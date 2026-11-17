package com.deepwistom.atoms.slides;

import com.deepwistom.atoms.project.ProjectVersionWriter;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Deck-specific façade over {@link ProjectVersionWriter}: a deck always enters at index.html.
 */
@Service
public class SlideDeckWriter {
    private final ProjectVersionWriter writer;

    public SlideDeckWriter(ProjectVersionWriter writer) {
        this.writer = writer;
    }

    public record Written(String versionId, int versionNo, int fileCount, long totalBytes) {}

    public Written write(String projectId, String runId, String summary, Map<String, byte[]> files,
                         int slideCount, Map<String, Object> artifact) {
        var written = writer.writeForRun(projectId, runId, "index.html", summary, files, slideCount, artifact);
        return new Written(written.versionId(), written.versionNo(), written.fileCount(), written.totalBytes());
    }
}
