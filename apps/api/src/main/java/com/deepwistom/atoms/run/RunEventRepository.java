package com.deepwistom.atoms.run;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only event log. Every step the visitor sees is a row here, never only in memory. */
@Repository
public class RunEventRepository {
    private final JdbcTemplate jdbc;
    public RunEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(int seq, String type, String payload, Instant createdAt) {}

    public void append(String runId, String type, String payload, Instant now) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0)+1 FROM run_events WHERE run_id=?", Integer.class, runId);
        jdbc.update("INSERT INTO run_events (run_id,seq,type,payload,created_at) VALUES (?,?,?,?,?)",
                runId, next == null ? 1 : next, type, payload, DatabaseTime.write(now));
    }

    public List<Row> after(String runId, int seq, int limit) {
        return jdbc.query("""
                SELECT seq,type,payload,created_at FROM run_events
                WHERE run_id=? AND seq>? ORDER BY seq ASC LIMIT ?
                """, (row, number) -> new Row(row.getInt(1), row.getString(2), row.getString(3),
                DatabaseTime.read(row, "created_at")), runId, seq, limit);
    }
}
