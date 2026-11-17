package com.deepwistom.atoms.project;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable project versions. A version is never updated after creation; new work creates a new row. */
@Repository
public class ProjectVersionRepository {
    private final JdbcTemplate jdbc;
    public ProjectVersionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record VersionRow(String id, int versionNo, String kind, String entryPath, String contentHash,
                             String summary, Instant createdAt) {}

    public record FileContent(String contentType, byte[] content) {}

    public int nextVersionNo(String projectId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM project_versions WHERE project_id=?", Integer.class, projectId);
        return (max == null ? 0 : max) + 1;
    }

    public void insert(String id, String projectId, int versionNo, String kind, String templateVersionId,
                       String summary, String contentHash, String entryPath, Instant now) {
        jdbc.update("""
                INSERT INTO project_versions
                (id,project_id,version_no,kind,parent_version_id,template_version_id,summary,content_hash,entry_path,created_at)
                VALUES (?,?,?,?,NULL,?,?,?,?,?)
                """, id, projectId, versionNo, kind, templateVersionId, summary, contentHash, entryPath,
                DatabaseTime.write(now));
    }

    /** Copies the template snapshot into the project so later template edits cannot leak into it. */
    public int copyFromTemplate(String projectVersionId, String templateVersionId) {
        return jdbc.update("""
                INSERT INTO project_files (version_id,path,content_type,size_bytes,content)
                SELECT ?,path,content_type,size_bytes,content FROM template_files WHERE version_id=?
                """, projectVersionId, templateVersionId);
    }

    public void setCurrentVersion(String projectId, String versionId, Instant now) {
        jdbc.update("UPDATE projects SET current_version_id=?,updated_at=? WHERE id=?",
                versionId, DatabaseTime.write(now), projectId);
    }

    public void setSourceTemplateVersion(String projectId, String templateVersionId) {
        jdbc.update("UPDATE projects SET source_template_version_id=? WHERE id=?", templateVersionId, projectId);
    }

    public Optional<FileContent> file(String versionId, String path) {
        return jdbc.query("SELECT content_type,content FROM project_files WHERE version_id=? AND path=?",
                (row, number) -> new FileContent(row.getString(1), row.getBytes(2)), versionId, path)
                .stream().findFirst();
    }

    public Optional<VersionRow> find(String projectId, String versionId) {
        return jdbc.query("""
                SELECT id,version_no,kind,entry_path,content_hash,summary,created_at
                FROM project_versions WHERE project_id=? AND id=?
                """, (row, number) -> new VersionRow(row.getString(1), row.getInt(2), row.getString(3),
                row.getString(4), row.getString(5), row.getString(6), DatabaseTime.read(row, "created_at")),
                projectId, versionId).stream().findFirst();
    }
}
