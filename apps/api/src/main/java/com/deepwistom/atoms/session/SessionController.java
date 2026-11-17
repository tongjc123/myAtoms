package com.deepwistom.atoms.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final SessionService service;
    public SessionController(SessionService service) { this.service = service; }

    @PostMapping("/api/session")
    public ResponseEntity<SessionService.SessionResponse> create(HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.bootstrap(request, response));
    }

    @GetMapping("/api/session")
    public ResponseEntity<SessionService.SessionResponse> current(HttpServletRequest request) {
        var owner = service.current(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SessionService.SessionResponse(owner.id(), owner.csrfToken(), owner.expiresAt()));
    }
}
