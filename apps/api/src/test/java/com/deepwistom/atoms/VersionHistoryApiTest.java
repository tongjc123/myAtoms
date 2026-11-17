package com.deepwistom.atoms;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.project.ProjectRepository;
import com.deepwistom.atoms.run.RunService;
import com.deepwistom.atoms.version.VersionModels.RestoreRequest;
import com.deepwistom.atoms.version.VersionService;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** Real MySQL transactions and HTTP boundaries; fixtures never invoke a provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class VersionHistoryApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired VersionService versions;
    @Autowired RunService runs;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean ProjectRepository projects;
    private final List<String> projectIds = new ArrayList<>();
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Session(String id, String cookie, String csrf) {}
    record Fixture(Session owner, String project, String first, String second) {}

    @AfterEach
    void cleanup() {
        for (String project : projectIds) {
            jdbc.update("DELETE FROM preview_sessions WHERE project_id=?", project);
            jdbc.update("DELETE FROM idempotency_requests WHERE project_id=?", project);
            jdbc.update("DELETE FROM runs WHERE project_id=?", project);
            jdbc.update("UPDATE projects SET current_version_id=NULL WHERE id=?", project);
            jdbc.update("""
                    DELETE f FROM project_files f JOIN project_versions v ON v.id=f.version_id WHERE v.project_id=?
                    """, project);
            // Self-referencing restore lineage requires deleting children before their sources.
            var ids = jdbc.queryForList(
                    "SELECT id FROM project_versions WHERE project_id=? ORDER BY version_no DESC", String.class, project);
            for (String id : ids) jdbc.update("DELETE FROM project_versions WHERE id=?", id);
            jdbc.update("DELETE FROM projects WHERE id=?", project);
        }
        for (String owner : owners) jdbc.update("DELETE FROM anonymous_sessions WHERE id=?", owner);
    }

    @Test
    void restoresAllTypesWithImmutableBinaryContentLineageAndOriginalReceipt() throws Exception {
        for (String type : List.of("WEB", "GAME", "SLIDES", "VIDEO")) {
            var f = fixture(type);
            byte[] binary = new byte[] {0, -1, 3, 0, 42, -128, 127};
            file(f.first(), type.equals("VIDEO") ? "media/clip.mp4" : "assets/data.png", binary);
            var oldFiles = fingerprints(f.first());
            String oldHash = jdbc.queryForObject(
                    "SELECT content_hash FROM project_versions WHERE id=?", String.class, f.first());
            String key = uuid();
            var restored = restore(f, f.first(), f.second(), key);
            assertThat(restored.statusCode()).isEqualTo(201);
            String current = value(restored.body(), "$.project.currentVersion.id");
            assertThat(current).isNotIn(f.first(), f.second());
            assertThat(JsonPath.<Integer>read(restored.body(), "$.project.currentVersion.number")).isEqualTo(3);
            assertThat(value(restored.body(), "$.project.currentVersion.kind")).isEqualTo("RESTORE");
            assertThat(fingerprints(current)).isEqualTo(oldFiles);
            assertThat(fingerprints(f.first())).isEqualTo(oldFiles);
            assertThat(jdbc.queryForObject("SELECT content_hash FROM project_versions WHERE id=?",
                    String.class, current)).isEqualTo(oldHash);
            var history = get(base(f) + "?pageSize=2", f.owner());
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(value(history.body(), "$.currentVersionId")).isEqualTo(current);
            assertThat(value(history.body(), "$.items[0].parentVersionId")).isEqualTo(f.second());
            assertThat(value(history.body(), "$.items[0].restoredFromVersionId")).isEqualTo(f.first());
            assertThat(JsonPath.<List<Integer>>read(history.body(), "$.items[*].number")).containsExactly(3, 2);
            assertThat(JsonPath.<Integer>read(history.body(), "$.total")).isEqualTo(3);
            var secondPage = get(base(f) + "?page=1&pageSize=2", f.owner());
            assertThat(JsonPath.<List<Integer>>read(secondPage.body(), "$.items[*].number")).containsExactly(1);
            assertThat(restore(f, f.first(), f.second(), key).body()).isEqualTo(restored.body());
            error(restore(f, f.second(), current, key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(count(f.project())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE project_id=?", Integer.class, f.project()))
                    .isZero();
        }
    }

    @Test
    void ownershipDeletedAndForeignVersionsAreHiddenOnEveryRoute() throws Exception {
        var f = fixture("GAME");
        var foreign = fixture("WEB");
        for (String path : List.of(base(f), base(f) + "/" + f.first() + "/source.zip")) {
            assertThat(get(path, foreign.owner()).statusCode()).isEqualTo(404);
        }
        assertThat(post(base(f) + "/" + f.first() + "/restore", expected(f.second()), foreign.owner(), uuid())
                .statusCode()).isEqualTo(404);
        assertThat(preview(f, foreign.first()).statusCode()).isEqualTo(404);
        assertThat(restore(f, foreign.first(), f.second(), uuid()).statusCode()).isEqualTo(404);
        assertThat(get(base(f) + "/" + foreign.first() + "/source.zip", f.owner()).statusCode()).isEqualTo(404);
        assertThat(post("/api/projects/" + f.project() + "/preview-sessions",
                "{\"versionId\":\"" + f.first() + "\"}", foreign.owner(), null).statusCode()).isEqualTo(404);
        assertThat(get(base(f) + "/" + uuid() + "/source.zip", f.owner()).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE projects SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", f.project());
        assertThat(get(base(f), f.owner()).statusCode()).isEqualTo(404);
        assertThat(get(base(f) + "/" + f.first() + "/source.zip", f.owner()).statusCode()).isEqualTo(404);
        assertThat(restore(f, f.first(), f.second(), uuid()).statusCode()).isEqualTo(404);
        assertThat(preview(f, f.first()).statusCode()).isEqualTo(404);
    }

    @Test
    void validatesRequestsBusyProjectsAndMissingOrCorruptContentsWithoutPartialVersions() throws Exception {
        var f = fixture("GAME");
        error(restore(f, f.first(), f.first(), uuid()), 409, "VERSION_CONFLICT");
        error(restore(f, f.second(), f.second(), uuid()), 409, "VERSION_ALREADY_CURRENT");
        error(post(base(f) + "/" + f.first() + "/restore", "{}", f.owner(), uuid()), 400, "VALIDATION_ERROR");
        error(restore(f, f.first(), f.second(), null), 400, "VALIDATION_ERROR");
        assertThat(get(base(f) + "?page=-1", f.owner()).statusCode()).isEqualTo(400);
        assertThat(get(base(f) + "?pageSize=51", f.owner()).statusCode()).isEqualTo(400);
        for (String state : List.of("QUEUED", "RUNNING")) {
            run(f, state);
            error(restore(f, f.first(), f.second(), uuid()), 409, "PROJECT_BUSY");
            jdbc.update("DELETE FROM runs WHERE project_id=?", f.project());
        }
        jdbc.update("DELETE FROM project_files WHERE version_id=?", f.first());
        error(restore(f, f.first(), f.second(), uuid()), 409, "VERSION_CONTENT_UNAVAILABLE");
        file(f.first(), "other.txt", new byte[] {1});
        error(restore(f, f.first(), f.second(), uuid()), 409, "VERSION_CONTENT_UNAVAILABLE");
        file(f.first(), "index.html", new byte[] {1});
        jdbc.update("UPDATE project_files SET size_bytes=100 WHERE version_id=? AND path='index.html'", f.first());
        error(restore(f, f.first(), f.second(), uuid()), 409, "VERSION_CONTENT_UNAVAILABLE");
        assertThat(count(f.project())).isEqualTo(2);
        assertThat(current(f.project())).isEqualTo(f.second());
    }

    @Test
    void previewTokensStayBoundToSelectedVersionAcrossRestorationAndDefaultBodyStillWorks() throws Exception {
        var f = fixture("VIDEO");
        file(f.first(), "movie.mp4", new byte[] {0, 1, -1, 0, 55});
        var historical = preview(f, f.first());
        assertThat(historical.statusCode()).isEqualTo(200);
        String url = value(historical.body(), "$.baseUrl");
        assertThat(get(url + "index.html", null).body()).isEqualTo("first VIDEO");
        assertThat(current(f.project())).isEqualTo(f.second());
        var latest = post("/api/projects/" + f.project() + "/preview-sessions", "", f.owner(), null);
        assertThat(latest.statusCode()).isEqualTo(200);
        assertThat(get(value(latest.body(), "$.baseUrl") + "index.html", null).body()).isEqualTo("second VIDEO");
        assertThat(restore(f, f.first(), f.second(), uuid()).statusCode()).isEqualTo(201);
        assertThat(get(url + "index.html", null).body()).isEqualTo("first VIDEO");
        assertThat(bytes(url + "movie.mp4", null).body()).containsExactly(0, 1, -1, 0, 55);
        var defaultPreview = preview(f, null);
        assertThat(get(value(defaultPreview.body(), "$.baseUrl") + "index.html", null).body()).isEqualTo("first VIDEO");
    }

    @Test
    void zipContainsEveryExactStoredFileForWebAndGameAndRejectsMediaTypes() throws Exception {
        for (String type : List.of("WEB", "GAME")) {
            var f = fixture(type);
            byte[] binary = new byte[] {0, -1, 5, 9};
            file(f.first(), "assets/icon.png", binary);
            var response = bytes(base(f) + "/" + f.first() + "/source.zip", f.owner());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type")).hasValue("application/zip");
            assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
            assertThat(response.headers().firstValue("content-disposition"))
                    .hasValue("attachment; filename=\"source-" + f.first() + ".zip\"");
            Map<String, byte[]> entries = new HashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    assertThat(entry.isDirectory()).isFalse();
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            assertThat(entries.keySet()).containsExactlyInAnyOrder("index.html", "assets/icon.png");
            assertThat(entries.get("index.html")).isEqualTo(("first " + type).getBytes(StandardCharsets.UTF_8));
            assertThat(entries.get("assets/icon.png")).isEqualTo(binary);
            assertThat(current(f.project())).isEqualTo(f.second());
            assertThat(count(f.project())).isEqualTo(2);
        }
        for (String type : List.of("SLIDES", "VIDEO")) {
            var f = fixture(type);
            error(get(base(f) + "/" + f.first() + "/source.zip", f.owner()), 400, "UNSUPPORTED_PROJECT_TYPE");
        }
    }

    @Test
    void zipRejectsUnsafePathsAndBoundsRealBytesAndFileCount() throws Exception {
        var f = fixture("WEB");
        for (String unsafe : List.of("/absolute.txt", "//absolute.txt", "../secret.txt", "a/./file.txt",
                "a//file.txt", "a/", "C:/file.txt", "a\\file.txt")) {
            file(f.first(), unsafe, new byte[] {1});
            error(get(base(f) + "/" + f.first() + "/source.zip", f.owner()), 409, "VERSION_CONTENT_UNAVAILABLE");
            jdbc.update("DELETE FROM project_files WHERE version_id=? AND path=?", f.first(), unsafe);
        }
        for (int i = 0; i < 100; i++) file(f.first(), "file" + i + ".txt", new byte[] {1});
        error(get(base(f) + "/" + f.first() + "/source.zip", f.owner()), 409, "SOURCE_EXPORT_TOO_LARGE");
        jdbc.update("DELETE FROM project_files WHERE version_id=? AND path<>'index.html'", f.first());
        byte[] large = new byte[11 * 1024 * 1024];
        file(f.first(), "large1.txt", large);
        file(f.first(), "large2.txt", large);
        error(get(base(f) + "/" + f.first() + "/source.zip", f.owner()), 409, "SOURCE_EXPORT_TOO_LARGE");
    }

    @Test
    void simultaneousDuplicateRequestsProduceOneVersionAndDifferentKeysConflict() throws Exception {
        for (boolean sameKey : List.of(true, false)) {
            var f = fixture("SLIDES");
            String key = uuid();
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> { start.await(); return restore(f, f.first(), f.second(), key); });
                var b = executor.submit(() -> { start.await(); return restore(f, f.first(), f.second(), sameKey ? key : uuid()); });
                start.countDown();
                var first = a.get(20, TimeUnit.SECONDS);
                var second = b.get(20, TimeUnit.SECONDS);
                if (sameKey) {
                    assertThat(first.statusCode()).isEqualTo(201);
                    assertThat(second.body()).isEqualTo(first.body());
                } else {
                    assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(201, 409);
                    error(first.statusCode() == 409 ? first : second, 409, "VERSION_CONFLICT");
                }
            }
            assertThat(count(f.project())).isEqualTo(3);
        }
    }

    @Test
    void restoreSeesGenerationCommittedWhileWaitingForProjectLock() throws Exception {
        for (boolean advancePointer : List.of(true, false)) {
            var f = fixture("WEB");
            if (!advancePointer) run(f, "FAILED");
            CountDownLatch reachedProjectLock = new CountDownLatch(1);
            doAnswer(invocation -> {
                reachedProjectLock.countDown();
                return invocation.callRealMethod();
            }).when(projects).lock(f.owner().id(), f.project());
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", String.class, f.project());
                    pending.set(executor.submit(() -> {
                        try {
                            versions.restore(f.owner().id(), f.project(), f.first(), uuid(), new RestoreRequest(f.second()));
                            return "unexpected-success";
                        } catch (ApiException error) {
                            return error.code();
                        }
                    }));
                    try {
                        // This spy executes after the real idempotency receipt SELECT, before the
                        // real blocking project SELECT FOR UPDATE. A stale RR snapshot now fails.
                        assertThat(reachedProjectLock.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException error) {
                        throw new IllegalStateException(error);
                    }
                    if (advancePointer) {
                        String generated = version(f.project(), 3, "concurrent generation");
                        jdbc.update("UPDATE projects SET current_version_id=? WHERE id=?", generated, f.project());
                    } else {
                        jdbc.update("UPDATE runs SET status='QUEUED' WHERE project_id=?", f.project());
                    }
                });
                assertThat(pending.get().get(15, TimeUnit.SECONDS))
                        .isEqualTo(advancePointer ? "VERSION_CONFLICT" : "PROJECT_BUSY");
            }
            assertThat(count(f.project())).isEqualTo(advancePointer ? 3 : 2);
        }
    }

    @Test
    void generationStartWaitsForOwnerBeforeTakingProjectLock() throws Exception {
        var f = fixture("SLIDES");
        CountDownLatch reachedProjectLock = new CountDownLatch(1);
        CountDownLatch startEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            reachedProjectLock.countDown();
            return invocation.callRealMethod();
        }).when(projects).lock(f.owner().id(), f.project());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM anonymous_sessions WHERE id=? FOR UPDATE",
                        String.class, f.owner().id());
                pending.set(executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    startEntered.countDown();
                    runs.start(f.owner().id(), f.project(), null, uuid());
                    // Exercise the real start INSERT without dispatching its afterCommit worker.
                    tx.setRollbackOnly();
                })));
                try {
                    assertThat(startEntered.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(reachedProjectLock.await(300, TimeUnit.MILLISECONDS)).isFalse();
                } catch (InterruptedException error) {
                    throw new IllegalStateException(error);
                }
            });
            pending.get().get(15, TimeUnit.SECONDS);
            assertThat(reachedProjectLock.getCount()).isZero();
        }
        assertThat(count(f.project())).isEqualTo(2);
    }

    private Fixture fixture(String type) throws Exception {
        Session owner = session();
        String project = uuid();
        jdbc.update("""
                INSERT INTO projects(id,owner_session_id,name,type,prompt,mode,revision,created_at,updated_at)
                VALUES (?,?,'History test',?,'No provider calls','BUILD',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, project, owner.id(), type);
        projectIds.add(project);
        String first = version(project, 1, "first " + type);
        String second = version(project, 2, "second " + type);
        jdbc.update("UPDATE projects SET current_version_id=? WHERE id=?", second, project);
        return new Fixture(owner, project, first, second);
    }

    private String version(String project, int number, String content) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO project_versions(id,project_id,version_no,kind,summary,content_hash,entry_path,created_at)
                VALUES (?,?,?,'GENERATED',?,SHA2(?,256),'index.html',UTC_TIMESTAMP(6))
                """, id, project, number, content, content);
        file(id, "index.html", content.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    private void file(String version, String path, byte[] content) {
        jdbc.update("""
                INSERT INTO project_files(version_id,path,content_type,size_bytes,content) VALUES (?,?,?,?,?)
                """, version, path, com.deepwistom.atoms.template.MediaTypes.of(path), content.length, content);
    }

    private void run(Fixture f, String state) {
        jdbc.update("""
                INSERT INTO runs(id,project_id,owner_session_id,purpose,status,engine,prompt,created_at)
                VALUES (?,?,?,'WEB',?,'history-test','No provider calls',UTC_TIMESTAMP(6))
                """, uuid(), f.project(), f.owner().id(), state);
    }

    private List<Map<String, Object>> fingerprints(String version) {
        return jdbc.queryForList("""
                SELECT path,content_type,size_bytes,SHA2(content,256) AS hash FROM project_files WHERE version_id=? ORDER BY path
                """, version);
    }

    private int count(String project) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project_versions WHERE project_id=?", Integer.class, project);
    }

    private String current(String project) {
        return jdbc.queryForObject("SELECT current_version_id FROM projects WHERE id=?", String.class, project);
    }

    private HttpResponse<String> restore(Fixture f, String source, String current, String key) throws Exception {
        return post(base(f) + "/" + source + "/restore", expected(current), f.owner(), key);
    }

    private HttpResponse<String> preview(Fixture f, String version) throws Exception {
        return post("/api/projects/" + f.project() + "/preview-sessions",
                version == null ? "{}" : "{\"versionId\":\"" + version + "\"}", f.owner(), null);
    }

    private Session session() throws Exception {
        var response = post("/api/session", "{}", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        String id = value(response.body(), "$.sessionId");
        owners.add(id);
        return new Session(id, response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0],
                value(response.body(), "$.csrfToken"));
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        return client.send(request(path, session).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> bytes(String path, Session session) throws Exception {
        return client.send(request(path, session).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> post(String path, String body, Session session, String key) throws Exception {
        var builder = request(path, session).header("Origin", "http://127.0.0.1:5173")
                .header("Content-Type", "application/json");
        if (session != null) builder.header("X-CSRF-Token", session.csrf());
        if (key != null) builder.header("Idempotency-Key", key);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, Session session) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (session != null) builder.header("Cookie", session.cookie());
        return builder;
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(value(response.body(), "$.code")).isEqualTo(code);
    }

    private static String expected(String version) { return "{\"expectedCurrentVersionId\":\"" + version + "\"}"; }
    private static String base(Fixture f) { return "/api/projects/" + f.project() + "/versions"; }
    private static String value(String body, String path) { return JsonPath.read(body, path); }
    private static String uuid() { return UUID.randomUUID().toString(); }
}
