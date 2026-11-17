package com.deepwistom.atoms.run;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {
    private final JdbcTemplate jdbc;
    public RunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(String id, String projectId, String purpose, String status, String engine,
                      String theme, Integer slideCount, int totalSteps, boolean cancelRequested,
                      String errorCode, String errorMessage, String modelKey, String specJson,
                      String providerTaskId, String instruction, String baseVersionId, String requestPayload,
                      Instant createdAt, Instant startedAt,
                      Instant finishedAt) {}

    private static final String SELECT = "SELECT r.* FROM runs r JOIN projects p ON p.id=r.project_id ";

    public void insert(String id, String projectId, String owner, String purpose, String engine,
                       String prompt, String templateSlug, String importId, String theme, int totalSteps,
                       Instant now) {
        insert(id, projectId, owner, purpose, engine, prompt, templateSlug, importId, theme, null, null,
                totalSteps, now);
    }

    public void insert(String id, String projectId, String owner, String purpose, String engine,
                       String prompt, String templateSlug, String importId, String theme,
                       String modelKey, String specJson, int totalSteps, Instant now) {
        jdbc.update("""
                INSERT INTO runs (id,project_id,owner_session_id,purpose,status,engine,prompt,template_slug,
                                  import_id,theme,model_key,spec_json,total_steps,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, projectId, owner, purpose, "QUEUED", engine, prompt, templateSlug, importId, theme,
                modelKey, specJson, totalSteps, DatabaseTime.write(now));
    }

    public Optional<Row> find(String owner, String projectId, String runId) {
        return jdbc.query(SELECT + """
                WHERE r.id=? AND r.project_id=? AND p.owner_session_id=? AND p.deleted_at IS NULL
                """, this::map, runId, projectId, owner).stream().findFirst();
    }

    public Optional<Row> latest(String owner, String projectId) {
        return jdbc.query(SELECT + """
                WHERE r.project_id=? AND p.owner_session_id=? AND p.deleted_at IS NULL
                ORDER BY r.created_at DESC, r.id DESC LIMIT 1
                """, this::map, projectId, owner).stream().findFirst();
    }

    public Optional<Row> byRequestKey(String owner, String projectId, String key) {
        return jdbc.query(SELECT + " WHERE r.project_id=? AND p.owner_session_id=? AND r.request_key=?",
                this::map, projectId, owner, key).stream().findFirst();
    }

    public boolean active(String projectId) {
        return !jdbc.queryForList("""
                SELECT id FROM runs WHERE project_id=? AND status IN ('QUEUED','RUNNING') LIMIT 1
                """, String.class, projectId).isEmpty();
    }

    public void bindContext(String id, String instruction, String base, String key, String payload) {
        jdbc.update("UPDATE runs SET instruction=?,base_version_id=?,request_key=?,request_payload=? WHERE id=?",
                instruction, base, key, payload, id);
    }

    public void markRunning(String id, Instant now) {
        jdbc.update("UPDATE runs SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'",
                DatabaseTime.write(now), id);
    }

    public void setProviderTask(String id, String taskId) {
        jdbc.update("UPDATE runs SET provider_task_id=? WHERE id=?", taskId, id);
    }

    public void finish(String id, String status, Integer slideCount, String errorCode, String errorMessage,
                       Instant now) {
        jdbc.update("""
                UPDATE runs SET status=?,slide_count=?,error_code=?,error_message=?,finished_at=?
                WHERE id=? AND status IN ('QUEUED','RUNNING')
                """, status, slideCount, errorCode, errorMessage, DatabaseTime.write(now), id);
    }

    public void requestCancel(String id) {
        jdbc.update("UPDATE runs SET cancel_requested=1 WHERE id=? AND status IN ('QUEUED','RUNNING')", id);
    }

    public boolean cancelRequested(String id) {
        Boolean value = jdbc.queryForObject("SELECT cancel_requested FROM runs WHERE id=?", Boolean.class, id);
        return Boolean.TRUE.equals(value);
    }

    private Row map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        Object slideCount = row.getObject("slide_count");
        return new Row(row.getString("id"), row.getString("project_id"), row.getString("purpose"),
                row.getString("status"), row.getString("engine"), row.getString("theme"),
                slideCount == null ? null : ((Number) slideCount).intValue(), row.getInt("total_steps"),
                row.getBoolean("cancel_requested"), row.getString("error_code"), row.getString("error_message"),
                row.getString("model_key"), row.getString("spec_json"), row.getString("provider_task_id"),
                row.getString("instruction"), row.getString("base_version_id"), row.getString("request_payload"),
                DatabaseTime.read(row, "created_at"),
                row.getObject("started_at") == null ? null : DatabaseTime.read(row, "started_at"),
                row.getObject("finished_at") == null ? null : DatabaseTime.read(row, "finished_at"));
    }
}
