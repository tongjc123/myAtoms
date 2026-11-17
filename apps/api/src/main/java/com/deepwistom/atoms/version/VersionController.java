package com.deepwistom.atoms.version;

import com.deepwistom.atoms.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/versions")
public class VersionController {
    private final VersionService versions;
    private final SessionService sessions;

    public VersionController(VersionService versions, SessionService sessions) {
        this.versions = versions;
        this.sessions = sessions;
    }

    @GetMapping
    public VersionModels.Page list(HttpServletRequest request, @PathVariable String id,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int pageSize) {
        return versions.list(sessions.current(request).id(), id, page, pageSize);
    }

    @PostMapping("/{versionId}/restore")
    public ResponseEntity<String> restore(HttpServletRequest request, @PathVariable String id,
                                          @PathVariable String versionId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody VersionModels.RestoreRequest body) {
        var result = versions.restore(sessions.current(request).id(), id, versionId, key, body);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }

    @GetMapping("/{versionId}/source.zip")
    public ResponseEntity<byte[]> sourceZip(HttpServletRequest request, @PathVariable String id,
                                            @PathVariable String versionId) {
        byte[] zip = versions.sourceZip(sessions.current(request).id(), id, versionId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"source-" + versionId + ".zip\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff").body(zip);
    }
}
