package com.deepwistom.atoms.provider;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Ark adapter contract against a local stub: request shape, async task polling and the
 * public error mapping. No real provider call is made and no provider credits are consumed.
 */
class ArkClientTest {
    private static final String KEY = "ark-test-key-must-not-leak";
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final Deque<StubResponse> responses = new ArrayDeque<>();

    record StubResponse(int status, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body.isBlank() ? null : JsonMapper.builder().build().readTree(body));
            StubResponse response = responses.isEmpty() ? new StubResponse(500, "{}") : responses.poll();
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsVideoTaskWithTheProductSpecificationAndNeverLeaksTheKeyInMessages() {
        responses.add(new StubResponse(200, "{\"id\":\"cgt-20261113120000-abcde\"}"));
        var task = client().createVideoTask(new ArkClient.VideoRequest(
                "晨光穿过森林，镜头缓慢前进", null, 5, "720p", "16:9", false, false));

        assertThat(task.id()).isEqualTo("cgt-20261113120000-abcde");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + KEY);
        assertThat(lastPath.get()).isEqualTo("/api/v3/contents/generations/tasks");
        JsonNode body = lastBody.get();
        assertThat(body.path("model").asString()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(body.path("duration").asInt()).isEqualTo(5);
        assertThat(body.path("resolution").asString()).isEqualTo("720p");
        assertThat(body.path("ratio").asString()).isEqualTo("16:9");
        assertThat(body.path("content").get(0).path("text").asString()).contains("晨光");
    }

    @Test
    void sendsReferenceImageForImageToVideoMode() {
        responses.add(new StubResponse(200, "{\"id\":\"cgt-1\"}"));
        client().createVideoTask(new ArkClient.VideoRequest(
                "让画面动起来", "https://example.test/ref.png", 5, "720p", "16:9", false, false));

        JsonNode content = lastBody.get().path("content");
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get(1).path("type").asString()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asString()).isEqualTo("https://example.test/ref.png");
        assertThat(content.get(1).path("role").asString()).isEqualTo("reference_image");
    }

    @Test
    void mapsUnactivatedModelToATerminalProviderError() {
        responses.add(new StubResponse(404, """
                {"error":{"code":"ModelNotOpen","message":"Your account has not activated the model. Request id: 0217"}}
                """));
        assertThatThrownBy(() -> client().createVideoTask(
                new ArkClient.VideoRequest("prompt", null, 5, "720p", "16:9", false, false)))
                .isInstanceOfSatisfying(ProviderException.class, error -> {
                    assertThat(error.code()).isEqualTo("PROVIDER_MODEL_NOT_ACTIVATED");
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.getMessage()).doesNotContain(KEY);
                });
    }

    @Test
    void mapsAuthenticationFailureWithoutEchoingTheKey() {
        responses.add(new StubResponse(401, """
                {"error":{"code":"AuthenticationError","message":"The API key is invalid."}}
                """));
        assertThatThrownBy(() -> client().createVideoTask(
                new ArkClient.VideoRequest("prompt", null, 5, "720p", "16:9", false, false)))
                .isInstanceOfSatisfying(ProviderException.class, error -> {
                    assertThat(error.code()).isEqualTo("PROVIDER_AUTH_FAILED");
                    assertThat(error.getMessage()).doesNotContain(KEY);
                });
    }

    @Test
    void readsCompletedTaskWithStoredArtifactUrlAndUsage() {
        responses.add(new StubResponse(200, """
                {"id":"cgt-1","model":"doubao-seedance-2-0-260128","status":"succeeded",
                 "content":{"video_url":"https://ark-output.example.test/v.mp4"},
                 "usage":{"completion_tokens":108900}}
                """));
        var task = client().getVideoTask("cgt-1");

        assertThat(task.succeeded()).isTrue();
        assertThat(task.videoUrl()).isEqualTo("https://ark-output.example.test/v.mp4");
        assertThat(task.usageTokens()).isEqualTo(108900);
        assertThat(lastPath.get()).isEqualTo("/api/v3/contents/generations/tasks/cgt-1");
    }

    @Test
    void probeReportsActivatedModelFromValidationError() {
        responses.add(new StubResponse(400, """
                {"error":{"code":"InvalidParameter","message":"content field cannot be empty."}}
                """));
        assertThat(client().probeModel().state()).isEqualTo(ArkClient.ModelState.ACTIVATED);
        assertThat(lastBody.get().path("content").isArray()).isTrue();
        assertThat(lastBody.get().path("content").size()).isZero();
    }

    @Test
    void probeReportsUnactivatedModelAndInvalidKey() {
        responses.add(new StubResponse(404, """
                {"error":{"code":"ModelNotOpen","message":"not activated"}}
                """));
        assertThat(client().probeModel().state()).isEqualTo(ArkClient.ModelState.NOT_ACTIVATED);

        responses.add(new StubResponse(401, """
                {"error":{"code":"AuthenticationError","message":"invalid"}}
                """));
        assertThat(client().probeModel().state()).isEqualTo(ArkClient.ModelState.AUTH_FAILED);
    }

    @Test
    void probeReportsUnreachableHostWithoutThrowing() {
        var offline = new ArkClient(new ProviderProperties(
                KEY, "http://127.0.0.1:1/api/v3", "doubao-seedance-2-0-260128",
                5, "720p", "16:9", false, Duration.ofSeconds(10),
                Duration.ofSeconds(3), false,
                java.util.List.of(), Duration.ofMillis(300), Duration.ofMillis(500)),
                JsonMapper.builder().build());
        assertThat(offline.probeModel().state()).isEqualTo(ArkClient.ModelState.UNREACHABLE);
    }

    @Test
    void unconfiguredProviderRefusesToCreatePaidTasks() {
        var missing = new ArkClient(new ProviderProperties(
                "", baseUrl, "", 5, "720p", "16:9", false,
                Duration.ofSeconds(10), Duration.ofSeconds(3), false, java.util.List.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1)), JsonMapper.builder().build());
        assertThatThrownBy(() -> missing.createVideoTask(
                new ArkClient.VideoRequest("prompt", null, 5, "720p", "16:9", false, false)))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_NOT_CONFIGURED"));
    }

    private ArkClient client() {
        return new ArkClient(new ProviderProperties(
                KEY, baseUrl, "doubao-seedance-2-0-260128", 5, "720p", "16:9", false,
                Duration.ofSeconds(10), Duration.ofSeconds(3), false, java.util.List.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(2)), JsonMapper.builder().build());
    }
}
