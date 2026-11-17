package com.deepwistom.atoms.project;

import java.time.Instant;
import java.util.List;

public final class ProjectModels {
    private ProjectModels() {}
    public enum Type { WEB, GAME, SLIDES, VIDEO }
    public enum Mode { BUILD, PLAN }
    public record CreateRequest(String name, Type type, String prompt, Mode mode) {}
    public record RenameRequest(String name, Long expectedRevision) {}
    public record DeleteRequest(Long expectedRevision) {}
    public record VersionRef(String id, int number, String kind, String summary, Instant createdAt) {}
    public record TemplateRef(String slug, String name) {}
    public record Project(String id, String name, Type type, String prompt, Mode mode,
                          String status, long revision, Instant createdAt, Instant updatedAt,
                          VersionRef currentVersion, TemplateRef sourceTemplate) {}
    public record ProjectResponse(Project project) {}
    public record ProjectPage(List<Project> items, int page, int pageSize, long total) {}
    public record MutationResponse(int status, String body, String projectId) {}
}
