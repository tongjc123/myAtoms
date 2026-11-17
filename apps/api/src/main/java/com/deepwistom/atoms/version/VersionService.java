package com.deepwistom.atoms.version;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.project.IdempotencyService;
import com.deepwistom.atoms.project.ProjectModels;
import com.deepwistom.atoms.project.ProjectRepository;
import com.deepwistom.atoms.project.ProjectService;
import com.deepwistom.atoms.project.ProjectVersionRepository;
import com.deepwistom.atoms.run.RunRepository;
import com.deepwistom.atoms.template.MediaTypes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class VersionService {
    private static final long MAX_EXPORT_BYTES = 20L * 1024 * 1024;
    private final VersionRepository history;
    private final ProjectVersionRepository versions;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final IdempotencyService idempotency;
    private final RunRepository runs;
    private final ObjectMapper json;

    public VersionService(VersionRepository history, ProjectVersionRepository versions, ProjectRepository projects,
                          ProjectService projectService, IdempotencyService idempotency, RunRepository runs,
                          ObjectMapper json) {
        this.history = history;
        this.versions = versions;
        this.projects = projects;
        this.projectService = projectService;
        this.idempotency = idempotency;
        this.runs = runs;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public VersionModels.Page list(String owner, String projectId, int page, int pageSize) {
        var project = projectService.get(owner, projectId);
        if (page < 0 || page > 10000 || pageSize < 1 || pageSize > 50) {
            throw ApiException.invalid("分页参数超出范围。");
        }
        return new VersionModels.Page(history.list(projectId, page, pageSize), page, pageSize,
                history.count(projectId), project.currentVersion() == null ? null : project.currentVersion().id());
    }

    // Idempotency reads happen before waiting on the project lock. READ_COMMITTED ensures the
    // subsequent pointer and active-run reads observe any generation that committed while waiting.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProjectModels.MutationResponse restore(String owner, String projectId, String versionId,
                                                  String key, VersionModels.RestoreRequest request) {
        if (request == null || request.expectedCurrentVersionId() == null
                || !request.expectedCurrentVersionId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw ApiException.invalid("缺少有效的当前版本，请刷新后重试。");
        }
        return idempotency.execute(owner, key, "POST /api/projects/" + projectId + "/versions/"
                + versionId + "/restore\n" + json.writeValueAsString(request), () -> {
            if (!projects.lock(owner, projectId)) {
                throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在、已删除或不属于当前访客。");
            }
            var project = projectService.get(owner, projectId);
            var source = version(projectId, versionId);
            String current = project.currentVersion() == null ? null : project.currentVersion().id();
            if (!Objects.equals(current, request.expectedCurrentVersionId())) {
                throw new ApiException(409, "VERSION_CONFLICT", "当前版本已变化，请刷新后重新操作。");
            }
            if (runs.active(projectId)) {
                throw new ApiException(409, "PROJECT_BUSY", "项目正在生成，请等待任务结束后恢复。");
            }
            if (Objects.equals(current, versionId)) {
                throw new ApiException(409, "VERSION_ALREADY_CURRENT", "所选版本已是当前版本。");
            }
            validateContents(source, history.files(versionId));
            String restoredId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            history.restore(restoredId, projectId, versions.nextVersionNo(projectId), current, versionId, now);
            versions.setCurrentVersion(projectId, restoredId, now);
            return new ProjectModels.MutationResponse(201,
                    json.writeValueAsString(new ProjectModels.ProjectResponse(projectService.get(owner, projectId))),
                    projectId);
        });
    }

    @Transactional(readOnly = true)
    public byte[] sourceZip(String owner, String projectId, String versionId) {
        var project = projectService.get(owner, projectId);
        var version = version(projectId, versionId);
        if (project.type() != ProjectModels.Type.WEB && project.type() != ProjectModels.Type.GAME) {
            throw new ApiException(400, "UNSUPPORTED_PROJECT_TYPE", "仅网页和游戏项目支持源码下载。");
        }
        var files = history.files(versionId);
        validateContents(version, files);
        if (files.size() > 100 || files.stream().mapToLong(VersionRepository.FileInfo::bytes).sum() > MAX_EXPORT_BYTES) {
            throw new ApiException(409, "SOURCE_EXPORT_TOO_LARGE", "该版本超出源码下载大小限制。");
        }
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output)) {
                for (var file : history.sourceFiles(versionId)) {
                    zip.putNextEntry(new ZipEntry(file.path()));
                    zip.write(file.content());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Could not create source archive", error);
        }
    }

    private ProjectVersionRepository.VersionRow version(String projectId, String versionId) {
        if (versionId == null || !versionId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiException(404, "VERSION_NOT_FOUND", "版本不存在。");
        }
        return versions.find(projectId, versionId)
                .orElseThrow(() -> new ApiException(404, "VERSION_NOT_FOUND", "版本不存在。"));
    }

    private void validateContents(ProjectVersionRepository.VersionRow version, List<VersionRepository.FileInfo> files) {
        if (files.isEmpty() || files.stream().noneMatch(file ->
                file.path().equals(version.entryPath()) && file.bytes() > 0)) throw unavailable();
        for (var file : files) {
            try {
                // normalize accepts a preview URL's leading slash; archive names must be relative.
                if (!MediaTypes.normalize(file.path(), null).equals(file.path())
                        || file.path().endsWith("/") || file.path().contains("//")
                        || List.of(file.path().split("/")).contains(".")
                        || file.bytes() != file.declaredBytes()) throw unavailable();
            } catch (IllegalArgumentException error) {
                throw unavailable();
            }
        }
    }

    private ApiException unavailable() {
        return new ApiException(409, "VERSION_CONTENT_UNAVAILABLE", "该版本文件不完整或无效，无法使用。");
    }
}
