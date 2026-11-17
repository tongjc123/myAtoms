package com.deepwistom.atoms.version;

import java.time.Instant;
import java.util.List;

public final class VersionModels {
    private VersionModels() {}

    public record Version(String id, int number, String kind, String summary, Instant createdAt,
                          String parentVersionId, String restoredFromVersionId, String entryPath) {}
    public record Page(List<Version> items, int page, int pageSize, long total, String currentVersionId) {}
    public record RestoreRequest(String expectedCurrentVersionId) {}
}
