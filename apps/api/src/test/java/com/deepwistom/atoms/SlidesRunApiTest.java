package com.deepwistom.atoms;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slides pipeline is a real, inspectable run: every step is written to the event log before the
 * stream replays it, cancelling leaves no published version behind, and imports are validated by
 * extension and content before they can influence a deck.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"atoms.provider.api-key=sentinel-must-never-be-exposed", "atoms.provider.video-model=", "atoms.qwen.text-model="})
@ActiveProfiles("integration")
class SlidesRunApiTest {
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String PROMPT = "为「创意破界」文化节做一套提案。活动在 2026 年 4 月 18 日开幕，"
            + "地点是上海西岸穹顶艺术中心。希望用橄榄绿与白色作为主色，突出主论坛、现场工作坊与夜间演出三大板块。"
            + "预计参与人数 3000 人，合作品牌 12 家。";

    @LocalServerPort
    private int port;

    record Session(String cookie, String csrf) {}

    @Test
    void runPublishesAVersionAndKeepsAnInspectableEventLog() throws Exception {
        var session = session();
        String projectId = createSlidesProject(session, "创意破界文化节提案");

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");
        assertThat(JsonPath.<String>read(started.body(), "$.status")).isEqualTo("QUEUED");

        var snapshot = awaitTerminal(session, projectId, runId, "SUCCEEDED");
        assertThat(JsonPath.<String>read(snapshot, "$.run.engine")).isEqualTo("atoms-slide-layout/1.0");
        assertThat(JsonPath.<Integer>read(snapshot, "$.run.slideCount")).isGreaterThanOrEqualTo(5);
        assertThat(JsonPath.<String>read(snapshot, "$.run.finishedAt")).isNotBlank();

        List<Map<String, Object>> events = JsonPath.read(snapshot, "$.events");
        assertThat(events).isNotEmpty();
        assertThat(events).extracting(event -> (Integer) event.get("seq")).isSorted();
        assertThat(events).extracting(event -> (String) event.get("type"))
                .contains("status", "step", "progress", "artifact");

        // The published version holds a self-contained deck rendered by the shared engine.
        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<String>read(project.body(), "$.project.currentVersion.kind")).isEqualTo("GENERATED");
        assertThat(JsonPath.<Integer>read(project.body(), "$.project.currentVersion.number")).isEqualTo(1);

        var preview = post("/api/projects/" + projectId + "/preview-sessions", "{}", session.cookie(), session.csrf(), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        String page = get(JsonPath.<String>read(preview.body(), "$.baseUrl")
                + JsonPath.<String>read(preview.body(), "$.entryPath"), null).body();
        assertThat(page).contains("<!doctype html>", "创意破界文化节提案", "data-len=");
        assertThat(page).doesNotContain("http://", "https://cdn", "<link rel=\"stylesheet\"");
    }

    @Test
    void cancellingStopsTheRunWithoutPublishingAnything() throws Exception {
        var session = session();
        String projectId = createSlidesProject(session, "取消验证");

        var started = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).isEqualTo(202);
        String runId = JsonPath.read(started.body(), "$.runId");

