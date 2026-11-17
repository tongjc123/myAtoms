package com.deepwistom.atoms.version;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VersionRepository {
    private final JdbcTemplate jdbc;

    public VersionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record FileInfo(String path, long bytes, long declaredBytes) {}
    public record SourceFile(String path, byte[] content) {}

    public List<VersionModels.Version> list(String projectId, int page, int size) {
        return jdbc.query("""
                SELECT id,version_no,kind,summary,created_at,parent_version_id,restored_from_version_id,entry_path
                FROM project_versions WHERE project_id=? ORDER BY version_no DESC LIMIT ? OFFSET ?
                """, (row, index) -> new VersionModels.Version(row.getString("id"), row.getInt("version_no"),
                row.getString("kind"), row.getString("summary"), DatabaseTime.read(row, "created_at"),
                row.getString("parent_version_id"), row.getString("restored_from_version_id"),
                row.getString("entry_path")), projectId, size, (long) page * size);
    }

    public long count(String projectId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project_versions WHERE project_id=?", Long.class, projectId);
    }

    /** Inspect metadata without bringing potentially large video blobs into the application. */
    public List<FileInfo> files(String versionId) {
        return jdbc.query("""
                SELECT path,OCTET_LENGTH(content),size_bytes FROM project_files WHERE version_id=? ORDER BY path
                """, (row, index) -> new FileInfo(row.getString(1), row.getLong(2), row.getLong(3)), versionId);
    }

    public List<SourceFile> sourceFiles(String versionId) {
        return jdbc.query("SELECT path,content FROM project_files WHERE version_id=? ORDER BY path",
                (row, index) -> new SourceFile(row.getString(1), row.getBytes(2)), versionId);
    }

    public void restore(String id, String projectId, int number, String parent, String source, Instant now) {
        jdbc.update("""
                INSERT INTO project_versions
                (id,project_id,version_no,kind,parent_version_id,restored_from_version_id,
                 template_version_id,summary,content_hash,entry_path,created_at)
                SELECT ?,?,?, 'RESTORE',?,id,template_version_id,?,content_hash,entry_path,?
                FROM project_versions WHERE id=? AND project_id=?
                """, id, projectId, number, parent, "恢复历史版本", DatabaseTime.write(now), source, projectId);
        jdbc.update("""
                INSERT INTO project_files (version_id,path,content_type,size_bytes,content)
                SELECT ?,path,content_type,size_bytes,content FROM project_files WHERE version_id=?
                """, id, source);
    }
}
