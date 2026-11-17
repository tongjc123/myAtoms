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

/**
 * The web pipeline is exercised against a local stub of the Qianwen gateway: the run really plans
 * the structure, writes the page, paints and inlines a hero image, validates it, commits an
 * immutable version and serves it through the preview surface. Model replies that would break the
 * sandbox (external assets) are rejected, and an unactivated model fails loudly instead of
 * producing a placeholder page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class WebRunApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String PROMPT = "做一个菜谱网站，可以选择各大菜系的菜谱来学习做菜。";
    private static final String MARKER = "麻婆豆腐";
    private static final String OUTLINE = """
            {"title":"今日菜单","tagline":"按菜系挑一道菜，跟着步骤做","cta":"开始做菜","sections":[
            {"id":"hero","heading":"今天想吃什么","points":["川菜","粤菜","家常菜"]},
            {"id":"steps","heading":"三道菜的步骤","points":["选菜","看步骤","开火"]}]}
            """;
    private static final String PAGE = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8"><title>今日菜单</title>
            <style>
            body{font-family:system-ui,sans-serif;margin:0;background:#faf8f4;color:#232323}
            main{max-width:960px;margin:0 auto;padding:64px 24px}
            h1{font-size:clamp(28px,5vw,52px);margin:0 0 16px}
            p{line-height:1.8;color:#555}
            .card{border:1px solid #e6e1d8;border-radius:16px;padding:24px;margin-top:24px}
            .hero-image{min-height:220px}
            </style></head>
            <body><main>
            <h1>今日菜单</h1><p>按菜系挑一道菜，跟着步骤做。</p>
            <div class="hero-image" aria-hidden="true"></div>
            <section class="card"><h2>川菜</h2><p>麻婆豆腐：豆腐切块，豆瓣酱炒香，勾芡收汁。</p></section>
            <script>document.querySelectorAll('.card').forEach((card)=>card.addEventListener('click',()=>card.classList.toggle('open')))</script>
            </main></body></html>
            """;
    private static final String EXTERNAL_PAGE = PAGE.replace("<h1>今日菜单</h1>",
            "<h1>今日菜单</h1><img src=\"https://example.com/cover.jpg\" alt=\"封面\">");

    private static HttpServer stub;
    private static final AtomicInteger chatCalls = new AtomicInteger();
    private static final AtomicInteger probes = new AtomicInteger();
    private static final AtomicReference<String> mode = new AtomicReference<>("ok");
    private static final AtomicReference<String> imageMode = new AtomicReference<>("ok");
    private static final AtomicReference<String> htmlRequest = new AtomicReference<>();

    @LocalServerPort
    private int port;

    record Session(String cookie, String csrf) {}

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"messages\":[]")) {
                probes.incrementAndGet();
                respond(exchange, 400, "{\"code\":\"InvalidParameter\",\"message\":\"Role must be in [...]\"}", "application/json");
                return;
            }
            if ("notopen".equals(mode.get())) {
                respond(exchange, 404,
                        "{\"code\":\"ModelNotOpen\",\"message\":\"Your account has not activated the model.\"}",
                        "application/json");
                return;
            }
            int call = chatCalls.incrementAndGet();
            if (call == 1) {
                respond(exchange, 200, completion(OUTLINE), "application/json");
                return;
            }
            htmlRequest.set(body);
            respond(exchange, 200,
                    completion("external".equals(mode.get()) ? EXTERNAL_PAGE : PAGE), "application/json");
        });
        // Image generation: return a download URL pointing back at this stub.
        stub.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("unpurchased".equals(imageMode.get())) {
                respond(exchange, 403,
                        "{\"code\":\"AccessDenied.Unpurchased\",\"message\":\"Access to model denied.\"}",
                        "application/json");
                return;
            }
            int port = stub.getAddress().getPort();
            respond(exchange, 200, "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"type\":\"image\","
                    + "\"image\":\"http://127.0.0.1:" + port + "/img.png\"}]},\"finish_reason\":\"stop\"}]}}",
                    "application/json");
        });
        // The hero image download: a valid 1x1 PNG (68 bytes).
        stub.createContext("/img.png", exchange -> {
            byte[] png = java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(png);
            }
            exchange.close();
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("atoms.qwen.api-key", () -> "stub-key-must-not-leak");
        registry.add("atoms.qwen.base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/api/v1");
        registry.add("atoms.qwen.text-model", () -> "stub-chat-model");
        registry.add("atoms.qwen.text-model-name", () -> "桩模型");
        registry.add("atoms.qwen.image-model", () -> "stub-image-model");
        registry.add("atoms.qwen.text-timeout", () -> "20s");
        // The hero image stub lives on 127.0.0.1; allow it for this test only.
        registry.add("atoms.qwen.allow-private-media-hosts", () -> "true");
        // The test database keeps reservations from earlier runs; a wide ceiling keeps this class
        // focused on the pipeline instead of on accumulated budget.
        registry.add("atoms.budget.limit-cny", () -> "100000.00");
        registry.add("atoms.budget.text-reserve-cny", () -> "1.00");
    }

    @BeforeEach
    void reset() {
        chatCalls.set(0);
        probes.set(0);
        htmlRequest.set(null);
        mode.set("ok");
        imageMode.set("ok");
    }

    @Test
    void webRunPlansWritesAndPublishesASingleFilePageWithInlinedHero() throws Exception {
        var session = session();
        String projectId = createWebProject(session);

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var snapshot = awaitTerminal(session, projectId, runId, "SUCCEEDED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.engine")).isEqualTo("qianwen-chat/1.0");
        assertThat(JsonPath.<String>read(snapshot, "$.run.modelKey")).isEqualTo("stub-chat-model");

        List<Map<String, Object>> events = JsonPath.read(snapshot, "$.events");
        assertThat(events).extracting(event -> (String) event.get("type")).contains("step", "artifact");
        var artifact = events.stream().filter(event -> "artifact".equals(event.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) artifact.get("data");
        assertThat(data.get("kind")).isEqualTo("WEB");
        assertThat(data.get("entryPath")).isEqualTo("index.html");
        assertThat(data.get("title")).isEqualTo("今日菜单");
        assertThat(data.get("sections")).isEqualTo(2);
        assertThat(data.get("model")).isEqualTo("桩模型");
        assertThat(data.get("heroImage")).isEqualTo(true);
        assertThat(data.get("heroImageModel")).isEqualTo("stub-image-model");

        // Two paid text calls: structure first, then the page written from that structure.
        assertThat(chatCalls.get()).isEqualTo(2);
        assertThat(htmlRequest.get()).contains("stub-chat-model").contains("今日菜单")
                .contains("hero").contains("steps").contains("按菜系挑一道菜");

        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<String>read(project.body(), "$.project.currentVersion.kind")).isEqualTo("GENERATED");

        var preview = post("/api/projects/" + projectId + "/preview-sessions", "{}",
                session.cookie(), session.csrf(), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        String baseUrl = JsonPath.read(preview.body(), "$.baseUrl");
        assertThat(JsonPath.<String>read(preview.body(), "$.entryPath")).isEqualTo("index.html");

        var page = get(baseUrl + "index.html", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(page.headers().firstValue("Content-Security-Policy").orElse(""))
                .contains("default-src 'none'");
        assertThat(page.body()).contains(MARKER).contains("今日菜单").doesNotContain("example.com")
                .contains("data:image/png;base64,").contains(".hero-image");
    }

    @Test
    void webRunRejectsAPageThatNeedsExternalResources() throws Exception {
        var session = session();
        String projectId = createWebProject(session);
        mode.set("external");

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var snapshot = awaitTerminal(session, projectId, runId, "FAILED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.errorCode")).isEqualTo("PROVIDER_OUTPUT_REJECTED");
        assertThat(JsonPath.<List<Map<String, Object>>>read(snapshot, "$.events"))
                .extracting(event -> (String) event.get("type")).doesNotContain("artifact");

        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<Object>read(project.body(), "$.project.currentVersion")).isNull();
    }

    @Test
    void webRunFailsHonestlyWhenTheTextModelIsNotActivated() throws Exception {
        var session = session();
        String projectId = createWebProject(session);
        mode.set("notopen");

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var snapshot = awaitTerminal(session, projectId, runId, "FAILED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.errorCode")).isEqualTo("PROVIDER_MODEL_NOT_ACTIVATED");
        var status = JsonPath.<List<Map<String, Object>>>read(snapshot, "$.events").stream()
                .filter(event -> "status".equals(event.get("type")))
                .map(event -> (Map<String, Object>) event.get("data"))
                .filter(data -> "FAILED".equals(data.get("status")))
                .findFirst().orElseThrow();
        assertThat(status.get("code")).isEqualTo("PROVIDER_MODEL_NOT_ACTIVATED");
        assertThat(String.valueOf(status.get("message"))).contains("开通");
    }

    @Test
    void webRunDegradesToTextOnlyWhenTheImageModelIsNotPurchased() throws Exception {
        var session = session();
        String projectId = createWebProject(session);
        imageMode.set("unpurchased");

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var snapshot = awaitTerminal(session, projectId, runId, "SUCCEEDED");
        var artifact = JsonPath.<List<Map<String, Object>>>read(snapshot, "$.events").stream()
                .filter(event -> "artifact".equals(event.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) artifact.get("data");
        assertThat(data.get("heroImage")).isEqualTo(false);
        assertThat(data.get("heroImageModel")).isNull();

        // The page is still published as a single file, without any data URI image.
        var preview = post("/api/projects/" + projectId + "/preview-sessions", "{}",
                session.cookie(), session.csrf(), null);
        String baseUrl = JsonPath.read(preview.body(), "$.baseUrl");
        var page = get(baseUrl + "index.html", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains(MARKER).doesNotContain("data:image/");
    }

    private String createWebProject(Session session) throws Exception {
        String body = "{\"name\":\"我的菜\",\"type\":\"WEB\",\"mode\":\"BUILD\",\"prompt\":\"" + PROMPT + "\"}";
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
