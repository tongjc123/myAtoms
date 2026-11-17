package com.deepwistom.atoms;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The budget guard is enforced inside the run, not by hiding the button: with an exhausted
 * ceiling the run still starts, then fails at the reservation step with a visible BUDGET_EXCEEDED
 * event and no provider call ever happens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atoms.provider.api-key=sentinel-must-never-be-exposed",
                "atoms.provider.video-models[0]=seedance-2-0|Seedance 2.0|多镜头视频|test-model-001|",
                "atoms.budget.limit-cny=0.01",
                "atoms.budget.video-reserve-cny=6.00",
        })
@ActiveProfiles("integration")
class VideoBudgetApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";

    @LocalServerPort
    private int port;

    record Session(String cookie, String csrf) {}

    @Test
    void anExhaustedBudgetFailsTheRunBeforeAnyPaidCall() throws Exception {
        var session = session();
        String body = "{\"name\":\"预算验证\",\"type\":\"VIDEO\",\"mode\":\"BUILD\",\"prompt\":\"测试预算护栏。\"}";
        var created = post("/api/projects", body, session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        String projectId = JsonPath.read(created.body(), "$.project.id");

        var started = post("/api/projects/" + projectId + "/runs",
                "{\"model\":\"seedance-2-0\",\"durationSeconds\":5}", session.cookie(), session.csrf(), null);
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
                assertThat(JsonPath.<String>read(snapshot, "$.run.errorCode")).isEqualTo("BUDGET_EXCEEDED");
                List<Map<String, Object>> events = JsonPath.read(snapshot, "$.events");
                assertThat(events).extracting(event -> (String) event.get("type"))
                        .doesNotContain("artifact");
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Run did not fail in time: " + snapshot);
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
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}
