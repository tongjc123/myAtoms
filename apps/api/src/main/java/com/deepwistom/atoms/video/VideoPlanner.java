package com.deepwistom.atoms.video;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deterministic storyboard helper. It only re-organises what the visitor actually wrote: beats
 * are cut from the prompt on sentence and clause boundaries, and no content is invented. The
 * compiled prompt is the original text plus the technical parameters the caller picked, never a
 * rephrased or "enhanced" narrative.
 */
@Service
public class VideoPlanner {
    public record Beat(int index, String text) {}
    public record Plan(List<Beat> beats, String subject) {}

    public Plan plan(String prompt) {
        String normalized = prompt == null ? "" : prompt.replaceAll("\\s+", " ").trim();
        List<Beat> beats = new ArrayList<>();
        for (String sentence : normalized.split("[。！？!?；;\\n]+")) {
            String value = sentence.trim();
            if (value.isEmpty()) continue;
            if (value.length() > 42) {
                for (String clause : value.split("[，,、]+")) {
                    String piece = clause.trim();
                    if (!piece.isEmpty()) beats.add(new Beat(beats.size() + 1, clamp(piece)));
                }
            } else {
                beats.add(new Beat(beats.size() + 1, clamp(value)));
            }
            if (beats.size() >= 4) break;
        }
        if (beats.isEmpty()) beats.add(new Beat(1, clamp(normalized)));
        String subject = beats.get(0).text();
        return new Plan(beats, subject.length() <= 20 ? subject : subject.substring(0, 19) + "…");
    }

    /** The exact text that is sent to the provider: the visitor's prompt plus explicit specs. */
    public String compile(String prompt, VideoCatalog.Spec spec) {
        StringBuilder compiled = new StringBuilder(prompt.strip());
        compiled.append("。规格：").append(spec.durationSeconds()).append(" 秒，").append(spec.resolution());
        if (spec.ratio() != null) compiled.append("，画幅 ").append(spec.ratio());
        return compiled.toString();
    }

    private static String clamp(String value) {
        return value.length() <= 42 ? value : value.substring(0, 41) + "…";
    }
}
