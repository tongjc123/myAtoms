package com.deepwistom.atoms;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class GameEditRunApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    @LocalServerPort private int port;
    record Session(String cookie, String csrf) {}

    private static final String GAME = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>Tap target</title>
            <style>body{font-family:system-ui;margin:0;padding:24px;background:#112;color:white}
            button{padding:24px;margin:16px;font-size:20px}main{max-width:700px;margin:auto}</style></head>
            <body><main><h1>Tap target</h1><p>Tap or press space to score five points and win.</p>
            <p id="score">0</p><button id="target">Tap</button><button id="restart">Restart</button>
            <script>let score=0;const display=document.querySelector('#score');
            function hit(){if(score<5)score++;display.textContent=score===5?'You win':score}
            document.querySelector('#target').onclick=hit;
            document.querySelector('#restart').onclick=()=>{score=0;display.textContent=0};
            document.addEventListener('keydown',e=>{if(e.code==='Space')hit()});</script></main></body></html>
            """;
    private static HttpServer stub;
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicInteger images = new AtomicInteger();
    private static final AtomicReference<String> request = new AtomicReference<>();
    private static final AtomicReference<String> output = new AtomicReference<>(GAME);
    private static final AtomicReference<java.util.function.UnaryOperator<String>> editReply = new AtomicReference<>();
    private static volatile java.util.concurrent.CountDownLatch entered;
    private static volatile java.util.concurrent.CountDownLatch release;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.deepwistom.atoms.project.ProjectVersionWriter writer;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Fixture timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            var transform = editReply.get();
            String reply = output.get();
            if (transform != null) {
                String user = JsonPath.read(request.get(), "$.input.messages[1].content");
                String delimiter = "当前完整 HTML 源码：\n";
                reply = transform.apply(user.substring(user.indexOf(delimiter) + delimiter.length()));
            }
            respond(exchange, 200, completion(reply), "application/json");
        });
        stub.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            images.incrementAndGet();
            respond(exchange, 500, "Unexpected image call", "text/plain");
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() { if (stub != null) stub.stop(0); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("atoms.qwen.api-key", () -> "local-fixture");
        registry.add("atoms.qwen.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/api/v1");
        registry.add("atoms.qwen.text-model", () -> "fixture-game");
        registry.add("atoms.qwen.image-model", () -> "must-not-call");
        registry.add("atoms.qwen.text-timeout", () -> "20s");
        registry.add("atoms.budget.limit-cny", () -> "100000.00");
        registry.add("atoms.budget.text-reserve-cny", () -> "1.00");
    }

    @BeforeEach
    void reset() {
        calls.set(0); images.set(0); request.set(null); output.set(GAME); editReply.set(null);
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(0);
    }

    @Test
    void gameFromScratchUsesGamePromptAndPersistsArtifactWithoutImage() throws Exception {
        var session = session();
        String project = create(session, "GAME");
        String run = start(session, project, "{}", null);
        String snapshot = awaitTerminal(session, project, run, "SUCCEEDED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.purpose")).isEqualTo("GAME");
        assertThat(JsonPath.<Object>read(snapshot, "$.run.baseVersionId")).isNull();
        List<Map<String, Object>> artifacts = JsonPath.read(snapshot, "$.events[?(@.type=='artifact')].data");
        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.getFirst()).containsEntry("kind", "GAME").containsEntry("entryPath", "index.html");
        assertThat(request.get()).contains("游戏循环").contains("手机触控");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(images.get()).isZero();
        assertThat(source(current(project))).isEqualTo(GAME.strip());
        // A late cancel after the atomic success transition cannot undo a committed version.
        var cancelled = post("/api/projects/" + project + "/runs/" + run + "/cancel", "{}",
                session.cookie(), session.csrf(), null);
        assertThat(JsonPath.<String>read(cancelled.body(), "$.status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void clonedGameEditsIncludeExactSourceAndSuccessiveBasesAndRejectStaleBase() throws Exception {
        var session = session();
        var cloned = post("/api/templates/star-swarm/clone", "{\"name\":\"Edit game\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(cloned.statusCode()).as(cloned.body()).isEqualTo(201);
        String project = JsonPath.read(cloned.body(), "$.project.id");
        String base = current(project);
        String original = source(base);
        String run = start(session, project, edit(base, "  Make targets purple  "), null);
        String snapshot = awaitTerminal(session, project, run, "SUCCEEDED");
        assertThat(JsonPath.<String>read(request.get(), "$.input.messages[1].content")).contains(original).contains(base);
        assertThat(JsonPath.<String>read(snapshot, "$.run.instruction")).isEqualTo("Make targets purple");
        assertThat(JsonPath.<String>read(snapshot, "$.run.baseVersionId")).isEqualTo(base);
        String second = current(project);
        assertThat(second).isNotEqualTo(base);
        assertThat(source(base)).isEqualTo(original);
        output.set(GAME.replace("Tap target", "Purple target"));
        String next = start(session, project, edit(second, "Add a purple title"), null);
        awaitTerminal(session, project, next, "SUCCEEDED");
        assertThat(JsonPath.<String>read(request.get(), "$.input.messages[1].content")).contains(source(second));
        assertThat(jdbc.queryForObject("SELECT parent_version_id FROM project_versions WHERE id=?",
                String.class, current(project))).isEqualTo(second);
        var stale = post("/api/projects/" + project + "/runs", edit(base, "Old base"),
                session.cookie(), session.csrf(), null);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("VERSION_CONFLICT");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(images.get()).isZero();
    }

    @Test
    void webEditIncludesCurrentHtmlAndFailureKeepsTheSuccessfulVersion() throws Exception {
        var session = session();
        var cloned = post("/api/templates/web-product-launch/clone", "{\"name\":\"Edit web\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(cloned.statusCode()).as(cloned.body()).isEqualTo(201);
        String project = JsonPath.read(cloned.body(), "$.project.id");
        String base = current(project);
        String run = start(session, project, edit(base, "Change title"), null);
        awaitTerminal(session, project, run, "SUCCEEDED");
        assertThat(JsonPath.<String>read(request.get(), "$.input.messages[1].content")).contains(source(base));
        String good = current(project);
        output.set("Incomplete output");
        String failed = start(session, project, edit(good, "Add details"), null);
        String snapshot = awaitTerminal(session, project, failed, "FAILED");
        assertThat(snapshot).contains("PROVIDER_OUTPUT_REJECTED");
        assertThat(current(project)).isEqualTo(good);
        assertThat(images.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM provider_spend WHERE run_id=?", String.class, failed))
                .isEqualTo("RESERVED");
    }

    @Test
    void duplicateConcurrentStartsReuseOneRunAndOtherKeysAreExcluded() throws Exception {
        var session = session();
        String project = create(session, "GAME");
        String key = UUID.randomUUID().toString();
        release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); return start(session, project, "{}", key); });
            var second = executor.submit(() -> { gate.await(); return start(session, project, "{}", key); });
            gate.countDown();
            String run = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(run);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var overlap = post("/api/projects/" + project + "/runs", "{}", session.cookie(), session.csrf(),
                    UUID.randomUUID().toString());
            assertThat(overlap.statusCode()).isEqualTo(409);
            assertThat(overlap.body()).contains("RUN_IN_PROGRESS");
            var conflict = post("/api/projects/" + project + "/runs", "{\"model\":\"changed\"}",
                    session.cookie(), session.csrf(), key);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(conflict.body()).contains("IDEMPOTENCY_CONFLICT");
            release.countDown();
            awaitTerminal(session, project, run, "SUCCEEDED");
            assertThat(start(session, project, "{}", key)).isEqualTo(run);
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void cancelWhileProviderReturnsCannotCommitAndRetainsAttemptedReservation() throws Exception {
        var session = session();
        String project = create(session, "GAME");
        release = new java.util.concurrent.CountDownLatch(1);
        try {
            String run = start(session, project, "{}", null);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(post("/api/projects/" + project + "/runs/" + run + "/cancel", "{}",
                    session.cookie(), session.csrf(), null).statusCode()).isEqualTo(200);
            release.countDown();
            awaitTerminal(session, project, run, "CANCELLED");
            assertThat(current(project)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_versions WHERE project_id=?",
                    Integer.class, project)).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM provider_spend WHERE run_id=?",
                    String.class, run)).isEqualTo("RESERVED");
        } finally { release.countDown(); }
    }

    @Test
    void finalWriterRejectsCancellationDeletionAndBaseChangesAtomically() throws Exception {
        var session = session();
        String project = create(session, "GAME");
        String first = start(session, project, "{}", null);
        awaitTerminal(session, project, first, "SUCCEEDED");
        String base = current(project);
        release = new java.util.concurrent.CountDownLatch(1);
        entered = new java.util.concurrent.CountDownLatch(1);
        try {
            String run = start(session, project, "{}", null);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT base_version_id FROM runs WHERE id=?", String.class, run)).isEqualTo(base);
            // Exercise the final transaction directly, including the window after the worker's last check.
            jdbc.update("UPDATE runs SET status='RUNNING',cancel_requested=1 WHERE id=?", run);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> commit(project, run))
                    .isInstanceOf(com.deepwistom.atoms.project.ProjectVersionWriter.CommitCancelled.class);
            jdbc.update("UPDATE runs SET cancel_requested=0,base_version_id=NULL WHERE id=?", run);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> commit(project, run))
                    .isInstanceOf(com.deepwistom.atoms.common.ApiException.class)
                    .hasMessageContaining("当前版本");
            jdbc.update("UPDATE runs SET base_version_id=? WHERE id=?", base, run);
            jdbc.update("UPDATE projects SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", project);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> commit(project, run))
                    .isInstanceOf(com.deepwistom.atoms.project.ProjectVersionWriter.CommitCancelled.class);
            jdbc.update("UPDATE projects SET deleted_at=NULL WHERE id=?", project);
            jdbc.update("UPDATE runs SET cancel_requested=1 WHERE id=?", run);
            release.countDown();
            awaitTerminal(session, project, run, "CANCELLED");
            assertThat(current(project)).isEqualTo(base);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_versions WHERE project_id=?",
                    Integer.class, project)).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void simultaneousDistinctRequestsCreateOnlyOneRun() throws Exception {
        var session = session();
        String project = create(session, "GAME");
        release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<HttpResponse<String>> submit = () -> {
                gate.await();
                return post("/api/projects/" + project + "/runs", "{}", session.cookie(), session.csrf(),
                        UUID.randomUUID().toString());
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            gate.countDown();
            var responses = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(202, 409);
            var accepted = responses.stream().filter(r -> r.statusCode() == 202).findFirst().orElseThrow();
            assertThat(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow().body())
                    .contains("RUN_IN_PROGRESS");
            release.countDown();
            awaitTerminal(session, project, JsonPath.read(accepted.body(), "$.runId"), "SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE project_id=?", Integer.class, project))
                    .isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void invalidOrOversizeEditNeverEnqueuesOrCallsProvider() throws Exception {
        var session = session();
        String project = create(session, "GAME");
        var missingBase = post("/api/projects/" + project + "/runs", "{\"instruction\":\"Change colors\"}",
                session.cookie(), session.csrf(), null);
        assertThat(missingBase.statusCode()).isEqualTo(400);
        var badKey = post("/api/projects/" + project + "/runs", "{}", session.cookie(), session.csrf(), "bad");
        assertThat(badKey.statusCode()).isEqualTo(400);
        String slides = create(session, "SLIDES");
        var wrongType = post("/api/projects/" + slides + "/runs", edit(UUID.randomUUID().toString(), "Change"),
                session.cookie(), session.csrf(), null);
        assertThat(wrongType.statusCode()).isEqualTo(400);
        var clone = post("/api/templates/star-swarm/clone", "{\"name\":\"Large source\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        String cloned = JsonPath.read(clone.body(), "$.project.id");
        String base = current(cloned);
        byte[] large = (GAME + " ".repeat(180_001)).getBytes(StandardCharsets.UTF_8);
        jdbc.update("UPDATE project_files SET content=?,size_bytes=? WHERE version_id=? AND path='index.html'",
                large, large.length, base);
        var oversized = post("/api/projects/" + cloned + "/runs", edit(base, "Change"),
                session.cookie(), session.csrf(), null);
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(oversized.body()).contains("EDIT_CONTEXT_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE project_id=?", Integer.class, cloned)).isZero();
        assertThat(calls.get()).isZero();
    }

    @Test
    void instructionAccepts8000CharactersAndRejects8001BeforeProviderCall() throws Exception {
        var session = session();
        var clone = post("/api/templates/star-swarm/clone", "{\"name\":\"Instruction boundary\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        String project = JsonPath.read(clone.body(), "$.project.id");
        String base = current(project);
        var tooLong = post("/api/projects/" + project + "/runs", edit(base, "x".repeat(8001)),
                session.cookie(), session.csrf(), null);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE project_id=?", Integer.class, project)).isZero();
        String instruction = "x".repeat(8000);
        String run = start(session, project, edit(base, "  " + instruction + "  "), null);
        String snapshot = awaitTerminal(session, project, run, "SUCCEEDED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.instruction")).isEqualTo(instruction);
        assertThat(JsonPath.<String>read(request.get(), "$.input.messages[1].content")).contains(instruction);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void largeHeroEditUsesOpaqueContextAndRestoresExactAssetWhileRejectingLostReferences() throws Exception {
        var session = session();
        var clone = post("/api/templates/web-product-launch/clone", "{\"name\":\"Large hero edit\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        String project = JsonPath.read(clone.body(), "$.project.id");
        String base = current(project);
        // A valid small PNG padded to the payload size of a normal generated hero.
        byte[] png = java.util.Arrays.copyOf(java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="),
                310_000);
        String asset = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        byte[] html = com.deepwistom.atoms.web.WebHtml.injectHero(
                GAME.strip().getBytes(StandardCharsets.UTF_8), asset);
        String original = new String(html, StandardCharsets.UTF_8);
        jdbc.update("UPDATE project_files SET content=?,size_bytes=? WHERE version_id=? AND path='index.html'",
                html, html.length, base);
        assertThat(html.length).isGreaterThan(410_000);
        editReply.set(source -> source.replace("Tap target", "Updated title"));
        String run = start(session, project, edit(base, "Change the title, keep the image"), null);
        awaitTerminal(session, project, run, "SUCCEEDED");
        String context = JsonPath.read(request.get(), "$.input.messages[1].content");
        assertThat(context).contains("__ATOMS_ASSET_").doesNotContain(asset).doesNotContain(asset.substring(0, 1000));
        assertThat(context.length()).isLessThan(10_000);
        String good = current(project);
        assertThat(source(good)).isEqualTo(original.replace("Tap target", "Updated title"));
        assertThat(source(base)).isEqualTo(original);
        assertThat(images.get()).isZero();

        for (String broken : List.of("__ATOMS_ASSET_unknown__", "", "__ATOMS_ASSET_broken")) {
            editReply.set(source -> source.replaceAll("__ATOMS_ASSET_[0-9a-f]{32}_[0-9]+__", broken));
            String failed = start(session, project, edit(good, "Keep all images"), null);
            String snapshot = awaitTerminal(session, project, failed, "FAILED");
            assertThat(snapshot).contains("PROVIDER_OUTPUT_REJECTED");
            assertThat(current(project)).isEqualTo(good);
            assertThat(source(good)).isEqualTo(original.replace("Tap target", "Updated title"));
        }
        assertThat(images.get()).isZero();
    }

    private void commit(String project, String run) {
        writer.writeForRun(project, run, "index.html", "guard test",
                Map.of("index.html", GAME.getBytes(StandardCharsets.UTF_8)), null,
                new java.util.LinkedHashMap<>(Map.of("kind", "GAME")));
    }

    private String create(Session session, String type) throws Exception {
        var created = post("/api/projects", "{\"name\":\"Fixture\",\"type\":\"" + type
                + "\",\"mode\":\"BUILD\",\"prompt\":\"Build an interactive target game\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.project.id");
    }

    private String start(Session session, String project, String body, String key) throws Exception {
        var response = post("/api/projects/" + project + "/runs", body, session.cookie(), session.csrf(), key);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JsonPath.read(response.body(), "$.runId");
    }

    private String current(String project) {
        return jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", String.class, project);
    }

    private String source(String version) {
        return new String(jdbc.queryForObject("SELECT content FROM project_files WHERE version_id=? AND path='index.html'",
                byte[].class, version), StandardCharsets.UTF_8);
    }

    private String edit(String base, String instruction) {
        return "{\"baseVersionId\":\"" + base + "\",\"instruction\":\"" + instruction + "\"}";
    }

    private String awaitTerminal(Session session, String projectId, String runId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000L;
        String body = null;
        while (System.currentTimeMillis() < deadline) {
            var response = get("/api/projects/" + projectId + "/runs/" + runId, session.cookie());
            assertThat(response.statusCode()).isEqualTo(200);
            body = response.body();
            String status = JsonPath.read(body, "$.run.status");
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
                assertThat(status).isEqualTo(expected);
                return body;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Run did not reach " + expected + " in time: " + body);
    }

    private static String completion(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
        return "{\"output\":{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped
               + "\"},\"finish_reason\":\"stop\"}]},\"usage\":{\"input_tokens\":120,\"output_tokens\":640}}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                                String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private Session session() throws Exception {
        var request = requestBuilder("/api/session")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        var response = client().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String csrf = JsonPath.read(response.body(), "$.csrfToken");
        return new Session(cookie, csrf);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var builder = requestBuilder(path).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String cookie, String csrf, String key) throws Exception {
        var builder = requestBuilder(path)
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        if (key != null) builder.header("Idempotency-Key", key);
        return client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : "http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}
