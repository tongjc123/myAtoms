package com.deepwistom.atoms.video;

import com.deepwistom.atoms.provider.ProviderProperties;
import com.deepwistom.atoms.provider.ProviderStatusService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only video workbench options: which models exist, which of them can actually run right
 * now, and the default specification. Availability comes from configuration, never from a guess.
 */
@RestController
@RequestMapping("/api/video")
public class VideoController {
    private final VideoCatalog catalog;
    private final ProviderStatusService status;
    private final ProviderProperties properties;

    public VideoController(VideoCatalog catalog, ProviderStatusService status,
                           ProviderProperties properties) {
        this.catalog = catalog;
        this.status = status;
        this.properties = properties;
    }

    public record Option(String key, String label, String description, String badge,
                         boolean available) {}

    public record Options(List<Option> models, String defaultModel, String defaultRatio,
                          String defaultResolution, int defaultDurationSeconds,
                          int minDurationSeconds, int maxDurationSeconds,
                          List<String> ratios, List<String> resolutions,
                          boolean providerConfigured, String providerState, String providerDetail) {}

    @GetMapping("/options")
    public ResponseEntity<Options> options() {
        List<Option> models = catalog.models().stream()
                .map(model -> new Option(model.key(), model.label(), model.description(),
                        model.badge(), model.available()))
                .toList();
        String defaultModel = models.stream().filter(Option::available).map(Option::key)
                .findFirst().orElse(null);
        var probe = status.current();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Options(
                models, defaultModel, null, properties.defaultResolution(),
                properties.defaultDurationSeconds(), VideoCatalog.MIN_DURATION, VideoCatalog.MAX_DURATION,
                List.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16"), List.of("480p", "720p", "1080p"),
                probe.configured(), probe.state(), probe.detail()));
    }
}
