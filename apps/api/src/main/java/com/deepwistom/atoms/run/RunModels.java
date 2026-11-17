package com.deepwistom.atoms.run;

import java.time.Instant;
import java.util.List;

public final class RunModels {
    private RunModels() {}

    public record Run(String id, String projectId, String purpose, String status, String engine,
                      String theme, Integer slideCount, int totalSteps, String errorCode,
                      String errorMessage, String modelKey, Object spec, String instruction, String baseVersionId,
                      Instant createdAt, Instant startedAt, Instant finishedAt) {}

    public record Event(int seq, String type, Object data, Instant createdAt) {}

    public record StartRequest(String templateSlug, String importId, String model, String ratio,
                               String resolution, Integer durationSeconds, String instruction, String baseVersionId) {}

    public record StartResponse(String runId, String status) {}

    public record Snapshot(Run run, List<Event> events) {}
}
