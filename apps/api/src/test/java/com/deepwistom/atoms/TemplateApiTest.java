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
 * Templates are a public read-only catalogue, while cloning is an authenticated mutation that must
 * produce an independent file snapshot. Preview access is granted by an opaque token, never the
 * owner cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"atoms.provider.api-key=sentinel-must-never-be-exposed", "atoms.provider.video-model="})
@ActiveProfiles("integration")
class TemplateApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";

    @LocalServerPort
    private int port;

    record Session(String cookie, String csrf) {}

    @Test
    void templatesListWithoutASessionAndExposePlayableBundles() throws Exception {
        var response = get("/api/templates?type=GAME", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JsonPath.parse(response.body());
        List<Map<String, Object>> items = json.read("$.items");
        assertThat(items).hasSizeGreaterThanOrEqualTo(4);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("type")).isEqualTo("GAME");
            assertThat(item.get("playable")).isEqualTo(true);
            assertThat((String) item.get("playUrl")).startsWith("/api/template-previews/");
            assertThat((String) item.get("coverUrl")).startsWith("/api/template-previews/");
            assertThat((Integer) item.get("versionNo")).isGreaterThanOrEqualTo(1);
        });
        assertThat(response.body()).doesNotContain("Bearer", "sentinel", "password", "secret");

        String playUrl = (String) items.get(0).get("playUrl");
        var bundle = get(playUrl, null);
        assertThat(bundle.statusCode()).isEqualTo(200);
        assertThat(bundle.headers().firstValue("content-type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("text/html"));
        assertThat(bundle.headers().firstValue("content-security-policy")).hasValueSatisfying(
                value -> assertThat(value).contains("frame-ancestors 'self'", "default-src 'none'"));
        assertThat(bundle.body()).contains("<!doctype html>");
    }

    @Test
    void templatePreviewRejectsPathTraversalAndUnknownSlugs() throws Exception {
        assertThat(get("/api/template-previews/star-swarm/pom.xml", null).statusCode()).isEqualTo(404);
        assertThat(get("/api/template-previews/not-a-template/index.html", null).statusCode()).isEqualTo(404);
        assertThat(get("/api/template-previews/star-swarm/..%2f..%2fsecret.txt", null).statusCode())
                .isIn(400, 404);
    }

    @Test
    void cloningRequiresSessionOriginAndCsrfToken() throws Exception {
        var anonymous = post("/api/templates/star-swarm/clone", "{\"name\":\"x\"}", null, null, UUID.randomUUID().toString());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        var session = session();
        var withoutCsrf = post("/api/templates/star-swarm/clone", "{\"name\":\"x\"}", session.cookie(), null,
                UUID.randomUUID().toString());
        assertThat(withoutCsrf.statusCode()).isEqualTo(403);

        var withoutOrigin = requestBuilder("/api/templates/star-swarm/clone")
                .header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"));
        assertThat(client().send(withoutOrigin.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void cloneCreatesAnIndependentVersionAndAnIsolatedPreview() throws Exception {
        var session = session();
        String key = UUID.randomUUID().toString();
        var cloned = post("/api/templates/star-swarm/clone", "{\"name\":\"克隆测试\"}", session.cookie(), session.csrf(), key);
        assertThat(cloned.statusCode()).isEqualTo(201);
        var json = JsonPath.parse(cloned.body());
        String projectId = json.read("$.project.id");
        assertThat(json.<String>read("$.project.status")).isEqualTo("READY");
        assertThat(json.<String>read("$.project.sourceTemplate.slug")).isEqualTo("star-swarm");
        assertThat(json.<String>read("$.project.currentVersion.kind")).isEqualTo("TEMPLATE");
        assertThat(json.<Integer>read("$.project.currentVersion.number")).isEqualTo(1);

        // Same idempotency key must return the same project instead of cloning twice.
        var replay = post("/api/templates/star-swarm/clone", "{\"name\":\"克隆测试\"}", session.cookie(), session.csrf(), key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(replay.body(), "$.project.id")).isEqualTo(projectId);

        var preview = post("/api/projects/" + projectId + "/preview-sessions", "{}", session.cookie(), session.csrf(), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        String baseUrl = JsonPath.read(preview.body(), "$.baseUrl");
        String entryPath = JsonPath.read(preview.body(), "$.entryPath");
        assertThat(baseUrl).matches("/api/previews/[A-Za-z0-9_-]{40,}/");
        assertThat(entryPath).isEqualTo("index.html");

        var page = get(baseUrl + entryPath, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("cache-control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(page.body()).contains("<!doctype html>");

        // A different anonymous visitor cannot read the project or mint a preview for it.
        var stranger = session();
        assertThat(get("/api/projects/" + projectId, stranger.cookie()).statusCode()).isEqualTo(404);
        assertThat(post("/api/projects/" + projectId + "/preview-sessions", "{}", stranger.cookie(), stranger.csrf(), null)
                .statusCode()).isEqualTo(404);
        assertThat(get(baseUrl + entryPath, stranger.cookie()).statusCode()).isEqualTo(200);
    }

    /**
     * The WEB bundle is a runnable single-file page, so a clone must serve the very same HTML and
     * the project must be typed WEB — the gallery offers it as a real starting point, not a mock.
     */
    @Test
    void webTemplatesArePlayableAndCloneIntoARunnableProject() throws Exception {
        var response = get("/api/templates?type=WEB", null);
        assertThat(response.statusCode()).isEqualTo(200);
        List<Map<String, Object>> items = JsonPath.parse(response.body()).read("$.items");
        assertThat(items).hasSizeGreaterThanOrEqualTo(4);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("type")).isEqualTo("WEB");
            assertThat(item.get("playable")).isEqualTo(true);
            assertThat((String) item.get("playUrl")).endsWith("/index.html");
        });

        for (Map<String, Object> item : items) {
            var bundle = get((String) item.get("playUrl"), null);
            assertThat(bundle.statusCode()).isEqualTo(200);
            assertThat(bundle.headers().firstValue("content-type")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("text/html"));
            assertThat(bundle.body()).contains("<!doctype html>");
        }

        String slug = (String) items.get(0).get("slug");
        var session = session();
        var cloned = post("/api/templates/" + slug + "/clone", "{\"name\":\"网页模板克隆\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(cloned.statusCode()).isEqualTo(201);
        var json = JsonPath.parse(cloned.body());
        String projectId = json.read("$.project.id");
        assertThat(json.<String>read("$.project.type")).isEqualTo("WEB");
        assertThat(json.<String>read("$.project.sourceTemplate.slug")).isEqualTo(slug);
        assertThat(json.<String>read("$.project.currentVersion.kind")).isEqualTo("TEMPLATE");

        var preview = post("/api/projects/" + projectId + "/preview-sessions", "{}",
                session.cookie(), session.csrf(), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        String page = get(JsonPath.<String>read(preview.body(), "$.baseUrl")
                + JsonPath.<String>read(preview.body(), "$.entryPath"), null).body();
        assertThat(page).isEqualTo(get("/api/template-previews/" + slug + "/index.html", null).body());
    }

    private Session session() throws Exception {
        var request = requestBuilder("/api/session")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        var response = client().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow()
                .split(";", 2)[0];
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
