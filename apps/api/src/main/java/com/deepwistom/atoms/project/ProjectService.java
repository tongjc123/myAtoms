package com.deepwistom.atoms.project;

import com.deepwistom.atoms.common.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.deepwistom.atoms.project.ProjectModels.*;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ProjectService(ProjectRepository projects, IdempotencyService idempotency, ObjectMapper json) {
        this.projects = projects;
        this.idempotency = idempotency;
        this.json = json;
    }

    public MutationResponse create(String owner, String key, CreateRequest request) {
        String name = name(request.name());
        if (request.type() == null || request.mode() == null) throw ApiException.invalid("请选择项目类型和创建模式。");
        if (request.prompt() == null || request.prompt().isBlank() || request.prompt().length() > 8000) {
            throw ApiException.invalid("需求描述需为 1–8000 个字符。");
        }
        CreateRequest normalized = new CreateRequest(name, request.type(), request.prompt().strip(), request.mode());
        return idempotency.execute(owner, key, "POST /api/projects\n" + json.writeValueAsString(normalized), () -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Project project = new Project(UUID.randomUUID().toString(), name, normalized.type(),
                    normalized.prompt(), normalized.mode(), "DRAFT", 1, now, now, null, null);
            projects.insert(owner, project);
            return response(201, project);
        });
    }

    @Transactional(readOnly = true)
    public ProjectPage list(String owner, int page, int pageSize) {
        if (page < 0 || page > 10000 || pageSize < 1 || pageSize > 50) throw ApiException.invalid("分页参数超出范围。");
        return new ProjectPage(projects.list(owner, page, pageSize), page, pageSize, projects.count(owner));
    }

    public Project get(String owner, String id) {
        id(id);
        return projects.find(owner, id).orElseThrow(() ->
                new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在、已删除或不属于当前访客。"));
    }

    public MutationResponse rename(String owner, String id, String key, RenameRequest request) {
        id(id);
        String name = name(request.name());
        long revision = revision(request.expectedRevision());
        return idempotency.execute(owner, key, "PATCH /api/projects/" + id + "\n"
                + json.writeValueAsString(new RenameRequest(name, revision)), () -> {
            get(owner, id);
            if (projects.rename(owner, id, name, revision, Instant.now()) != 1) throw conflict();
            return response(200, get(owner, id));
        });
    }

    public MutationResponse delete(String owner, String id, String key, DeleteRequest request) {
        id(id);
        long revision = revision(request.expectedRevision());
        return idempotency.execute(owner, key, "DELETE /api/projects/" + id + "\n" + revision, () -> {
            get(owner, id);
            if (projects.delete(owner, id, revision, Instant.now()) != 1) throw conflict();
            return new MutationResponse(204, "", id);
        });
    }

    private MutationResponse response(int status, Project project) {
        return new MutationResponse(status, json.writeValueAsString(new ProjectResponse(project)), project.id());
    }
    private String name(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 80) throw ApiException.invalid("项目名称需为 1–80 个字符。");
        return name.strip();
    }
    private long revision(Long revision) {
        if (revision == null || revision < 1) throw ApiException.invalid("缺少有效项目版本，请刷新后重试。");
        return revision;
    }
    private void id(String id) {
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在。");
        }
    }
    private ApiException conflict() {
        return new ApiException(409, "VERSION_CONFLICT", "项目已在其他页面更新，请刷新后重新操作。");
    }
}
