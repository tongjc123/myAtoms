package com.deepwistom.atoms.template;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.project.IdempotencyService;
import com.deepwistom.atoms.project.ProjectModels;
import com.deepwistom.atoms.project.ProjectRepository;
import com.deepwistom.atoms.project.ProjectVersionRepository;
import com.deepwistom.atoms.slides.DeckRenderer;
import com.deepwistom.atoms.slides.SlideDeck;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.deepwistom.atoms.template.TemplateModels.*;

@Service
public class TemplateService {
    private static final Set<String> TYPES = Set.of("WEB", "GAME", "SLIDES", "VIDEO");

    private final TemplateRepository templates;
    private final ProjectRepository projects;
    private final ProjectVersionRepository versions;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final DeckRenderer renderer;

    public TemplateService(TemplateRepository templates, ProjectRepository projects,
                           ProjectVersionRepository versions, IdempotencyService idempotency,
                           ObjectMapper json, DeckRenderer renderer) {
        this.templates = templates;
        this.projects = projects;
        this.versions = versions;
        this.idempotency = idempotency;
        this.json = json;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public TemplatePage list(String type) {
        String filter = type == null || type.isBlank() ? null : type.toUpperCase();
        if (filter != null && !TYPES.contains(filter)) throw ApiException.invalid("未知的模板类型。");
        List<Item> items = templates.list(filter).stream().map(this::item).toList();
        return new TemplatePage(items);
    }

    @Transactional(readOnly = true)
    public Item get(String slug) {
        return item(find(slug));
    }

    /**
     * Clones a template snapshot into an independent project. The template version itself is
     * copied file by file, so a later template update never mutates an existing project.
     */
    public ProjectModels.MutationResponse clone(String owner, String slug, String key, CloneRequest request) {
        var template = find(slug);
        var version = resolveVersion(template);
        String name = name(request == null ? null : request.name(), template.name());
        String rawPrompt = "从模板「" + template.name() + "」开始：" + template.summary();
        String prompt = rawPrompt.length() > 8000 ? rawPrompt.substring(0, 8000) : rawPrompt;
        String command = "POST /api/templates/" + slug + "/clone\n"
                + json.writeValueAsString(new CloneRequest(name, version.id()));
        return idempotency.execute(owner, key, command, () -> create(owner, template, version, name, prompt));
    }

    private ProjectModels.MutationResponse create(String owner, TemplateRepository.Row template,
                                                  TemplateRepository.VersionRow version, String name, String prompt) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String projectId = UUID.randomUUID().toString();
        var project = new ProjectModels.Project(projectId, name, ProjectModels.Type.valueOf(template.type()),
                prompt, ProjectModels.Mode.BUILD, "DRAFT", 1, now, now, null, null);
        projects.insert(owner, project);

        String versionId = UUID.randomUUID().toString();
        versions.insert(versionId, projectId, 1, "TEMPLATE", version.id(),
                "导入模板「" + template.name() + "」v" + version.versionNo(), version.contentHash(),
                version.entryPath(), now);
        int copied = versions.copyFromTemplate(versionId, version.id());
        if (copied != version.fileCount()) {
            throw new ApiException(500, "TEMPLATE_INCOMPLETE", "模板文件快照不完整，请稍后重试。", true);
        }
        versions.setCurrentVersion(projectId, versionId, now);
        versions.setSourceTemplateVersion(projectId, version.id());

        var saved = projects.find(owner, projectId).orElseThrow(() ->
                new ApiException(500, "PROJECT_NOT_FOUND", "项目创建后无法读取，请重试。", true));
        return new ProjectModels.MutationResponse(201,
                json.writeValueAsString(new ProjectModels.ProjectResponse(saved)), projectId);
    }

    private TemplateRepository.Row find(String slug) {
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{1,47}")) {
            throw new ApiException(404, "TEMPLATE_NOT_FOUND", "模板不存在。");
        }
        return templates.find(slug).orElseThrow(() ->
                new ApiException(404, "TEMPLATE_NOT_FOUND", "模板不存在或已下架。"));
    }

    private TemplateRepository.VersionRow resolveVersion(TemplateRepository.Row template) {
        if (template.versionId() == null) {
            throw new ApiException(409, "TEMPLATE_NOT_READY", "该模板暂无可用的内容版本。");
        }
        return templates.currentVersion(template.id()).orElseThrow(() ->
                new ApiException(409, "TEMPLATE_NOT_READY", "该模板暂无可用的内容版本。"));
    }

    private String name(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested.strip();
        if (value.isBlank() || value.length() > 80) throw ApiException.invalid("项目名称需为 1–80 个字符。");
        return value;
    }

    Item item(TemplateRepository.Row row) {
        String entry = row.entryPath() == null ? "index.html" : row.entryPath();
        String base = "/api/template-previews/" + row.slug() + "/";
        return new Item(row.id(), row.slug(), row.name(), row.type(), row.category(), row.summary(),
                row.description(), row.author(), row.sourceUrl(), row.licenseNote(), row.versionId(),
                row.versionNo(), base + entry, row.coverPath() == null ? null : base + row.coverPath(),
                row.playable(), theme(row, entry), slideCount(row, entry));
    }

    private String theme(TemplateRepository.Row row, String entry) { return deck(row, entry).theme; }

    private int slideCount(TemplateRepository.Row row, String entry) { return deck(row, entry).slides; }

    private DeckFacts deck(TemplateRepository.Row row, String entry) {
        if (row.versionId() == null) return DeckFacts.EMPTY;
        try {
            var file = templates.file(row.versionId(), entry).orElse(null);
            if (file == null) return DeckFacts.EMPTY;
            SlideDeck deck = renderer.read(file.content());
            return deck == null ? DeckFacts.EMPTY : new DeckFacts(deck.theme(), deck.size());
        } catch (RuntimeException error) {
            return DeckFacts.EMPTY;
        }
    }

    private record DeckFacts(String theme, int slides) {
        static final DeckFacts EMPTY = new DeckFacts(null, 0);
    }
}
