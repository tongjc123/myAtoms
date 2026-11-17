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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The video pipeline is exercised against a local stub of the provider: the run really reserves
 * budget, submits the task, polls it, downloads the media and publishes an immutable version that
 * the preview surface then serves with byte ranges. No provider credit is consumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class VideoRunApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String PROMPT = "雨夜霓虹街头，一位撑透明雨伞的行人缓步前行，积水倒映灯箱，镜头缓慢前推。";
    private static final byte[] CLIP = pseudoMp4();
    private static HttpServer stub;
    private static final AtomicInteger polls = new AtomicInteger();
    private static final AtomicReference<String> lastCreateBody = new AtomicReference<>();
    private static final AtomicInteger deletes = new AtomicInteger();

    @LocalServerPort
    private int port;

    record Session(String cookie, String csrf) {}

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v3/contents/generations/tasks", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                lastCreateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"id\":\"cgt-stub-1\"}", "application/json");
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                respond(exchange, 200, "{\"id\":\"cgt-stub-1\"}", "application/json");
            } else {
                respond(exchange, 405, "{}", "application/json");
            }
        });
        stub.createContext("/api/v3/contents/generations/tasks/cgt-stub-1", exchange -> {
            // First poll is still rendering, the second one succeeds.
            String body = polls.incrementAndGet() < 2
                    ? "{\"id\":\"cgt-stub-1\",\"status\":\"running\"}"
                    : "{\"id\":\"cgt-stub-1\",\"status\":\"succeeded\",\"content\":{\"video_url\":\""
                      + mediaUrl() + "\"},\"usage\":{\"completion_tokens\":321}}";
            respond(exchange, 200, body, "application/json");
        });
        stub.createContext("/media/clip.mp4", exchange ->
                respond(exchange, 200, CLIP, "video/mp4"));
        stub.start();
    }

    private static String mediaUrl() {
        return "http://127.0.0.1:" + stub.getAddress().getPort() + "/media/clip.mp4";
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("atoms.provider.api-key", () -> "stub-key-must-not-leak");
        registry.add("atoms.provider.base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/api/v3");
        registry.add("atoms.provider.video-models[0]", () -> "seedance-2-0|Seedance 2.0|多镜头视频|test-model-001|");
        registry.add("atoms.provider.video-models[1]", () -> "wan-3-0|Wan 3.0|未配置模型||");
        registry.add("atoms.provider.allow-private-media-hosts", () -> "true");
        registry.add("atoms.provider.poll-interval", () -> "200ms");
        registry.add("atoms.provider.video-timeout", () -> "30s");
        registry.add("atoms.budget.limit-cny", () -> "300.00");
        registry.add("atoms.budget.video-reserve-cny", () -> "6.00");
    }

    @Test
    void videoRunPublishesAVersionAndServesMediaWithRanges() throws Exception {
        var session = session();
        String projectId = createVideoProject(session);

        var options = get("/api/video/options", null);
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Map<String, Object>>>read(options.body(), "$.models"))
                .anySatisfy(model -> assertThat(model.get("key")).isEqualTo("seedance-2-0"))
                .anySatisfy(model -> assertThat(model.get("available")).isEqualTo(false));

        polls.set(0);
        var started = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"seedance-2-0\",\"ratio\":\"16:9\",\"resolution\":\"720p\",\"durationSeconds\":5}",
                session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var snapshot = awaitTerminal(session, projectId, runId, "SUCCEEDED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.engine")).isEqualTo("volcengine-ark/1.0");
        assertThat(JsonPath.<String>read(snapshot, "$.run.modelKey")).isEqualTo("seedance-2-0");
        assertThat(JsonPath.<Integer>read(snapshot, "$.run.spec.durationSeconds")).isEqualTo(5);
        List<Map<String, Object>> events = JsonPath.read(snapshot, "$.events");
        assertThat(events).extracting(event -> (String) event.get("type"))
                .contains("step", "progress", "artifact");
        var artifact = events.stream().filter(event -> "artifact".equals(event.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) artifact.get("data");
        assertThat(data.get("kind")).isEqualTo("VIDEO");
        assertThat(data.get("mediaPath")).isEqualTo("video.mp4");
        assertThat(data.get("durationSeconds")).isEqualTo(5);

        // The provider really received the compiled prompt with the chosen specification.
        String submitted = lastCreateBody.get();
        assertThat(submitted).contains("test-model-001").contains("雨夜霓虹")
                .contains("\"duration\":5").contains("\"resolution\":\"720p\"").contains("\"ratio\":\"16:9\"");

        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<String>read(project.body(), "$.project.currentVersion.kind")).isEqualTo("GENERATED");

        var preview = post("/api/projects/" + projectId + "/preview-sessions", "{}",
                session.cookie(), session.csrf(), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        String media = JsonPath.<String>read(preview.body(), "$.baseUrl") + "video.mp4";

        var full = getBytes(media);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.headers().firstValue("Content-Type").orElse("")).contains("video/mp4");
        assertThat(full.headers().firstValue("Accept-Ranges").orElse("")).isEqualTo("bytes");
        assertThat(full.body()).isEqualTo(CLIP);

        var ranged = getBytes(media, "bytes=0-99");
        assertThat(ranged.statusCode()).isEqualTo(206);
        assertThat(ranged.headers().firstValue("Content-Range").orElse(""))
                .isEqualTo("bytes 0-99/" + CLIP.length);
        assertThat(ranged.body().length).isEqualTo(100);
    }

    @Test
    void cancellingAVideoRunLeavesNoVersionBehind() throws Exception {
        var session = session();
        String projectId = createVideoProject(session);
        var started = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"seedance-2-0\",\"durationSeconds\":6}",
                session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var cancelled = post("/api/projects/" + projectId + "/runs/" + runId + "/cancel", "{}",
                session.cookie(), session.csrf(), null);
        assertThat(cancelled.statusCode()).isEqualTo(200);

        var snapshot = awaitTerminal(session, projectId, runId, "CANCELLED");
        assertThat(JsonPath.<List<Map<String, Object>>>read(snapshot, "$.events"))
                .extracting(event -> (String) event.get("type")).doesNotContain("artifact");
        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<Object>read(project.body(), "$.project.currentVersion")).isNull();
    }

    @Test
    void videoRunsValidateSpecAndRejectUnavailableModels() throws Exception {
        var session = session();
        String projectId = createVideoProject(session);

        var badRatio = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"seedance-2-0\",\"ratio\":\"5:4\"}", session.cookie(), session.csrf(), null);
        assertThat(badRatio.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(badRatio.body(), "$.code")).isEqualTo("VALIDATION_ERROR");

        var badDuration = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"seedance-2-0\",\"durationSeconds\":99}", session.cookie(), session.csrf(), null);
        assertThat(badDuration.statusCode()).isEqualTo(400);

        var unavailable = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"wan-3-0\"}", session.cookie(), session.csrf(), null);
        assertThat(unavailable.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(unavailable.body(), "$.code")).isEqualTo("VIDEO_MODEL_NOT_CONFIGURED");

        var unknown = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"not-a-model\"}", session.cookie(), session.csrf(), null);
        assertThat(unknown.statusCode()).isEqualTo(400);
    }

    private String createVideoProject(Session session) throws Exception {
        String body = "{\"name\":\"雨夜霓虹\",\"type\":\"VIDEO\",\"mode\":\"BUILD\",\"prompt\":\"" + PROMPT + "\"}";
        var created = post("/api/projects", body, session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.project.id");
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

    /** A tiny but structurally valid MP4 prefix: the pipeline only needs bytes, the test needs determinism. */
    private static byte[] pseudoMp4() {
        byte[] bytes = new byte[4096];
        byte[] header = {0, 0, 0, 32, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        System.arraycopy(header, 0, bytes, 0, header.length);
        for (int index = header.length; index < bytes.length; index++) bytes[index] = (byte) (index % 251);
        return bytes;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body,
                                String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                                String contentType) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
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

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client().send(requestBuilder(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> getBytes(String path, String range) throws Exception {
        return client().send(requestBuilder(path).header("Range", range).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
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
