package com.deepwistom.atoms.template;

import com.deepwistom.atoms.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.deepwistom.atoms.template.TemplateModels.*;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateService templates;
    private final SessionService sessions;

    public TemplateController(TemplateService templates, SessionService sessions) {
        this.templates = templates;
        this.sessions = sessions;
    }

    @GetMapping
    public TemplatePage list(@RequestParam(required = false) String type) {
        return templates.list(type);
    }

    @GetMapping("/{slug}")
    public Item get(@PathVariable String slug) {
        return templates.get(slug);
    }

    @PostMapping(value = "/{slug}/clone", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> clone(HttpServletRequest request, @PathVariable String slug,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) CloneRequest body) {
        var result = templates.clone(sessions.current(request).id(), slug, key, body);
        return ResponseEntity.status(result.status())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
