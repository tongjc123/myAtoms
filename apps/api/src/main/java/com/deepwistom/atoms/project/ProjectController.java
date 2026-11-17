package com.deepwistom.atoms.project;

import com.deepwistom.atoms.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.deepwistom.atoms.project.ProjectModels.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projects;
    private final SessionService sessions;
    public ProjectController(ProjectService projects, SessionService sessions) {
        this.projects = projects;
        this.sessions = sessions;
    }

    @GetMapping
    public ProjectPage list(HttpServletRequest request,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int pageSize) {
        return projects.list(sessions.current(request).id(), page, pageSize);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(HttpServletRequest request, @PathVariable String id) {
        return new ProjectResponse(projects.get(sessions.current(request).id(), id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CreateRequest body) {
        return response(projects.create(sessions.current(request).id(), key, body));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rename(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody RenameRequest body) {
        return response(projects.rename(sessions.current(request).id(), id, key, body));
    }

    @DeleteMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> delete(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody DeleteRequest body) {
        return response(projects.delete(sessions.current(request).id(), id, key, body));
    }

    private ResponseEntity<String> response(MutationResponse response) {
        var builder = ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore());
        return response.status() == 204 ? builder.build()
                : builder.contentType(MediaType.APPLICATION_JSON).body(response.body());
    }
}
