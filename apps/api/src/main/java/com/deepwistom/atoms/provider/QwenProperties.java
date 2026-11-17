package com.deepwistom.atoms.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Qianwen (通义千问) gateway configuration for text and image generation. The key is read only by
 * the API process and is never returned to browsers, written to logs or copied into generated
 * projects. Text and image share one gateway (DashScope-style endpoints); video stays on Ark.
 */
@ConfigurationProperties("atoms.qwen")
public record QwenProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://cn-beijing.maas.qianwenaiapi.com/api/v1") String baseUrl,
        @DefaultValue("") String textModel,
        @DefaultValue("") String textModelName,
        @DefaultValue("") String imageModel,
        @DefaultValue("120s") Duration textTimeout,
        @DefaultValue("120s") Duration imageTimeout,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("false") boolean allowPrivateMediaHosts) {

    public boolean textConfigured() {
        return !apiKey.isBlank() && !textModel.isBlank();
    }

    public boolean imageConfigured() {
        return !apiKey.isBlank() && !imageModel.isBlank();
    }

    /** Label shown in the UI; empty when no model id is configured, falling back to the id. */
    public String textLabel() {
        if (textModel.isBlank()) return "";
        return textModelName.isBlank() ? textModel : textModelName;
    }
}
