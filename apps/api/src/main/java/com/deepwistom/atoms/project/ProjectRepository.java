package com.deepwistom.atoms.project;

import com.deepwistom.atoms.common.DatabaseTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.deepwistom.atoms.project.ProjectModels.*;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;
    public ProjectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SELECT = """
            SELECT p.*,
                   v.id AS current_version_id_value, v.version_no AS current_version_no,
                   v.kind AS current_version_kind, v.summary AS current_version_summary,
                   v.created_at AS current_version_created_at,
                   t.slug AS source_template_slug, t.name AS source_template_name
            FROM projects p
            LEFT JOIN project_versions v ON v.id = p.current_version_id
            LEFT JOIN template_versions tv ON tv.id = p.source_template_version_id
            LEFT JOIN templates t ON t.id = tv.template_id
            """;

    public void insert(String owner, Project project) {
        jdbc.update("""
                INSERT INTO projects (id,owner_session_id,name,type,prompt,mode,revision,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, project.id(), owner, project.name(), project.type().name(), project.prompt(),
                project.mode().name(), project.revision(), DatabaseTime.write(project.createdAt()), DatabaseTime.write(project.updatedAt()));
    }

    public Optional<Project> find(String owner, String id) {
        return jdbc.query(SELECT + " WHERE p.id=? AND p.owner_session_id=? AND p.deleted_at IS NULL",
                this::map, id, owner).stream().findFirst();
    }

    /** All generation starts and commits lock this row first. */
    public boolean lock(String owner, String id) {
        return !jdbc.queryForList("""
                SELECT id FROM projects WHERE id=? AND owner_session_id=? AND deleted_at IS NULL FOR UPDATE
                """, String.class, id, owner).isEmpty();
    }

    public List<Project> list(String owner, int page, int size) {
        return jdbc.query(SELECT + """
                 WHERE p.owner_session_id=? AND p.deleted_at IS NULL
                ORDER BY p.updated_at DESC,p.id DESC LIMIT ? OFFSET ?
                """, this::map, owner, size, (long) page * size);
    }

    public long count(String owner) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE owner_session_id=? AND deleted_at IS NULL",
                Long.class, owner);
        return count == null ? 0 : count;
    }

    public int rename(String owner, String id, String name, long revision, Instant now) {
        return jdbc.update("""
                UPDATE projects SET name=?,revision=revision+1,updated_at=?
                WHERE id=? AND owner_session_id=? AND revision=? AND deleted_at IS NULL
                """, name, DatabaseTime.write(now), id, owner, revision);
    }

    public int delete(String owner, String id, long revision, Instant now) {
        return jdbc.update("""
                UPDATE projects SET deleted_at=?,updated_at=?,revision=revision+1
                WHERE id=? AND owner_session_id=? AND revision=? AND deleted_at IS NULL
                """, DatabaseTime.write(now), DatabaseTime.write(now), id, owner, revision);
    }

    private Project map(ResultSet row, int number) throws SQLException {
        String versionId = row.getString("current_version_id_value");
        VersionRef currentVersion = versionId == null ? null : new VersionRef(versionId,
                row.getInt("current_version_no"), row.getString("current_version_kind"),
                row.getString("current_version_summary"), DatabaseTime.read(row, "current_version_created_at"));
        String templateSlug = row.getString("source_template_slug");
        TemplateRef sourceTemplate = templateSlug == null ? null
                : new TemplateRef(templateSlug, row.getString("source_template_name"));
        return new Project(row.getString("id"), row.getString("name"), Type.valueOf(row.getString("type")),
                row.getString("prompt"), Mode.valueOf(row.getString("mode")),
                versionId == null ? "DRAFT" : "READY", row.getLong("revision"),
                DatabaseTime.read(row, "created_at"), DatabaseTime.read(row, "updated_at"),
                currentVersion, sourceTemplate);
    }
}
