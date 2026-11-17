package com.deepwistom.atoms.template;

import com.deepwistom.atoms.common.DatabaseTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TemplateRepository {
    private final JdbcTemplate jdbc;
    public TemplateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(String id, String slug, String name, String type, String category, String summary,
                      String description, String author, String sourceUrl, String licenseNote,
                      String coverPath, String versionId, int versionNo, String entryPath) {
        public boolean playable() { return versionId != null && entryPath != null; }
    }

    public record VersionRow(String id, String templateId, int versionNo, String contentHash,
                             String entryPath, int fileCount, long totalBytes, Instant createdAt) {}

    public record FileContent(String contentType, byte[] content) {}

    private static final String SELECT = """
            SELECT t.*, v.id AS version_id_value, v.version_no AS version_no_value, v.entry_path AS entry_path_value
            FROM templates t
            LEFT JOIN template_versions v ON v.id = t.current_version_id
            """;

    public List<Row> list(String type) {
        String filter = type == null ? "" : " WHERE t.type=?";
        Object[] arguments = type == null ? new Object[] {} : new Object[] { type };
        return jdbc.query(SELECT + filter + " ORDER BY t.sort_order ASC,t.name ASC", this::map, arguments);
    }

    public Optional<Row> find(String slug) {
        return jdbc.query(SELECT + " WHERE t.slug=?", this::map, slug).stream().findFirst();
    }

    public Optional<Row> findById(String id) {
        return jdbc.query(SELECT + " WHERE t.id=?", this::map, id).stream().findFirst();
    }

    public Optional<VersionRow> findVersion(String templateId, String versionId) {
        return jdbc.query("""
                SELECT * FROM template_versions WHERE template_id=? AND id=?
                """, this::mapVersion, templateId, versionId).stream().findFirst();
    }

    public Optional<VersionRow> currentVersion(String templateId) {
        return jdbc.query("""
                SELECT v.* FROM template_versions v
                JOIN templates t ON t.current_version_id=v.id
                WHERE t.id=?
                """, this::mapVersion, templateId).stream().findFirst();
    }

    public void insert(String id, String slug, String name, String type, String category, String summary,
                       String description, String author, String sourceUrl, String licenseNote,
                       String coverPath, int sortOrder, Instant now) {
        jdbc.update("""
                INSERT INTO templates
                (id,slug,name,type,category,summary,description,author,source_url,license_note,cover_path,
                 sort_order,current_version_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)
                """, id, slug, name, type, category, summary, description, author, sourceUrl, licenseNote,
                coverPath, sortOrder, DatabaseTime.write(now), DatabaseTime.write(now));
    }

    public void updateMetadata(String id, String name, String category, String summary, String description,
                               String author, String sourceUrl, String licenseNote, String coverPath,
                               int sortOrder, Instant now) {
        jdbc.update("""
                UPDATE templates SET name=?,category=?,summary=?,description=?,author=?,source_url=?,
                       license_note=?,cover_path=?,sort_order=?,updated_at=?
                WHERE id=?
                """, name, category, summary, description, author, sourceUrl, licenseNote, coverPath,
                sortOrder, DatabaseTime.write(now), id);
    }

    public int nextVersionNo(String templateId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM template_versions WHERE template_id=?", Integer.class, templateId);
        return (max == null ? 0 : max) + 1;
    }

    public void insertVersion(VersionRow version) {
        jdbc.update("""
                INSERT INTO template_versions
                (id,template_id,version_no,content_hash,entry_path,file_count,total_bytes,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, version.id(), version.templateId(), version.versionNo(), version.contentHash(),
                version.entryPath(), version.fileCount(), version.totalBytes(), DatabaseTime.write(version.createdAt()));
    }

    public void insertFile(String versionId, String path, String contentType, byte[] content) {
        jdbc.update("INSERT INTO template_files (version_id,path,content_type,size_bytes,content) VALUES (?,?,?,?,?)",
                versionId, path, contentType, content.length, content);
    }

    public void setCurrentVersion(String templateId, String versionId, Instant now) {
        jdbc.update("UPDATE templates SET current_version_id=?,updated_at=? WHERE id=?",
                versionId, DatabaseTime.write(now), templateId);
    }

    public Optional<FileContent> file(String versionId, String path) {
        return jdbc.query("SELECT content_type,content FROM template_files WHERE version_id=? AND path=?",
                (row, number) -> new FileContent(row.getString(1), row.getBytes(2)), versionId, path)
                .stream().findFirst();
    }

    private Row map(ResultSet row, int number) throws SQLException {
        return new Row(row.getString("id"), row.getString("slug"), row.getString("name"),
                row.getString("type"), row.getString("category"), row.getString("summary"),
                row.getString("description"), row.getString("author"), row.getString("source_url"),
                row.getString("license_note"), row.getString("cover_path"),
                row.getString("version_id_value"), row.getInt("version_no_value"),
                row.getString("entry_path_value"));
    }

    private VersionRow mapVersion(ResultSet row, int number) throws SQLException {
        return new VersionRow(row.getString("id"), row.getString("template_id"), row.getInt("version_no"),
                row.getString("content_hash"), row.getString("entry_path"), row.getInt("file_count"),
                row.getLong("total_bytes"), DatabaseTime.read(row, "created_at"));
    }
}
