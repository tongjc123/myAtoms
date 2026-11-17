package com.deepwistom.atoms.template;

import java.util.List;

public final class TemplateModels {
    private TemplateModels() {}

    /** Public catalogue entry. Play and cover URLs are derived from the slug, never stored secrets. */
    public record Item(String id, String slug, String name, String type, String category,
                       String summary, String description, String author, String sourceUrl,
                       String licenseNote, String versionId, int versionNo, String playUrl,
                       String coverUrl, boolean playable, String theme, int slideCount) {}

    public record TemplatePage(List<Item> items) {}

    public record CloneRequest(String name, String templateVersionId) {}
}
