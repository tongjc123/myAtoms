package com.deepwistom.atoms.session;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;
    public SessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record SessionRow(String id, Instant expiresAt) {}

    public Optional<SessionRow> findBySecretHash(String hash) {
        return jdbc.query("SELECT id, expires_at FROM anonymous_sessions WHERE secret_hash=?",
                (row, number) -> new SessionRow(row.getString("id"), DatabaseTime.read(row, "expires_at")), hash)
                .stream().findFirst();
    }

    public void create(String id, String hash, Instant createdAt, Instant expiresAt) {
        jdbc.update("INSERT INTO anonymous_sessions (id,secret_hash,created_at,expires_at) VALUES (?,?,?,?)",
                id, hash, DatabaseTime.write(createdAt), DatabaseTime.write(expiresAt));
    }

    public void renew(String id, Instant expiresAt) {
        jdbc.update("UPDATE anonymous_sessions SET expires_at=GREATEST(expires_at,?) WHERE id=?",
                DatabaseTime.write(expiresAt), id);
    }

    public Optional<SessionRow> lock(String id) {
        return jdbc.query("SELECT id,expires_at FROM anonymous_sessions WHERE id=? FOR UPDATE",
                (row, number) -> new SessionRow(row.getString("id"), DatabaseTime.read(row, "expires_at")), id)
                .stream().findFirst();
    }
}
