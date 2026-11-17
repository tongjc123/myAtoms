package com.deepwistom.atoms.importer;

import java.util.List;

public final class TemplateImportModels {
    private TemplateImportModels() {}

    public record ImportItem(String id, String name, String filename, int slideCount, String parseState,
                             List<String> outlineTitles, String note, String createdAt) {}

    public record ImportPage(List<ImportItem> items) {}
}
