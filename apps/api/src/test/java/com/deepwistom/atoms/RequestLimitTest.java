package com.deepwistom.atoms;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class RequestLimitTest {
    @LocalServerPort private int port;

    @Test void declaredAndChunkedBodiesAreBoundedBeforeJsonParsing() throws Exception {
        byte[] oversized = ("{\"prompt\":\"" + "x".repeat(70000) + "\"}").getBytes(StandardCharsets.UTF_8);
        try (var client = HttpClient.newHttpClient()) {
            for (var body : new HttpRequest.BodyPublisher[] {
                    HttpRequest.BodyPublishers.ofByteArray(oversized),
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)) }) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/projects"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .header("Origin", "http://127.0.0.1:5173").POST(body).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(response.body()).contains("REQUEST_TOO_LARGE").doesNotContain("xxxx");
            }
        }
    }
}
