package com.deepwistom.atoms;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"atoms.provider.api-key=sentinel-must-never-be-exposed",
                "atoms.provider.video-model=",
                "atoms.qwen.text-model=",
                "atoms.qwen.image-model="})
@ActiveProfiles("integration")
class SystemBoundaryTest {
    @LocalServerPort
    private int port;

    @Test
    void healthWorksWithConfiguredDatabaseAndHidesDetails() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsEntry("status", "UP");
        assertThat(body.keySet()).isSubsetOf("status", "groups");
        assertThat(response.body()).doesNotContain("sentinel", "password", "secret", "details", "components");
    }

    @Test
    void capabilitiesReportImplementedPipelinesWithoutLeakingSecrets() throws Exception {
        var response = get("/api/system/capabilities");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var json = JsonPath.parse(response.body());
        Map<String, Object> fields = json.read("$");
        assertThat(fields.keySet()).containsExactlyInAnyOrder(
                "generationAvailable", "projectManagementAvailable", "reasonCode", "message", "projectTypes");
        // The pipeline exists; whether a run succeeds depends on the model actually being activated,
        // which the run reports honestly instead of the capability endpoint hiding it.
        assertThat(json.<Boolean>read("$.generationAvailable")).isTrue();
        assertThat(json.<Boolean>read("$.projectManagementAvailable")).isTrue();
        assertThat(json.<String>read("$.reasonCode")).isEmpty();
        assertThat(json.<String>read("$.message")).isNotBlank();
        assertThat(json.<List<String>>read("$.projectTypes")).containsExactly("WEB", "GAME", "SLIDES", "VIDEO");
        assertThat(response.body()).doesNotContain("sentinel", "api-key", "password", "secret");
    }

    @Test
    void diagnosticConfigurationEndpointsAreNotPublished() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/configprops").statusCode()).isEqualTo(404);
    }

    @Test
    void providerStatusStaysClosedAndNeverEchoesSecrets() throws Exception {
        var response = get("/api/system/provider");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var json = JsonPath.parse(response.body());
        Map<String, Object> fields = json.read("$");
        assertThat(fields.keySet()).containsExactlyInAnyOrder("configured", "provider", "endpointHost",
                "videoModel", "state", "detail", "checkedAt", "defaultDurationSeconds", "defaultResolution",
                "textModel", "textState", "textDetail", "imageModel", "imageState", "imageDetail");
        assertThat(json.<Boolean>read("$.configured")).isFalse();
        assertThat(json.<String>read("$.state")).isEqualTo("NOT_CONFIGURED");
        // Web generation is reported separately and stays closed when no text model is configured.
        assertThat(json.<String>read("$.textState")).isEqualTo("NOT_CONFIGURED");
        assertThat(json.<String>read("$.textModel")).isEmpty();
        // Image is a separate model and stays closed when none is configured.
        assertThat(json.<String>read("$.imageState")).isEqualTo("NOT_CONFIGURED");
        assertThat(json.<String>read("$.imageModel")).isEmpty();
        assertThat(json.<String>read("$.provider")).isEqualTo("volcengine-ark");
        assertThat(response.body()).doesNotContain("sentinel", "api-key", "Bearer", "password", "secret");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
