package com.deepwistom.atoms.importer;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.slides.SlidePlanner.OutlineSlide;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class TemplateImportService {
    private static final Logger log = LoggerFactory.getLogger(TemplateImportService.class);
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final List<String> EXTENSIONS = List.of(".pptx", ".pdf");

    private final TemplateImportRepository imports;
    private final ObjectMapper json;

    public TemplateImportService(TemplateImportRepository imports, ObjectMapper json) {
        this.imports = imports;
        this.json = json;
    }

    @Transactional
    public TemplateImportModels.ImportItem importDeck(String owner, String requestedName, String filename,
                                                      String contentType, byte[] content) {
        if (content == null || content.length == 0) throw ApiException.invalid("请选择要导入的文件。");
        if (content.length > MAX_BYTES) throw new ApiException(413, "IMPORT_TOO_LARGE", "文件不能超过 8MB。");
        String safeName = filename == null ? "" : filename.strip();
        String lower = safeName.toLowerCase(Locale.ROOT);
        if (EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw ApiException.invalid("目前支持导入 .pptx 或 .pdf 文件。");
        }

        int slideCount = 0;
        String parseState = "STORED_ONLY";
        List<OutlineSlide> outline = List.of();
        if (lower.endsWith(".pptx")) {
            try {
                var parsed = PptxOutlineParser.parse(content);
                slideCount = parsed.slideCount();
                outline = parsed.outline();
                if (!outline.isEmpty()) parseState = "PARSED";
            } catch (IOException error) {
                log.warn("pptx parse failed file={} reason={}", safeName, error.getClass().getSimpleName());
            }
        } else {
            slideCount = PptxOutlineParser.pdfPages(content);
        }

        String name = requestedName == null || requestedName.isBlank()
                ? safeName.replaceFirst("\\.[Pp][Dd][Ff][Tt][Xx]$", "")
                : requestedName.strip();
        if (name.isBlank() || name.length() > 80) name = name.isBlank() ? "导入的演示" : name.substring(0, 80);

        var row = new TemplateImportRepository.Row(UUID.randomUUID().toString(), name, safeName,
                lower.endsWith(".pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                content.length, slideCount, parseState, json.writeValueAsString(outline),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        imports.insert(row, owner, content);
        return item(row);
    }

    @Transactional(readOnly = true)
    public TemplateImportModels.ImportPage list(String owner) {
        List<TemplateImportModels.ImportItem> items = imports.list(owner, 20).stream()
                .map(this::item).toList();
        return new TemplateImportModels.ImportPage(items);
    }

    @Transactional(readOnly = true)
    public List<OutlineSlide> outline(String owner, String id) {
        var row = imports.find(owner, id).orElseThrow(() ->
                new ApiException(404, "IMPORT_NOT_FOUND", "导入的模板不存在。"));
        try {
            return json.readValue(row.outline(), new TypeReference<List<OutlineSlide>>() {});
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    TemplateImportModels.ImportItem item(TemplateImportRepository.Row row) {
        List<String> titles = new ArrayList<>();
        try {
            List<OutlineSlide> outline = json.readValue(row.outline(),
                    new TypeReference<List<OutlineSlide>>() {});
            for (int index = 0; index < outline.size() && index < 8; index++) {
                titles.add(outline.get(index).title());
            }
        } catch (RuntimeException ignored) {
            // An unreadable outline only removes the preview titles, never the import itself.
        }
        String note = "PARSED".equals(row.parseState())
                ? "已解析 " + row.slideCount() + " 页文字大纲"
                : "已保存原文件 · 暂不支持文字解析";
        return new TemplateImportModels.ImportItem(row.id(), row.name(), row.filename(), row.slideCount(),
                row.parseState(), titles, note, row.createdAt().toString());
    }
}
