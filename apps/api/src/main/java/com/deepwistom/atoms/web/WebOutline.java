package com.deepwistom.atoms.web;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Page structure the model proposes before writing markup. It is parsed defensively: anything the
 * model returns that does not look like the requested shape is rejected instead of being rendered.
 */
public record WebOutline(String title, String tagline, String cta, List<Section> sections) {

    public record Section(String id, String heading, List<String> points) {}

    public String summary() {
        List<String> names = new ArrayList<>();
        for (Section section : sections) names.add(section.id());
        return String.join(" · ", names);
    }

    public static WebOutline parse(ObjectMapper json, String raw) {
        String body = jsonBody(raw);
        if (body == null) return null;
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (RuntimeException error) {
            return null;
        }
        String title = text(node, "title", 80);
        String tagline = text(node, "tagline", 160);
        String cta = text(node, "cta", 40);
        if (title == null || tagline == null) return null;
        List<Section> sections = new ArrayList<>();
        JsonNode array = node.path("sections");
        if (array.isArray()) {
            for (JsonNode item : array) {
                if (sections.size() >= 8) break;
                String heading = text(item, "heading", 80);
                if (heading == null) continue;
                String id = text(item, "id", 24);
                List<String> points = new ArrayList<>();
                JsonNode list = item.path("points");
                if (list.isArray()) {
                    for (JsonNode point : list) {
                        String value = point.isString() ? point.asString().strip() : "";
                        if (!value.isBlank() && points.size() < 6) points.add(limit(value, 160));
                    }
                }
                sections.add(new Section(id == null ? "section-" + (sections.size() + 1) : id, heading, points));
            }
        }
        if (sections.isEmpty()) return null;
        return new WebOutline(title, tagline, cta == null ? "了解更多" : cta, sections);
    }

    /** Pulls the JSON object out of a reply that may be wrapped in prose or a fenced block. */
    private static String jsonBody(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return value.substring(start, end + 1);
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isString()) return null;
        String text = value.asString().strip();
        return text.isEmpty() ? null : limit(text, max);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
