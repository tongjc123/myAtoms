package com.deepwistom.atoms.preview;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.project.ProjectRepository;
import com.deepwistom.atoms.project.ProjectVersionRepository;
import com.deepwistom.atoms.session.SessionService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues short-lived, project-scoped preview tokens. A sandboxed iframe has an opaque origin, so
 * the owner cookie cannot be relied on inside it; the token carries exactly one project version.
 */
@Service
public class PreviewService {
    private final PreviewRepository previews;
    private final ProjectRepository projects;
    private final ProjectVersionRepository versions;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;

    public PreviewService(PreviewRepository previews, ProjectRepository projects,
                          ProjectVersionRepository versions,
                          @Value("${atoms.preview.ttl:PT30M}") Duration ttl) {
        this.previews = previews;
        this.projects = projects;
        this.versions = versions;
        this.ttl = ttl;
    }

    public record Session(String baseUrl, String entryPath, String expiresAt) {}
    public record CreateRequest(String versionId) {}

    @Transactional
    public Session create(String owner, String projectId) {
        return create(owner, projectId, null);
    }

    @Transactional
    public Session create(String owner, String projectId, String versionId) {
        if (projectId == null || !projectId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw notFound();
        }
        var project = projects.find(owner, projectId).orElseThrow(PreviewService::notFound);
        if (versionId == null && project.currentVersion() == null) {
            throw new ApiException(409, "PREVIEW_NOT_READY", "项目还没有可预览的版本。");
        }
        String selected = versionId == null ? project.currentVersion().id() : versionId;
        if (!selected.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiException(404, "VERSION_NOT_FOUND", "版本不存在。");
        }
        var version = versions.find(projectId, selected)
                .orElseThrow(() -> new ApiException(404, "VERSION_NOT_FOUND", "版本不存在。"));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(ttl);
        previews.deleteExpired(now);
        previews.insert(sha256(token), projectId, version.id(), owner, now, expiresAt);
        return new Session("/api/previews/" + token + "/", version.entryPath(), expiresAt.toString());
    }

    @Transactional(readOnly = true)
    public PreviewRepository.Access resolve(String token) {
        return previews.resolve(sha256(token), Instant.now())
                .orElseThrow(() -> new ApiException(404, "PREVIEW_NOT_FOUND", "预览链接已过期，请刷新页面重新打开。"));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在、已删除或不属于当前访客。");
    }
}
