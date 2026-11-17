package com.deepwistom.atoms.provider;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-side Volcengine Ark (video) configuration. The API key is only read by the API process and
 * is never returned to browsers, written to logs or copied into generated projects.
 *
 * <p>{@code videoModels} is a catalog of "key|label|description|modelId|badge" entries. An entry
 * without a model id is shown as unavailable instead of silently pointing at a guessed identifier.
 * Text and image generation moved to {@link QwenProperties}.
 */
@ConfigurationProperties("atoms.provider")
public record ProviderProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://ark.cn-beijing.volces.com/api/v3") String baseUrl,
        @DefaultValue("") String videoModel,
        @DefaultValue("5") int defaultDurationSeconds,
        @DefaultValue("720p") String defaultResolution,
        @DefaultValue("16:9") String defaultRatio,
        @DefaultValue("false") boolean generateAudio,
        @DefaultValue("10m") Duration videoTimeout,
        @DefaultValue("3s") Duration pollInterval,
        @DefaultValue("false") boolean allowPrivateMediaHosts,
        @DefaultValue List<String> videoModels,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout) {

    public boolean configured() {
        return !apiKey.isBlank() && !videoModel.isBlank();
    }
}