        var cancelled = post("/api/projects/" + projectId + "/runs/" + runId + "/cancel", "{}",
                session.cookie(), session.csrf(), null);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(cancelled.body(), "$.status")).isIn("QUEUED", "RUNNING", "CANCELLED");

        var snapshot = awaitTerminal(session, projectId, runId, "CANCELLED");
        // A stopped run must not claim a deck: no artifact event, no slide count, no version.
        assertThat(JsonPath.<List<Map<String, Object>>>read(snapshot, "$.events"))
                .extracting(event -> (String) event.get("type")).doesNotContain("artifact");
        assertThat(JsonPath.<Object>read(snapshot, "$.run.slideCount")).isIn(null, 0);

        var project = get("/api/projects/" + projectId, session.cookie());
        assertThat(JsonPath.<Object>read(project.body(), "$.project.currentVersion")).isNull();

        // A cancelled run must not block the project from being retried.
        var retry = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(retry.statusCode()).isEqualTo(202);
    }

    @Test
    void runsRequireConfiguredGameProviderAndRejectOverlappingRequests() throws Exception {
        var session = session();
        var cloned = post("/api/templates/star-swarm/clone", "{\"name\":\"游戏项目\"}",
                session.cookie(), session.csrf(), UUID.randomUUID().toString());
        String gameId = JsonPath.read(cloned.body(), "$.project.id");
        var unsupported = post("/api/projects/" + gameId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(unsupported.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(unsupported.body(), "$.code")).isEqualTo("PROVIDER_NOT_CONFIGURED");

        String projectId = createSlidesProject(session, "并发验证");
        var first = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(first.statusCode()).isEqualTo(202);
        var second = post("/api/projects/" + projectId + "/runs", "{}", session.cookie(), session.csrf(), null);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(second.body(), "$.code")).isEqualTo("RUN_IN_PROGRESS");

        var unknownTemplate = post("/api/projects/" + projectId + "/runs",
                "{\"templateSlug\":\"not-a-real-template\"}", session.cookie(), session.csrf(), null);
        assertThat(unknownTemplate.statusCode()).isIn(404, 409);

        String runId = JsonPath.read(first.body(), "$.runId");
        post("/api/projects/" + projectId + "/runs/" + runId + "/cancel", "{}", session.cookie(), session.csrf(), null);
        awaitTerminal(session, projectId, runId, "CANCELLED");

        // Another visitor cannot observe or drive the run.
        var stranger = session();
        assertThat(get("/api/projects/" + projectId + "/runs/latest", stranger.cookie()).statusCode()).isEqualTo(404);
        assertThat(post("/api/projects/" + projectId + "/runs/" + runId + "/cancel", "{}",
                stranger.cookie(), stranger.csrf(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void importsAcceptReadablePptxAndRejectUnsupportedFiles() throws Exception {
        var session = session();

        var rejected = upload(session, "outline.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(rejected.body(), "$.code")).isEqualTo("VALIDATION_ERROR");

        var anonymous = upload(new Session(null, null), "outline.pptx", "application/octet-stream", pptx("概览", "要点"));
        assertThat(anonymous.statusCode()).isEqualTo(401);

        var imported = upload(session, "年度复盘.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx("年度经营复盘", "营收增长 18%", "三条业务线全部达成目标"));
        assertThat(imported.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(imported.body(), "$.parseState")).isEqualTo("PARSED");
        assertThat(JsonPath.<Integer>read(imported.body(), "$.slideCount")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(imported.body(), "$.outlineTitles"))
                .containsExactly("年度经营复盘");
        String importId = JsonPath.read(imported.body(), "$.id");

        var listed = get("/api/templates/imports", session.cookie());
        assertThat(JsonPath.<List<Map<String, Object>>>read(listed.body(), "$.items"))
                .anySatisfy(item -> assertThat(item.get("id")).isEqualTo(importId));

        // A stored import can drive a run, and its outline becomes the deck's backbone.
        String projectId = createSlidesProject(session, "导入大纲演示");
        var started = post("/api/projects/" + projectId + "/runs", "{\"importId\":\"" + importId + "\"}",
                session.cookie(), session.csrf(), null);
        assertThat(started.statusCode()).isEqualTo(202);
        String snapshot = awaitTerminal(session, projectId, JsonPath.read(started.body(), "$.runId"), "SUCCEEDED");
        assertThat(JsonPath.<Integer>read(snapshot, "$.run.slideCount")).isGreaterThanOrEqualTo(2);
    }

    private String createSlidesProject(Session session, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"type\":\"SLIDES\",\"mode\":\"BUILD\",\"prompt\":\""
                + PROMPT + "\"}";
        var created = post("/api/projects", body, session.cookie(), session.csrf(), UUID.randomUUID().toString());
        assertThat(created.statusCode()).isEqualTo(201);
        String projectId = JsonPath.read(created.body(), "$.project.id");
        assertThat(JsonPath.<String>read(created.body(), "$.project.type")).isEqualTo("SLIDES");
        return projectId;
    }

    private String awaitTerminal(Session session, String projectId, String runId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
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

    /** Builds a minimal but genuine .pptx package with one slide and the given text runs. */
    private static byte[] pptx(String... runs) throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><p:cSld><p:spTree>");
        for (String run : runs) xml.append("<a:t>").append(run).append("</a:t>");
        xml.append("</p:spTree></p:cSld></p:sld>");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ppt/slides/slide1.xml"));
            zip.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private HttpResponse<String> upload(Session session, String filename, String contentType, byte[] content)
            throws Exception {
        String boundary = "----atoms" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        var builder = requestBuilder("/api/templates/imports")
                .header("Origin", ORIGIN)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (session.cookie() != null) builder.header("Cookie", session.cookie());
        if (session.csrf() != null) builder.header("X-CSRF-Token", session.csrf());
        return client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
