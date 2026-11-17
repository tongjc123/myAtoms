package com.deepwistom.atoms.preview;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PreviewRepository {
    private final JdbcTemplate jdbc;
    public PreviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Access(String projectId, String versionId, String entryPath, String projectName) {}

    public void insert(String tokenHash, String projectId, String versionId, String ownerSessionId,
                       Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO preview_sessions
                (token_hash,project_id,version_id,owner_session_id,created_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, tokenHash, projectId, versionId, ownerSessionId,
                DatabaseTime.write(createdAt), DatabaseTime.write(expiresAt));
    }

    public Optional<Access> resolve(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT s.project_id, s.version_id, v.entry_path, p.name
                FROM preview_sessions s
                JOIN project_versions v ON v.id=s.version_id
                JOIN projects p ON p.id=s.project_id
                WHERE s.token_hash=? AND s.expires_at>? AND p.deleted_at IS NULL
                """, (row, number) -> new Access(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                tokenHash, DatabaseTime.write(now)).stream().findFirst();
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM preview_sessions WHERE expires_at<=?", DatabaseTime.write(now));
    }
}
