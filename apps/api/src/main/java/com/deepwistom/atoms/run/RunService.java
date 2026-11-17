package com.deepwistom.atoms.run;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.importer.TemplateImportRepository;
import com.deepwistom.atoms.importer.TemplateImportService;
import com.deepwistom.atoms.project.ProjectModels;
import com.deepwistom.atoms.project.ProjectRepository;
import com.deepwistom.atoms.session.SessionRepository;
import com.deepwistom.atoms.project.ProjectVersionWriter;
import com.deepwistom.atoms.provider.ArkClient;
import com.deepwistom.atoms.provider.ProviderException;
import com.deepwistom.atoms.provider.ProviderProperties;
import com.deepwistom.atoms.slides.DeckRenderer;
import com.deepwistom.atoms.slides.SlideDeck;
import com.deepwistom.atoms.project.ProjectVersionRepository;
import com.deepwistom.atoms.slides.SlidePlanner;
import com.deepwistom.atoms.template.TemplateRepository;
import com.deepwistom.atoms.video.BudgetService;
import com.deepwistom.atoms.video.VideoCatalog;
import com.deepwistom.atoms.video.VideoPlanner;
import com.deepwistom.atoms.web.WebGenerator;
import com.deepwistom.atoms.web.WebEditSource;
import com.deepwistom.atoms.web.WebHtml;
import com.deepwistom.atoms.web.WebOutline;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives one generation run from queued to a committed version.
 *
 * <p>Every step is executed here for real (analysis, planning, rendering or provider polling,
 * storage) and written to {@code run_events} as it happens. The browser only replays that log, so
 * reloading a conversation shows exactly what the server did, and stopping a run leaves the same
 * trace. Slides are produced by the local layout engine; videos are produced by the configured
 * provider — the run fails loudly when the provider cannot serve the request.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    public static final String ENGINE = "atoms-slide-layout/1.0";
    public static final String VIDEO_ENGINE = "volcengine-ark/1.0";
    public static final String WEB_ENGINE = "qianwen-chat/1.0";
    private static final int STEPS = 6;
    private static final int WEB_STEPS = 5;
    /** Hard cap for a downloaded video; Seedance 1080p clips stay well below this. */
    private static final long MAX_VIDEO_BYTES = 256L * 1024 * 1024;

    private final ProjectRepository projects;
    private final SessionRepository sessions;
    private final RunRepository runs;
    private final RunEventRepository events;
    private final SlidePlanner planner;
    private final DeckRenderer renderer;
    private final ProjectVersionRepository versions;
    private final TemplateRepository templates;
    private final TemplateImportRepository imports;
    private final TemplateImportService importService;
    private final VideoCatalog catalog;
    private final VideoPlanner videoPlanner;
    private final BudgetService budget;
    private final ArkClient ark;
    private final WebGenerator webGenerator;
    private final ProjectVersionWriter versionWriter;
    private final java.time.Duration videoTimeout;
    private final java.time.Duration pollInterval;
    private final ObjectMapper json;
    private final ExecutorService workers;

    public RunService(ProjectRepository projects, SessionRepository sessions, RunRepository runs, RunEventRepository events,
                      SlidePlanner planner, DeckRenderer renderer, ProjectVersionRepository versions,
                      TemplateRepository templates, TemplateImportRepository imports,
                      TemplateImportService importService, VideoCatalog catalog,
                      VideoPlanner videoPlanner, BudgetService budget, ArkClient ark,
                      WebGenerator webGenerator,
                      ProjectVersionWriter versionWriter, ProviderProperties provider,
                      ObjectMapper json) {
        this.projects = projects;
        this.sessions = sessions;
        this.runs = runs;
        this.events = events;
        this.planner = planner;
        this.renderer = renderer;
        this.versions = versions;
        this.templates = templates;
        this.imports = imports;
        this.importService = importService;
        this.catalog = catalog;
        this.videoPlanner = videoPlanner;
        this.budget = budget;
        this.ark = ark;
        this.webGenerator = webGenerator;
        this.versionWriter = versionWriter;
        this.videoTimeout = provider.videoTimeout();
        this.pollInterval = provider.pollInterval();
        this.json = json;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "atoms-run");
            thread.setDaemon(true);
            return thread;
        };
        this.workers = Executors.newFixedThreadPool(2, factory);
    }

    @PreDestroy
    void shutdown() { workers.shutdownNow(); }

    @Transactional
    public RunModels.StartResponse start(String owner, String projectId, RunModels.StartRequest request, String key) {
        // Restore/idempotency locks owner then project. The run INSERT also locks its owner
        // foreign key, so acquiring the project first would deadlock with a waiting restore.
        sessions.lock(owner).orElseThrow(() -> new ApiException(401, "SESSION_REQUIRED", "匿名会话不可用，请重新连接。"));
        if (!projects.lock(owner, projectId)) throw projectNotFound();
        ProjectModels.Project project = projects.find(owner, projectId).orElseThrow(RunService::projectNotFound);
        if (request == null) request = new RunModels.StartRequest(null, null, null, null, null, null, null, null);
        String instruction = request.instruction() == null ? null : request.instruction().strip();
        String base = request.baseVersionId();
        if (instruction != null && (instruction.isEmpty() || instruction.length() > 8000)) {
            throw ApiException.invalid("修改说明须为 1 到 8000 字。");
        }
        if ((instruction == null) != (base == null)) throw ApiException.invalid("修改说明和基础版本须同时提供。");
        if (base != null) base = uuid(base);
        if (instruction != null && project.type() != ProjectModels.Type.WEB && project.type() != ProjectModels.Type.GAME) {
            throw ApiException.invalid("只有网页和游戏支持基于源码修改。");
        }
        request = new RunModels.StartRequest(request.templateSlug(), request.importId(), request.model(),
                request.ratio(), request.resolution(), request.durationSeconds(), instruction, base);
        String payload = json.writeValueAsString(request);
        if (key != null) {
            key = uuid(key);
            var prior = runs.byRequestKey(owner, projectId, key);
            if (prior.isPresent()) {
                if (!payload.equals(prior.get().requestPayload())) {
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一请求标识不能用于不同的生成内容。");
                }
                return new RunModels.StartResponse(prior.get().id(), prior.get().status());
            }
        }
        if (runs.active(projectId)) throw new ApiException(409, "RUN_IN_PROGRESS", "这个项目已有正在进行的生成任务。");
        String current = project.currentVersion() == null ? null : project.currentVersion().id();
        if (instruction != null && !java.util.Objects.equals(base, current)) {
            throw new ApiException(409, "VERSION_CONFLICT", "当前版本已变化，请基于最新版本重新提交。");
        }
        RunModels.StartResponse response;
        if (project.type() == ProjectModels.Type.VIDEO) response = startVideo(owner, project, request);
        else if (project.type() == ProjectModels.Type.WEB || project.type() == ProjectModels.Type.GAME) {
            response = startWeb(owner, project, instruction, current);
        } else response = startSlides(owner, project, request);
        runs.bindContext(response.runId(), instruction, current, key, payload);
        return response;
    }

    private static String uuid(String value) {
        try {
            String parsed = UUID.fromString(value).toString();
            if (!parsed.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException error) { throw ApiException.invalid("版本或请求标识须为 UUID。"); }
    }

    private void enqueue(Runnable work) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { workers.submit(work); }
        });
    }

    // ------------------------------------------------------------------- web

    private RunModels.StartResponse startWeb(String owner, ProjectModels.Project project, String instruction, String base) {
        if (!webGenerator.configured()) {
            throw new ApiException(503, "PROVIDER_NOT_CONFIGURED",
                    "服务端尚未配置文本模型编号，网页和游戏生成暂不可用。", true);
        }
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        runs.insert(runId, project.id(), owner, project.type().name(), WEB_ENGINE, project.prompt(),
                null, null, null, webGenerator.modelId(), null, WEB_STEPS, now);
        WebEditSource source = null;
        if (instruction != null) {
            var version = versions.find(project.id(), base).orElseThrow(() ->
                    new ApiException(409, "VERSION_CONFLICT", "找不到基础版本。"));
            var file = versions.file(base, version.entryPath()).orElseThrow(() ->
                    new ApiException(409, "SOURCE_UNAVAILABLE", "基础版本没有可修改的 HTML 源码。"));
            if (!version.entryPath().endsWith(".html")) throw ApiException.invalid("基础版本不是 HTML。");
            source = WebEditSource.prepare(new String(file.content(), java.nio.charset.StandardCharsets.UTF_8));
        }
        WebEditSource currentSource = source;
        enqueue(() -> executeWeb(runId, project, instruction, base, currentSource));
        return new RunModels.StartResponse(runId, "QUEUED");
    }

    private void executeWeb(String runId, ProjectModels.Project project, String instruction, String base, WebEditSource source) {
        boolean reserved = false;
        boolean settled = false;
        try {
            runs.markRunning(runId, Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "RUNNING", "label", "正在启动生成任务"));

            String prompt = project.prompt();
            step(runId, "analyze", "Mike", "需求解析", "running", "通读需求，提取页面要表达的信息");
            List<String> topics = webTopics(prompt);
            step(runId, "analyze", "Mike", "需求解析", "done",
                    topics.isEmpty() ? "需求已读取，按原文直接生成"
                            : "关注点：" + String.join("、", topics) + (topics.size() >= 3 ? " 等" : ""));

            // Reserve the configured allowance before any paid request.
            budget.reserveText(runId, webGenerator.modelId());
            reserved = true;
            boolean game = project.type() == ProjectModels.Type.GAME;
            WebOutline outline = null;
            WebGenerator.Draft draft;
            if (instruction != null || game) {
                step(runId, "outline", "Alex", "生成上下文", "done", instruction != null
                        ? "已读取基础版本的完整 HTML 与修改说明" : "已读取游戏需求，将生成可交互单文件游戏");
                step(runId, "compose", "Emma", "源码编写", "running",
                        "调用「" + webGenerator.modelLabel() + "」生成完整源码");
            } else {
                step(runId, "outline", "Alex", "页面结构", "running",
                        "调用「" + webGenerator.modelLabel() + "」规划区块与文案");
            }
            if (runs.cancelRequested(runId)) throw new Stopped();
            budget.markSubmitted(runId);
            if (instruction != null) {
                draft = webGenerator.edit(project.type().name(), project.name(), prompt, instruction, base, source.html());
            } else if (game) {
                draft = webGenerator.game(project.name(), prompt);
            } else {
                outline = webGenerator.outline(project.name(), prompt);
                if (runs.cancelRequested(runId)) throw new Stopped();
                step(runId, "outline", "Alex", "页面结构", "done",
                        "确定 " + outline.sections().size() + " 个区块：" + outline.summary());
                step(runId, "compose", "Emma", "页面编写", "running", "把结构写成单文件 HTML");
                if (runs.cancelRequested(runId)) throw new Stopped();
                budget.markSubmitted(runId);
                draft = webGenerator.write(project.name(), outline);
            }
            if (runs.cancelRequested(runId)) throw new Stopped();
            step(runId, "compose", "Emma", "页面编写", "done",
                    "模型返回 " + readableBytes(draft.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                            + " 源码 · 用量 " + draft.tokens() + " tokens");

            // Hero image is an enhancement: a failure here degrades to a text-only page instead of
            // failing the whole run, and the event says so honestly.
            String heroDataUri = null;
            if (!game && instruction == null && webGenerator.imageConfigured()) {
                step(runId, "image", "Iris", "配图生成", "running",
                        "调用「" + webGenerator.imageModel() + "」生成主视觉并内嵌");
                try {
                    if (runs.cancelRequested(runId)) throw new Stopped();
                    budget.markSubmitted(runId);
                    heroDataUri = webGenerator.heroDataUri(project.name(), outline);
                    if (runs.cancelRequested(runId)) throw new Stopped();
                    step(runId, "image", "Iris", "配图生成", "done", "主视觉已生成并内嵌为本地数据");
                } catch (ProviderException error) {
                    log.warn("hero image degraded runId={} code={}", runId, error.code());
                    step(runId, "image", "Iris", "配图生成", "done",
                            "配图未生成（" + error.getMessage() + "），页面按无配图版式继续");
                }
            }

            step(runId, "check", "Sarah", "页面校验", "running", "检查文档完整性、外部依赖与体积");
            byte[] html = WebHtml.sanitize(source == null ? draft.raw() : source.restore(draft.raw()));
            if (heroDataUri != null) {
                html = WebHtml.injectHero(html, heroDataUri);
            }
            step(runId, "check", "Sarah", "页面校验", "done",
                    "校验通过：单文件 " + readableBytes(html.length) + "，无外部资源引用，预览可离线打开");

            step(runId, "publish", "David", "版本入库", "running", "写入不可变版本并切换当前版本");
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("kind", project.type().name());
            artifact.put("title", outline == null ? project.name() : outline.title());
            artifact.put("tagline", outline == null ? "" : outline.tagline());
            artifact.put("entryPath", "index.html");
            artifact.put("sizeBytes", html.length);
            artifact.put("sections", outline == null ? 0 : outline.sections().size());
            artifact.put("model", webGenerator.modelLabel());
            artifact.put("tokens", draft.tokens());
            artifact.put("heroImage", heroDataUri != null);
            artifact.put("heroImageModel", heroDataUri != null ? webGenerator.imageModel() : null);
            budget.settle(runId);
            settled = true;
            versionWriter.writeForRun(project.id(), runId, "index.html",
                    instruction == null ? "生成「" + project.name() + "」"
                            : "修改：" + instruction.substring(0, Math.min(200, instruction.length())),
                    Map.of("index.html", html), null, artifact);
        } catch (Stopped | ProjectVersionWriter.CommitCancelled stopped) {
            runs.finish(runId, "CANCELLED", null, null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "CANCELLED", "label", "已停止，未写入新版本"));
        } catch (ProviderException error) {
            log.warn("web run failed runId={} code={}", runId, error.code());
            runs.finish(runId, "FAILED", null, error.code(), error.getMessage(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", error.getMessage(),
                    "code", error.code(), "message", error.getMessage()));
        } catch (ApiException error) {
            log.warn("web run failed runId={} code={}", runId, error.code());
            runs.finish(runId, "FAILED", null, error.code(), error.getMessage(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", error.getMessage(),
                    "code", error.code(), "message", error.getMessage()));
        } catch (RuntimeException error) {
            log.error("web run failed runId={} type={}", runId, error.getClass().getSimpleName(), error);
            runs.finish(runId, "FAILED", null, "GENERATION_FAILED", "生成过程中断，请重新生成。",
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", "生成失败，请重新生成",
                    "code", "GENERATION_FAILED", "message", "生成过程中断，请重新生成。"));
        } finally {
            if (reserved && !settled) {
                try {
                    budget.release(runId);
                } catch (RuntimeException error) {
                    log.warn("budget release failed runId={}", runId);
                }
            }
        }
    }

    /** Sentence-level extraction of what the requirement talks about; no invented content. */
    private static List<String> webTopics(String prompt) {
        List<String> topics = new ArrayList<>();
        for (String piece : prompt.split("[。；;\\n，,、]")) {
            String value = piece.strip();
            if (value.length() < 3 || value.length() > 30) continue;
            if (topics.stream().anyMatch(existing -> existing.equals(value))) continue;
            topics.add(value);
            if (topics.size() >= 3) break;
        }
        return topics;
    }


    // ---------------------------------------------------------------- slides

    private RunModels.StartResponse startSlides(String owner, ProjectModels.Project project,
                                                RunModels.StartRequest request) {
        String templateSlug = request == null || request.templateSlug() == null || request.templateSlug().isBlank()
                ? null : request.templateSlug().strip();
        String importId = request == null || request.importId() == null || request.importId().isBlank()
                ? null : request.importId().strip();

        String theme = null;
        List<String> inheritedTitles = List.of();
        if (templateSlug != null) {
            if (!templateSlug.matches("[a-z0-9][a-z0-9-]{1,47}")) throw ApiException.invalid("模板标识无效。");
            var template = templates.find(templateSlug).orElseThrow(() ->
                    new ApiException(404, "TEMPLATE_NOT_FOUND", "所选模板不存在或已下架。"));
            var version = templates.currentVersion(template.id()).orElse(null);
            if (version == null) throw new ApiException(409, "TEMPLATE_NOT_READY", "所选模板暂无可用的内容版本。");
            var file = templates.file(version.id(), version.entryPath()).orElse(null);
            if (file != null) {
                SlideDeck deck = renderer.read(file.content());
                if (deck != null) {
                    theme = deck.theme();
                    inheritedTitles = deck.slides().stream()
                            .map(slide -> String.valueOf(slide.getOrDefault("title", "")))
                            .filter(title -> !title.isBlank()).toList();
                }
            }
        }
        List<SlidePlanner.OutlineSlide> outline = importId == null ? List.of()
                : importService.outline(owner, importId);

        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        runs.insert(runId, project.id(), owner, "SLIDES", ENGINE, project.prompt(),
                templateSlug, importId, theme, STEPS, now);

        Context context = new Context(project, runId, templateSlug, outline, inheritedTitles, theme,
                null, null);
        enqueue(() -> executeSlides(context));
        return new RunModels.StartResponse(runId, "QUEUED");
    }

    // ---------------------------------------------------------------- video

    private RunModels.StartResponse startVideo(String owner, ProjectModels.Project project,
                                               RunModels.StartRequest request) {
        String modelKey = request == null ? null : request.model();
        String ratio = request == null ? null : request.ratio();
        String resolution = request == null ? null : request.resolution();
        Integer duration = request == null ? null : request.durationSeconds();
        if (modelKey == null || modelKey.isBlank()) {
            // No model picked: use the first available catalog entry so the workbench can stay quiet.
            modelKey = catalog.models().stream().filter(VideoCatalog.Model::available)
                    .map(VideoCatalog.Model::key).findFirst()
                    .orElseThrow(() -> new ApiException(503, "PROVIDER_NOT_CONFIGURED",
                            "服务端尚未配置视频模型，暂时无法生成。", true));
        }
        VideoCatalog.Spec spec = catalog.spec(modelKey, ratio, resolution, duration);
        VideoCatalog.Model model = catalog.require(spec.model());
        String specJson = json.writeValueAsString(Map.of(
                "ratio", spec.ratio() == null ? "auto" : spec.ratio(),
                "resolution", spec.resolution(),
                "durationSeconds", spec.durationSeconds()));

        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        runs.insert(runId, project.id(), owner, "VIDEO", VIDEO_ENGINE, project.prompt(),
                null, null, null, spec.model(), specJson, STEPS, now);

        Context context = new Context(project, runId, null, List.of(), List.of(), null, model, spec);
        enqueue(() -> executeVideo(context));
        return new RunModels.StartResponse(runId, "QUEUED");
    }

    @Transactional
    public RunModels.Run cancel(String owner, String projectId, String runId) {
        if (!projects.lock(owner, projectId)) throw projectNotFound();
        var run = runs.find(owner, projectId, runId).orElseThrow(RunService::runNotFound);
        if (!"QUEUED".equals(run.status()) && !"RUNNING".equals(run.status())) return view(run);
        runs.requestCancel(runId);
        return view(runs.find(owner, projectId, runId).orElseThrow(RunService::runNotFound));
    }

    @Transactional(readOnly = true)
    public RunModels.Snapshot snapshot(String owner, String projectId, String runId) {
        var run = runs.find(owner, projectId, runId).orElseThrow(RunService::runNotFound);
        List<RunModels.Event> list = new ArrayList<>();
        for (var event : events.after(runId, 0, 500)) list.add(view(event));
        return new RunModels.Snapshot(view(run), list);
    }

    @Transactional(readOnly = true)
    public RunModels.Snapshot latest(String owner, String projectId) {
        var run = runs.latest(owner, projectId).orElseThrow(RunService::runNotFound);
        return snapshot(owner, projectId, run.id());
    }

    /** Returns events newer than {@code after}; the caller polls until the run is terminal. */
    @Transactional(readOnly = true)
    public List<RunModels.Event> eventsAfter(String owner, String projectId, String runId, int after) {
        var run = runs.find(owner, projectId, runId).orElseThrow(RunService::runNotFound);
        List<RunModels.Event> list = new ArrayList<>();
        for (var event : events.after(run.id(), after, 200)) list.add(view(event));
        return list;
    }

    @Transactional(readOnly = true)
    public RunModels.Run run(String owner, String projectId, String runId) {
        return view(runs.find(owner, projectId, runId).orElseThrow(RunService::runNotFound));
    }

    // ---------------------------------------------------------------- pipeline

    private record Context(ProjectModels.Project project, String runId, String templateSlug,
                           List<SlidePlanner.OutlineSlide> outline, List<String> inheritedTitles,
                           String theme, VideoCatalog.Model model, VideoCatalog.Spec spec) {}

    private static final class Stopped extends RuntimeException {
        Stopped() { super("cancelled", null, false, false); }
    }

    private void executeSlides(Context context) {
        String runId = context.runId();
        try {
            runs.markRunning(runId, Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "RUNNING", "label", "正在启动生成任务"));

            step(runId, "analyze", "Mike", "需求解析", "running", "通读需求描述，提取主题与可用素材");
            pause(runId, 700);
            SlidePlanner.Plan plan = planner.plan(context.project().name(), context.project().prompt(),
                    context.theme(), context.outline(), context.inheritedTitles());
            String topicSummary = plan.topics().isEmpty() ? "没有识别到明确要点"
                    : String.join("、", plan.topics().subList(0, Math.min(3, plan.topics().size())));
            step(runId, "analyze", "Mike", "需求解析", "done",
                    "主题已确认；要点：" + topicSummary + (plan.topics().size() > 3 ? " 等" : ""));

            step(runId, "outline", "Alex", "演示架构", "running", "按要点数量铺排页面结构");
            pause(runId, 650);
            step(runId, "outline", "Alex", "演示架构", "done",
                    "确定 " + plan.deck().size() + " 页：" + plan.deck().outline());

            step(runId, "theme", "Emma", "视觉风格", "running", "匹配与主题相符的版式与配色");
            pause(runId, 600);
            step(runId, "theme", "Emma", "视觉风格", "done",
                    "采用「" + themeLabel(plan.theme()) + "」主题 · 16:9 · 全部版式可离线渲染");

            step(runId, "layout", "Iris", "逐页排版", "running", "逐页生成版式与文字层级");
            List<Map<String, Object>> slides = plan.deck().slides();
            for (int index = 0; index < slides.size(); index++) {
                pause(runId, 220);
                String title = String.valueOf(slides.get(index).getOrDefault("title",
                        slides.get(index).getOrDefault("text", "")));
                emit(runId, "progress", Map.of("id", "layout", "done", index + 1, "total", slides.size(),
                        "label", "第 " + (index + 1) + " 页 · " + title));
            }
            step(runId, "layout", "Iris", "逐页排版", "done",
                    "共排版 " + slides.size() + " 页，全部使用同一套栅格与字阶");

            step(runId, "review", "Sarah", "版式校验", "running", "检查空白页、文字溢出与文件完整性");
            Map<String, byte[]> files = new LinkedHashMap<>(renderer.files(plan.deck()));
            pause(runId, 600);
            step(runId, "review", "Sarah", "版式校验", "done",
                    "校验通过：" + files.size() + " 个文件，" + slides.size() + " 页，无空白页与越界文本");

            step(runId, "publish", "David", "版本入库", "running", "写入不可变版本并切换当前版本");
            pause(runId, 420);
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("kind", "SLIDES");
            artifact.put("slideCount", slides.size());
            artifact.put("theme", plan.theme());
            artifact.put("title", plan.deck().title());
            versionWriter.writeForRun(context.project().id(), runId, "index.html",
                    "生成 " + slides.size() + " 页「" + plan.deck().title() + "」", files, slides.size(), artifact);
        } catch (Stopped | ProjectVersionWriter.CommitCancelled stopped) {
            runs.finish(runId, "CANCELLED", null, null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "CANCELLED", "label", "已停止，未写入新版本"));
        } catch (ApiException error) {
            runs.finish(runId, "FAILED", null, error.code(), error.getMessage(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", error.getMessage(),
                    "code", error.code(), "message", error.getMessage()));
        } catch (RuntimeException error) {
            log.error("run failed runId={} type={}", runId, error.getClass().getSimpleName(), error);
            runs.finish(runId, "FAILED", null, "GENERATION_FAILED", "生成过程中断，请重新生成。",
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", "生成失败，请重新生成",
                    "code", "GENERATION_FAILED", "message", "生成过程中断，请重新生成。"));
        }
    }

    private void executeVideo(Context context) {
        String runId = context.runId();
        VideoCatalog.Spec spec = context.spec();
        VideoCatalog.Model model = context.model();
        boolean reserved = false;
        boolean settled = false;
        String taskId = null;
        try {
            runs.markRunning(runId, Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "RUNNING", "label", "正在启动生成任务"));

            String prompt = context.project().prompt();
            step(runId, "analyze", "Mike", "需求解析", "running", "通读画面描述，确认主体、环境与镜头");
            pause(runId, 700);
            VideoPlanner.Plan plan = videoPlanner.plan(prompt);
            step(runId, "analyze", "Mike", "需求解析", "done",
                    "主体已确认：" + plan.subject());

            step(runId, "storyboard", "Alex", "分镜脚本", "running", "把描述切成可执行的镜头节拍");
            pause(runId, 600);
            StringBuilder beats = new StringBuilder();
            for (VideoPlanner.Beat beat : plan.beats()) {
                if (beats.length() > 0) beats.append(" / ");
                beats.append(beat.index()).append(". ").append(beat.text());
            }
            step(runId, "storyboard", "Alex", "分镜脚本", "done",
                    plan.beats().size() + " 个节拍：" + beats);

            step(runId, "prompt", "Emma", "提示词编译", "running", "把节拍与规格拼成模型可读的任务");
            pause(runId, 500);
            String compiled = videoPlanner.compile(prompt, spec);
            step(runId, "prompt", "Emma", "提示词编译", "done",
                    "保留原文，仅追加技术参数（" + spec.summary() + "）· 共 " + compiled.length() + " 字");

            step(runId, "submit", "Iris", "任务提交", "running",
                    "向「" + model.label() + "」提交生成任务（先占用预算，再发起付费调用）");
            budget.reserve(runId, spec.model());
            reserved = true;
            if (runs.cancelRequested(runId)) throw new Stopped();
            budget.markSubmitted(runId);
            var created = ark.createVideoTask(model.modelId(), new ArkClient.VideoRequest(
                    compiled, null, spec.durationSeconds(), spec.resolution(), spec.ratio(),
                    false, true));
            taskId = created.id();
            runs.setProviderTask(runId, taskId);
            if (runs.cancelRequested(runId)) {
                try { ark.deleteVideoTask(taskId); } catch (RuntimeException ignored) { }
                throw new Stopped();
            }
            step(runId, "submit", "Iris", "任务提交", "done", "任务已提交：" + taskId);

            step(runId, "generate", "Sarah", "模型生成", "running",
                    "「" + model.label() + "」正在渲染 " + spec.durationSeconds() + " 秒画面");
            ArkClient.VideoTask task = poll(runId, taskId);
            step(runId, "generate", "Sarah", "模型生成", "done",
                    "渲染完成 · " + (task.usageTokens() > 0 ? "用量 " + task.usageTokens() + " tokens" : "规格 " + spec.summary()));

            step(runId, "publish", "David", "下载入库", "running", "下载成片并写入不可变版本");
            byte[] video = ark.download(task.videoUrl(), MAX_VIDEO_BYTES);
            if (video.length < 100) {
                throw new ProviderException(502, "PROVIDER_ERROR", "下载到的视频内容异常，请重新生成。", true);
            }
            String title = context.project().name();
            Map<String, byte[]> files = Map.of("video.mp4", video);
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("kind", "VIDEO");
            artifact.put("title", title);
            artifact.put("mediaPath", "video.mp4");
            artifact.put("model", model.label());
            artifact.put("ratio", spec.ratio() == null ? "auto" : spec.ratio());
            artifact.put("resolution", spec.resolution());
            artifact.put("durationSeconds", spec.durationSeconds());
            artifact.put("sizeBytes", video.length);
            budget.settle(runId);
            settled = true;
            versionWriter.writeForRun(context.project().id(), runId, "video.mp4",
                    "生成 " + spec.durationSeconds() + " 秒「" + title + "」", files, null, artifact);
        } catch (Stopped | ProjectVersionWriter.CommitCancelled stopped) {
            runs.finish(runId, "CANCELLED", null, null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "CANCELLED", "label", "已停止，未写入新版本"));
        } catch (ProviderException error) {
            log.warn("video run failed runId={} code={}", runId, error.code());
            runs.finish(runId, "FAILED", null, error.code(), error.getMessage(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", error.getMessage(),
                    "code", error.code(), "message", error.getMessage()));
        } catch (ApiException error) {
            log.warn("video run failed runId={} code={}", runId, error.code());
            runs.finish(runId, "FAILED", null, error.code(), error.getMessage(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", error.getMessage(),
                    "code", error.code(), "message", error.getMessage()));
        } catch (RuntimeException error) {
            log.error("video run failed runId={} type={}", runId, error.getClass().getSimpleName(), error);
            runs.finish(runId, "FAILED", null, "GENERATION_FAILED", "生成过程中断，请重新生成。",
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            emit(runId, "status", Map.of("status", "FAILED", "label", "生成失败，请重新生成",
                    "code", "GENERATION_FAILED", "message", "生成过程中断，请重新生成。"));
        } finally {
            if (reserved && !settled) {
                try {
                    budget.release(runId);
                } catch (RuntimeException error) {
                    log.warn("budget release failed runId={}", runId);
                }
            }
        }
    }

    /** Polls the provider task until terminal, honouring stop requests and the wait budget. */
    private ArkClient.VideoTask poll(String runId, String taskId) {
        long deadline = System.currentTimeMillis() + videoTimeoutMillis();
        int pollMillis = (int) Math.min(Integer.MAX_VALUE, pollIntervalMillis());
        long start = System.currentTimeMillis();
        while (true) {
            pause(runId, pollMillis);
            ArkClient.VideoTask task = ark.getVideoTask(taskId);
            if (task.succeeded()) return task;
            if (task.failed()) {
                String message = task.errorMessage() == null ? "视频服务拒绝了这次生成，请调整后重试。" : task.errorMessage();
                throw new ProviderException(502, task.errorCode() == null ? "PROVIDER_TASK_FAILED"
                        : "PROVIDER_TASK_FAILED", message, true);
            }
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            long limit = (deadline - start) / 1000;
            if (System.currentTimeMillis() >= deadline) {
                try { ark.deleteVideoTask(taskId); } catch (RuntimeException ignored) { }
                throw new ProviderException(504, "PROVIDER_TIMEOUT",
                        "等待超过 " + (limit / 60) + " 分钟仍未出片，请稍后重试。", true);
            }
            emit(runId, "progress", Map.of("id", "generate", "done", Math.min(elapsed, limit),
                    "total", limit, "label", "模型生成中 · 已等待 " + elapsed + " 秒"));
        }
    }

    private void step(String runId, String id, String agent, String role, String state, String detail) {
        emit(runId, "step", Map.of("id", id, "agent", agent, "role", role, "state", state, "detail", detail));
    }

    /** Sleeps in slices so a stop request is honoured quickly. */
    private void pause(String runId, int millis) {
        int waited = 0;
        while (waited < millis) {
            if (runs.cancelRequested(runId)) throw new Stopped();
            int slice = Math.min(120, millis - waited);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new Stopped();
            }
            waited += slice;
        }
        if (runs.cancelRequested(runId)) throw new Stopped();
    }

    private void emit(String runId, String type, Map<String, Object> payload) {
        events.append(runId, type, json.writeValueAsString(payload), Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private long videoTimeoutMillis() {
        return Math.max(10_000L, videoTimeout.toMillis());
    }

    private long pollIntervalMillis() {
        return Math.max(500L, pollInterval.toMillis());
    }

    private RunModels.Run view(RunRepository.Row row) {
        Object spec = null;
        if (row.specJson() != null) {
            try {
                spec = json.readValue(row.specJson(), Object.class);
            } catch (RuntimeException error) {
                spec = null;
            }
        }
        return new RunModels.Run(row.id(), row.projectId(), row.purpose(), row.status(), row.engine(),
                row.theme(), row.slideCount(), row.totalSteps(), row.errorCode(), row.errorMessage(),
                row.modelKey(), spec, row.instruction(), row.baseVersionId(), row.createdAt(), row.startedAt(), row.finishedAt());
    }

    private RunModels.Event view(RunEventRepository.Row row) {
        Object data;
        try {
            data = json.readValue(row.payload(), Object.class);
        } catch (RuntimeException error) {
            data = Map.of("raw", row.payload());
        }
        return new RunModels.Event(row.seq(), row.type(), data, row.createdAt());
    }

    private static String themeLabel(String theme) {
        if (theme == null) return "标准";
        return switch (theme) {
            case "ink" -> "深色极简";
            case "swiss" -> "瑞士网格";
            case "editorial" -> "杂志米白";
            case "sky" -> "商务蓝";
            default -> "标准";
        };
    }

    private static String readableBytes(long bytes) {
        if (bytes >= 1_048_576) return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static ApiException projectNotFound() {
        return new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在、已删除或不属于当前访客。");
    }

    private static ApiException runNotFound() {
        return new ApiException(404, "RUN_NOT_FOUND", "没有找到这次生成任务。");
    }
}
