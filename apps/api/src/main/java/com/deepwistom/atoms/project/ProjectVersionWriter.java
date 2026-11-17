package com.deepwistom.atoms.project;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.template.MediaTypes;
import com.deepwistom.atoms.run.RunEventRepository;
import com.deepwistom.atoms.run.RunRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Commits produced files as a new immutable project version. Files and the version pointer move
 * inside one transaction, so a preview never observes a half-written version.
 */
@Service
public class ProjectVersionWriter {
    private final ProjectVersionRepository versions;
    private final JdbcTemplate jdbc;
    private final RunRepository runs;
    private final RunEventRepository events;
    private final ObjectMapper json;

    public ProjectVersionWriter(ProjectVersionRepository versions, JdbcTemplate jdbc,
                                RunRepository runs, RunEventRepository events, ObjectMapper json) {
        this.versions = versions;
        this.jdbc = jdbc;
        this.runs = runs;
        this.events = events;
        this.json = json;
    }

    public record Written(String versionId, int versionNo, int fileCount, long totalBytes) {}

    public static final class CommitCancelled extends RuntimeException {}

    /** No model work belongs here: only the final guard, files, pointer, events and terminal state. */
    @Transactional
    public Written writeForRun(String projectId, String runId, String entryPath, String summary,
                               Map<String, byte[]> files, Integer slideCount, Map<String, Object> artifact) {
        var project = jdbc.queryForList(
                "SELECT current_version_id,deleted_at FROM projects WHERE id=? FOR UPDATE", projectId);
        var run = jdbc.queryForList(
                "SELECT status,cancel_requested,base_version_id FROM runs WHERE id=? AND project_id=? FOR UPDATE",
                runId, projectId);
        if (run.isEmpty() || project.isEmpty()) throw new CommitCancelled();
        var state = run.getFirst();
        if (!"RUNNING".equals(state.get("status"))
                || Boolean.TRUE.equals(state.get("cancel_requested"))
                || (state.get("cancel_requested") instanceof Number flag && flag.intValue() != 0)
                || project.getFirst().get("deleted_at") != null) throw new CommitCancelled();
        String base = (String) state.get("base_version_id");
        if (!Objects.equals(base, project.getFirst().get("current_version_id"))) {
            throw new ApiException(409, "VERSION_CONFLICT", "当前版本已变化，请基于最新版本重新提交。");
        }
        Written written = writeGenerated(projectId, entryPath, summary, files);
        jdbc.update("UPDATE project_versions SET parent_version_id=? WHERE id=?", base, written.versionId());
        artifact.put("versionId", written.versionId());
        artifact.put("versionNo", written.versionNo());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        events.append(runId, "step", json.writeValueAsString(Map.of("id", "publish", "agent", "David",
                "role", "版本入库", "state", "done",
                "detail", "版本 v" + written.versionNo() + " 已创建 · " + written.fileCount() + " 个文件")), now);
        events.append(runId, "artifact", json.writeValueAsString(artifact), now);
        events.append(runId, "status", json.writeValueAsString(Map.of("status", "SUCCEEDED", "label", "生成完成")), now);
        runs.finish(runId, "SUCCEEDED", slideCount, null, null, now);
        return written;
    }

    private Written writeGenerated(String projectId, String entryPath, String summary,
                                  Map<String, byte[]> files) {
        if (files.isEmpty()) throw new ApiException(500, "GENERATION_EMPTY", "本次生成没有产生任何文件。", true);
        if (!files.containsKey(entryPath)) {
            throw new ApiException(500, "GENERATION_EMPTY", "本次生成缺少入口文件。", true);
        }
        String versionId = UUID.randomUUID().toString();
        int versionNo = versions.nextVersionNo(projectId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Map<String, byte[]> ordered = new TreeMap<>(files);
        String hash = hash(ordered);
        long total = 0;
        for (byte[] content : ordered.values()) total += content.length;
        versions.insert(versionId, projectId, versionNo, "GENERATED", null,
                summary.substring(0, Math.min(200, summary.length())), hash, entryPath, now);
        for (Map.Entry<String, byte[]> file : ordered.entrySet()) {
            jdbc.update("""
                    INSERT INTO project_files (version_id,path,content_type,size_bytes,content)
                    VALUES (?,?,?,?,?)
                    """, versionId, file.getKey(), MediaTypes.of(file.getKey()),
                    file.getValue().length, file.getValue());
        }
        versions.setCurrentVersion(projectId, versionId, now);
        return new Written(versionId, versionNo, ordered.size(), total);
    }

    private static String hash(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                digest.update(file.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(MessageDigest.getInstance("SHA-256").digest(file.getValue()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
