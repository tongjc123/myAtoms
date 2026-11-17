package com.deepwistom.atoms.video;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.provider.ProviderProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The video model catalog and the product specification guard. Model entries come from server
 * configuration in "key|label|description|modelId|badge" form; an entry whose model id is blank
 * is listed as unavailable so the workbench can show the slot without pretending it can run.
 */
@Service
public class VideoCatalog {
    private static final Set<String> RATIOS = Set.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final Set<String> RESOLUTIONS = Set.of("480p", "720p", "1080p");
    public static final int MIN_DURATION = 4;
    public static final int MAX_DURATION = 30;

    public record Model(String key, String label, String description, String modelId, String badge,
                        boolean available) {}

    public record Spec(String model, String ratio, String resolution, int durationSeconds) {
        public String summary() {
            String size = ratio == null ? "自动画幅" : ratio;
            return size + " · " + resolution + " · " + durationSeconds + " 秒";
        }
    }

    private final ProviderProperties properties;

    public VideoCatalog(ProviderProperties properties) {
        this.properties = properties;
    }

    public List<Model> models() {
        List<Model> result = new ArrayList<>();
        for (String entry : properties.videoModels()) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 4 || parts[0].isBlank()) continue;
            String modelId = parts[3].trim();
            String badge = parts.length > 4 ? parts[4].trim() : "";
            result.add(new Model(parts[0].trim(), parts[1].trim(), parts[2].trim(), modelId,
                    badge.isEmpty() ? null : badge, !modelId.isBlank()));
        }
        return result;
    }

    public Model require(String key) {
        if (key == null || key.isBlank() || !key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw ApiException.invalid("模型选择无效。");
        }
        Model found = models().stream().filter(model -> model.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.invalid("所选模型不存在。"));
        if (!found.available()) {
            throw new ApiException(409, "VIDEO_MODEL_NOT_CONFIGURED",
                    "「" + found.label() + "」还没有配置模型编号，暂不可选。", true);
        }
        return found;
    }

    /**
     * Validates the user-facing specification. Ratio may be null ("智能": the provider adapts to
     * the prompt); resolution and duration fall back to the configured defaults.
     */
    public Spec spec(String modelKey, String ratio, String resolution, Integer durationSeconds) {
        Model model = require(modelKey);
        String size = ratio == null || ratio.isBlank() ? null : ratio.trim().toLowerCase(Locale.ROOT);
        if (size != null && !RATIOS.contains(size)) {
            throw ApiException.invalid("画幅比例无效，可选 " + String.join("、", RATIOS) + "。");
        }
        String clarity = resolution == null || resolution.isBlank()
                ? properties.defaultResolution() : resolution.trim().toLowerCase(Locale.ROOT);
        if (!RESOLUTIONS.contains(clarity)) {
            throw ApiException.invalid("分辨率无效，可选 " + String.join("、", RESOLUTIONS) + "。");
        }
        int seconds = durationSeconds == null ? properties.defaultDurationSeconds() : durationSeconds;
        if (seconds < MIN_DURATION || seconds > MAX_DURATION) {
            throw ApiException.invalid("时长需要在 " + MIN_DURATION + "–" + MAX_DURATION + " 秒之间。");
        }
        return new Spec(model.key(), size, clarity, seconds);
    }
}
