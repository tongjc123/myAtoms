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
 * Web generation spends tokens on two model calls, so it takes a budget reservation before the first
 * one. With an exhausted ceiling the run still starts, then fails at the reservation step with a
 * visible BUDGET_EXCEEDED and <strong>no</strong> provider call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class WebBudgetApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static HttpServer stub;
    private static final AtomicInteger chatCalls = new AtomicInteger();

    @LocalServerPort
    private int port;

    record Session(String cookie, String csrf) {}

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v3/chat/completions", exchange -> {
            chatCalls.incrementAndGet();
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("atoms.provider.api-key", () -> "stub-key");
        registry.add("atoms.provider.base-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/api/v3");
        registry.add("atoms.provider.text-model", () -> "stub-chat-model");
        registry.add("atoms.budget.limit-cny", () -> "0.01");
        registry.add("atoms.budget.text-reserve-cny", () -> "1.00");
    }

    @Test
    void anExhaustedBudgetFailsTheWebRunBeforeAnyPaidCall() throws Exception {
        var session = session();
        String body = "{\"name\":\"预算页\",\"type\":\"WEB\",\"mode\":\"BUILD\",\"prompt\":\"做一个预算护栏说明页。\"}";
        var created = post("/api/projects", body, session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        String projectId = JsonPath.read(created.body(), "$.project.id");

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        long deadline = System.currentTimeMillis() + 30_000L;
        String snapshot = null;
        while (System.currentTimeMillis() < deadline) {
            var response = get("/api/projects/" + projectId + "/runs/" + runId, session.cookie());
            snapshot = response.body();
            String status = JsonPath.read(snapshot, "$.run.status");
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
                assertThat(status).isEqualTo("FAILED");
                break;
            }
            Thread.sleep(150);
        }
        assertThat(snapshot).isNotNull();
        assertThat(JsonPath.<String>read(snapshot, "$.run.errorCode")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(JsonPath.<List<Map<String, Object>>>read(snapshot, "$.events"))
                .extracting(event -> (String) event.get("type")).doesNotContain("artifact");
        assertThat(chatCalls.get()).isZero();

        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<Object>read(project.body(), "$.project.currentVersion")).isNull();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private Session session() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session"))
                .header("Origin", ORIGIN).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String csrf = JsonPath.read(response.body(), "$.csrfToken");
        return new Session(cookie, csrf);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String cookie, String csrf, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", ORIGIN).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        if (key != null) builder.header("Idempotency-Key", key);
        return client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}
