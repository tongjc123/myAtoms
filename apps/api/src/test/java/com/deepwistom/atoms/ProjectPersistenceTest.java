package com.deepwistom.atoms;

import com.jayway.jsonpath.JsonPath;
import com.deepwistom.atoms.project.IdempotencyService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class ProjectPersistenceTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String CREATE = """
            {"name":"阅读清单","type":"WEB","prompt":"记录阅读计划并分类","mode":"BUILD"}
            """;
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IdempotencyService idempotency;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private record Visitor(String id, String cookie, String csrf) {}

    @AfterEach void closeClient() { client.close(); }

    @Test void createReadRenameDeleteUsesRealStorage() throws Exception {
        Visitor owner = visitor();
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isZero();
        var created = send(owner, "POST", "/api/projects", CREATE, key());
        assertThat(created.statusCode()).isEqualTo(201);
        String id = string(created, "$.project.id");
        assertThat(string(created, "$.project.status")).isEqualTo("DRAFT");
        assertThat(created.body()).doesNotContain("runId");
        var loaded = send(owner, "GET", "/api/projects/" + id, null, null);
        assertThat(string(loaded, "$.project.prompt")).isEqualTo("记录阅读计划并分类");
        assertThat(string(loaded, "$.project.createdAt")).endsWith("Z");
        LocalDateTime saved = jdbc.queryForObject("SELECT created_at FROM projects WHERE id=?", LocalDateTime.class, id);
        assertThat(Duration.between(saved.toInstant(ZoneOffset.UTC), Instant.now()).abs()).isLessThan(Duration.ofSeconds(10));
        var renamed = send(owner, "PATCH", "/api/projects/" + id,
                "{\"name\":\"新阅读清单\",\"expectedRevision\":1}", key());
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(number(renamed, "$.project.revision")).isEqualTo(2);
        assertThat(string(send(owner, "GET", "/api/projects/" + id, null, null), "$.project.name")).isEqualTo("新阅读清单");
        assertThat(send(owner, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":2}", key()).statusCode()).isEqualTo(204);
        assertThat(send(owner, "GET", "/api/projects/" + id, null, null).statusCode()).isEqualTo(404);
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM projects WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test void independentVisitorsCannotReadOrMutateOthersProjects() throws Exception {
        Visitor owner = visitor();
        Visitor other = visitor();
        String id = string(send(owner, "POST", "/api/projects", CREATE, key()), "$.project.id");
        assertThat(send(null, "GET", "/api/projects", null, null).statusCode()).isEqualTo(401);
        assertThat(number(send(other, "GET", "/api/projects", null, null), "$.total")).isZero();
        assertThat(send(other, "GET", "/api/projects/" + id, null, null).statusCode()).isEqualTo(404);
        assertThat(send(other, "PATCH", "/api/projects/" + id, "{\"name\":\"非法修改\",\"expectedRevision\":1}", key()).statusCode()).isEqualTo(404);
        assertThat(send(other, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":1}", key()).statusCode()).isEqualTo(404);
        String forged = CREATE.strip().replace("\"mode\":\"BUILD\"", "\"mode\":\"BUILD\",\"owner_session_id\":\"" + owner.id() + "\"");
        var response = send(other, "POST", "/api/projects", forged, key());
        // Unknown fields may be rejected; if accepted, ownership is still exclusively server-derived.
        if (response.statusCode() == 201) {
            String forgedId = string(response, "$.project.id");
            assertThat(send(owner, "GET", "/api/projects/" + forgedId, null, null).statusCode()).isEqualTo(404);
            assertThat(jdbc.queryForObject("SELECT owner_session_id FROM projects WHERE id=?", String.class, forgedId)).isEqualTo(other.id());
        } else {
            assertThat(response.statusCode()).isEqualTo(400);
        }
        assertThat(string(send(owner, "GET", "/api/projects/" + id, null, null), "$.project.name")).isEqualTo("阅读清单");
    }

    @Test void mutationsRequireOriginCsrfAndIdempotencyKey() throws Exception {
        Visitor owner = visitor();
        var base = request(owner, "POST", "/api/projects", CREATE, key());
        assertThat(client.send(base.setHeader("Origin", "https://untrusted.example").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(client.send(request(owner, "POST", "/api/projects", CREATE, key()).setHeader("X-CSRF-Token", "wrong").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var missingOrigin = HttpRequest.newBuilder(uri("/api/session")).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(missingOrigin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(send(owner, "POST", "/api/projects", CREATE, null).statusCode()).isEqualTo(400);
        var form = request(owner, "POST", "/api/projects", "name=test", key()).setHeader("Content-Type", "application/x-www-form-urlencoded");
        assertThat(client.send(form.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isZero();
    }

    @Test void repeatedCommandsReplayButKeyReuseCannotChangeMeaning() throws Exception {
        Visitor owner = visitor();
        String createKey = key();
        var first = send(owner, "POST", "/api/projects", CREATE, createKey);
        var replay = send(owner, "POST", "/api/projects", CREATE, createKey);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(send(owner, "POST", "/api/projects", CREATE.replace("阅读清单", "另一项目"), createKey).statusCode()).isEqualTo(409);
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isEqualTo(1);
        String id = string(first, "$.project.id");
        assertThat(send(owner, "PATCH", "/api/projects/" + id, "{\"name\":\"更名\",\"expectedRevision\":1}", createKey).statusCode()).isEqualTo(409);
        String deleteKey = key();
        assertThat(send(owner, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":1}", deleteKey).statusCode()).isEqualTo(204);
        assertThat(send(owner, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":1}", deleteKey).statusCode()).isEqualTo(204);
        assertThat(send(owner, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":1}", key()).statusCode()).isEqualTo(404);
        var deletedReplay = send(owner, "POST", "/api/projects", CREATE, createKey);
        assertThat(deletedReplay.statusCode()).isEqualTo(404);
        assertThat(deletedReplay.body()).doesNotContain("阅读清单", "记录阅读计划");
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isZero();
    }

    @Test void failureAfterDatabaseWriteRollsBackProjectAndReceiptTogether() throws Exception {
        Visitor owner = visitor();
        String requestKey = key();
        assertThatThrownBy(() -> idempotency.execute(owner.id(), requestKey, "forced-rollback-test", () -> {
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO projects (id,owner_session_id,name,type,prompt,mode,created_at,updated_at)
                    VALUES (?,?,?,'WEB',?,'BUILD',?,?)
                    """, key(), owner.id(), "rollback-only", "must not persist", now, now);
            throw new IllegalStateException("deliberate test failure after insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_requests WHERE owner_session_id=? AND request_key=?",
                Long.class, owner.id(), requestKey)).isZero();
        assertThat(send(owner, "POST", "/api/projects", CREATE, requestKey).statusCode()).isEqualTo(201);
    }

    @Test void concurrentDuplicatesProduceOneProjectAndConflictingEditsDoNotOverwrite() throws Exception {
        Visitor owner = visitor();
        String requestKey = key();
        var first = client.sendAsync(request(owner, "POST", "/api/projects", CREATE, requestKey).build(), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request(owner, "POST", "/api/projects", CREATE, requestKey).build(), HttpResponse.BodyHandlers.ofString());
        var a = first.get(15, TimeUnit.SECONDS);
        var b = second.get(15, TimeUnit.SECONDS);
        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.body()).isEqualTo(a.body());
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isEqualTo(1);
        String id = string(a, "$.project.id");
        var editA = client.sendAsync(request(owner, "PATCH", "/api/projects/" + id, "{\"name\":\"A\",\"expectedRevision\":1}", key()).build(), HttpResponse.BodyHandlers.ofString());
        var editB = client.sendAsync(request(owner, "PATCH", "/api/projects/" + id, "{\"name\":\"B\",\"expectedRevision\":1}", key()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(editA.get(15, TimeUnit.SECONDS).statusCode(), editB.get(15, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 409);
        assertThat(number(send(owner, "GET", "/api/projects/" + id, null, null), "$.project.revision")).isEqualTo(2);
    }

    @Test void staleDeletePreservesUpdatedProjectAndFailedRequestDoesNotPoisonRetry() throws Exception {
        Visitor owner = visitor();
        String id = string(send(owner, "POST", "/api/projects", CREATE, key()), "$.project.id");
        assertThat(send(owner, "PATCH", "/api/projects/" + id, "{\"name\":\"已更新\",\"expectedRevision\":1}", key()).statusCode()).isEqualTo(200);
        String retryKey = key();
        assertThat(send(owner, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":1}", retryKey).statusCode()).isEqualTo(409);
        assertThat(string(send(owner, "GET", "/api/projects/" + id, null, null), "$.project.name")).isEqualTo("已更新");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_requests WHERE owner_session_id=? AND request_key=?", Long.class, owner.id(), retryKey)).isZero();
        assertThat(send(owner, "DELETE", "/api/projects/" + id, "{\"expectedRevision\":2}", retryKey).statusCode()).isEqualTo(204);
    }

    @Test void malformedAndOversizedFieldsNeverCreateProjects() throws Exception {
        Visitor owner = visitor();
        for (String invalid : List.of(
                CREATE.replace("阅读清单", "   "),
                CREATE.replace("阅读清单", "x".repeat(81)),
                CREATE.replace("记录阅读计划并分类", ""),
                CREATE.replace("记录阅读计划并分类", "x".repeat(8001)),
                CREATE.replace("WEB", "SERVER"),
                CREATE.replace("BUILD", "UNKNOWN"),
                CREATE.replace("\"WEB\"", "0"),
                CREATE.replace("\"WEB\"", "\"0\""),
                CREATE.replace("\"BUILD\"", "1"),
                CREATE.replace("\"BUILD\"", "\"1\""), "null", "{broken")) {
            var response = send(owner, "POST", "/api/projects", invalid, key());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(string(response, "$.code")).isEqualTo("VALIDATION_ERROR");
            assertThat(string(response, "$.requestId")).isNotBlank();
        }
        assertThat(send(owner, "GET", "/api/projects?page=-1", null, null).statusCode()).isEqualTo(400);
        assertThat(send(owner, "GET", "/api/projects?pageSize=51", null, null).statusCode()).isEqualTo(400);
        assertThat(number(send(owner, "GET", "/api/projects", null, null), "$.total")).isZero();
    }

    @Test void sessionPersistsWithCookieButExpiredIdentityCannotRecoverOldProjects() throws Exception {
        Visitor owner = visitor();
        String projectId = string(send(owner, "POST", "/api/projects", CREATE, key()), "$.project.id");
        var renewed = send(owner, "POST", "/api/session", null, null);
        assertThat(string(renewed, "$.sessionId")).isEqualTo(owner.id());
        assertThat(string(renewed, "$.csrfToken")).isEqualTo(owner.csrf());
        String hash = jdbc.queryForObject("SELECT secret_hash FROM anonymous_sessions WHERE id=?", String.class, owner.id());
        assertThat(hash).matches("[a-f0-9]{64}");
        jdbc.update("UPDATE anonymous_sessions SET expires_at=? WHERE id=?",
                LocalDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), owner.id());
        var expired = send(owner, "GET", "/api/projects", null, null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(string(expired, "$.code")).isEqualTo("SESSION_EXPIRED");
        Visitor replacement = visitorFrom(send(owner, "POST", "/api/session", null, null));
        assertThat(replacement.id()).isNotEqualTo(owner.id());
        assertThat(send(replacement, "GET", "/api/projects/" + projectId, null, null).statusCode()).isEqualTo(404);
    }

    @Test void paginationIsBoundedAndOnlyContainsOwnedProjects() throws Exception {
        Visitor owner = visitor();
        for (int i = 0; i < 3; i++) assertThat(send(owner, "POST", "/api/projects", CREATE, key()).statusCode()).isEqualTo(201);
        var first = send(owner, "GET", "/api/projects?page=0&pageSize=2", null, null);
        var second = send(owner, "GET", "/api/projects?page=1&pageSize=2", null, null);
        assertThat(number(first, "$.total")).isEqualTo(3);
        assertThat(JsonPath.<List<Object>>read(first.body(), "$.items")).hasSize(2);
        assertThat(JsonPath.<List<Object>>read(second.body(), "$.items")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(first.body(), "$.items[*].id"))
                .doesNotContainAnyElementsOf(JsonPath.read(second.body(), "$.items[*].id"));
    }

    private Visitor visitor() throws Exception { return visitorFrom(send(null, "POST", "/api/session", null, null)); }
    private Visitor visitorFrom(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        String header = response.headers().firstValue("set-cookie").orElseThrow();
        assertThat(header.contains("HttpOnly") && header.contains("SameSite=Strict") && !header.contains("Domain=")).isTrue();
        return new Visitor(string(response, "$.sessionId"), header.split(";", 2)[0], string(response, "$.csrfToken"));
    }
    private HttpResponse<String> send(Visitor visitor, String method, String path, String body, String key) throws Exception {
        return client.send(request(visitor, method, path, body, key).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(Visitor visitor, String method, String path, String body, String key) {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(12)).header("Origin", ORIGIN);
        if (visitor != null) request.header("Cookie", visitor.cookie()).header("X-CSRF-Token", visitor.csrf());
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String string(HttpResponse<String> response, String path) { return JsonPath.read(response.body(), path); }
    private static long number(HttpResponse<String> response, String path) { return JsonPath.<Number>read(response.body(), path).longValue(); }
}
