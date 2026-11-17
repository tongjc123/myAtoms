package com.deepwistom.atoms.importer;

import com.deepwistom.atoms.common.DatabaseTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TemplateImportRepository {
    private final JdbcTemplate jdbc;
    public TemplateImportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(String id, String name, String filename, String contentType, int sizeBytes,
                      int slideCount, String parseState, String outline, Instant createdAt) {}

    private static final String COLUMNS =
            "id,name,filename,content_type,size_bytes,slide_count,parse_state,outline,created_at";

    public void insert(Row row, String owner, byte[] content) {
        jdbc.update("""
                INSERT INTO template_imports
                (id,owner_session_id,name,filename,content_type,size_bytes,slide_count,parse_state,outline,content,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, row.id(), owner, row.name(), row.filename(), row.contentType(), row.sizeBytes(),
                row.slideCount(), row.parseState(), row.outline(), content, DatabaseTime.write(row.createdAt()));
    }

    public List<Row> list(String owner, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM template_imports WHERE owner_session_id=? "
                + "ORDER BY created_at DESC LIMIT ?", this::map, owner, limit);
    }

    public Optional<Row> find(String owner, String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM template_imports WHERE id=? AND owner_session_id=?",
                this::map, id, owner).stream().findFirst();
    }

    private Row map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new Row(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getInt(5), row.getInt(6), row.getString(7), row.getString(8),
                DatabaseTime.read(row, "created_at"));
    }
}
