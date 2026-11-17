package com.deepwistom.atoms.provider;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only provider readiness for the workbench. Never returns the key, request payloads or
 * provider internals; the response only states whether the video model is usable right now.
 */
@RestController
@RequestMapping("/api/system/provider")
public class ProviderController {
    private final ProviderStatusService status;

    public ProviderController(ProviderStatusService status) { this.status = status; }

    @GetMapping
    public ResponseEntity<ProviderStatusService.ProviderStatus> current() {
        return noStore(status.current());
    }

    /** Forces a fresh probe, used after the operator activates a model in the provider console. */
    @PostMapping("/refresh")
    public ResponseEntity<ProviderStatusService.ProviderStatus> refresh() {
        return noStore(status.refresh());
    }

    private static ResponseEntity<ProviderStatusService.ProviderStatus> noStore(
            ProviderStatusService.ProviderStatus body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
