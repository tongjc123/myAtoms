package com.deepwistom.atoms.preview;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.project.ProjectVersionRepository;
import com.deepwistom.atoms.session.SessionService;
import com.deepwistom.atoms.template.MediaTypes;
import com.deepwistom.atoms.template.TemplateRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only preview surface for templates and cloned projects. Responses are locked down: no
 * network egress, no forms, no top navigation, and only the workbench itself may frame them.
 */
@RestController
public class PreviewController {
    private static final String CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'self'";

    private final PreviewService previews;
    private final TemplateRepository templates;
    private final ProjectVersionRepository versions;
    private final SessionService sessions;

    public PreviewController(PreviewService previews, TemplateRepository templates,
                             ProjectVersionRepository versions, SessionService sessions) {
        this.previews = previews;
        this.templates = templates;
        this.versions = versions;
        this.sessions = sessions;
    }

    @PostMapping("/api/projects/{id}/preview-sessions")
    public PreviewService.Session create(HttpServletRequest request, @PathVariable String id,
                                         @RequestBody(required = false) PreviewService.CreateRequest body) {
        return previews.create(sessions.current(request).id(), id, body == null ? null : body.versionId());
    }

    @GetMapping({"/api/template-previews/{slug}", "/api/template-previews/{slug}/{*path}"})
    public ResponseEntity<byte[]> template(@PathVariable String slug,
                                           @PathVariable(required = false) String path) {
        var template = templates.find(slug).orElseThrow(() -> notFound("模板不存在或已下架。"));
        if (template.versionId() == null) throw notFound("模板暂无可预览的内容。");
        String safe = safePath(path, template.entryPath());
        var file = templates.file(template.versionId(), safe)
                .orElseThrow(() -> notFound("模板文件不存在。"));
        return respond(file.contentType(), file.content(), CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic().getHeaderValue());
    }

    @GetMapping({"/api/previews/{token}", "/api/previews/{token}/{*path}"})
    public ResponseEntity<byte[]> project(HttpServletRequest request, @PathVariable String token,
                                          @PathVariable(required = false) String path) {
        var access = previews.resolve(token);
        String safe = safePath(path, access.entryPath());
        var file = versions.file(access.versionId(), safe)
                .orElseThrow(() -> notFound("该版本中不存在此文件。"));
        if (file.contentType().startsWith("video/") || file.contentType().startsWith("audio/")) {
            return media(request.getHeader("Range"), file.contentType(), file.content());
        }
        return respond(file.contentType(), file.content(), CacheControl.noStore().getHeaderValue());
    }

    /**
     * Serves media with byte-range support so the player can seek. Without Range the whole body
     * is returned; an unsatisfiable range is a plain 416.
     */
    private static ResponseEntity<byte[]> media(String range, String contentType, byte[] content) {
        int total = content.length;
        if (range != null && range.startsWith("bytes=")) {
            String[] pair = range.substring("bytes=".length()).split("-", -1);
            try {
                long start = pair[0].isEmpty() ? 0 : Long.parseLong(pair[0]);
                long end = pair.length < 2 || pair[1].isEmpty() ? total - 1L : Long.parseLong(pair[1]);
                if (start >= total || start < 0 || end < start) {
                    return ResponseEntity.status(416)
                            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total).build();
                }
                end = Math.min(end, total - 1L);
                byte[] slice = java.util.Arrays.copyOfRange(content, (int) start, (int) end + 1);
                return ResponseEntity.status(206)
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Cross-Origin-Resource-Policy", "same-origin")
                        .body(slice);
            } catch (NumberFormatException error) {
                return ResponseEntity.status(416)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total).build();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .body(content);
    }

    private static ResponseEntity<byte[]> respond(String contentType, byte[] content, String cacheControl) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .header("Content-Security-Policy", CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .body(content);
    }

    private static String safePath(String raw, String fallback) {
        try {
            return MediaTypes.normalize(raw, fallback);
        } catch (IllegalArgumentException error) {
            throw notFound("预览路径无效。");
        }
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "PREVIEW_NOT_FOUND", message);
    }
}
