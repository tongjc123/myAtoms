package com.deepwistom.atoms.provider;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Reports whether the configured models can actually be used. The checks never create a billable
 * task and never expose the API key; results are cached briefly to avoid probing on every page load.
 * Video is served by Volcengine Ark, text and image by the Qianwen gateway.
 */
@Service
public class ProviderStatusService {
    private static final Duration CACHE = Duration.ofMinutes(2);

    public record ProviderStatus(
            boolean configured,
            String provider,
            String endpointHost,
            String videoModel,
            String state,
            String detail,
            Instant checkedAt,
            int defaultDurationSeconds,
            String defaultResolution,
            String textModel,
            String textState,
            String textDetail,
            String imageModel,
            String imageState,
            String imageDetail) {}

    private final ArkClient ark;
    private final QwenClient qwen;
    private final ProviderProperties properties;
    private final QwenProperties qwenProperties;
    private volatile ProviderStatus cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public ProviderStatusService(ArkClient ark, QwenClient qwen,
                                 ProviderProperties properties, QwenProperties qwenProperties) {
        this.ark = ark;
        this.qwen = qwen;
        this.properties = properties;
        this.qwenProperties = qwenProperties;
    }

    public ProviderStatus current() {
        ProviderStatus snapshot = cached;
        if (snapshot != null && Instant.now().isBefore(cachedAt.plus(CACHE))) return snapshot;
        return refresh();
    }

    public ProviderStatus refresh() {
        String state;
        String detail;
        if (!properties.configured()) {
            state = "NOT_CONFIGURED";
            detail = "服务端尚未配置视频模型密钥，生成能力保持关闭。";
        } else {
            ArkClient.ModelProbe probe = ark.probeModel();
            state = probe.state().name();
            detail = probe.detail();
        }

        String textState;
        String textDetail;
        if (!qwenProperties.textConfigured()) {
            textState = "NOT_CONFIGURED";
            textDetail = "服务端尚未配置文本模型编号，网页生成保持关闭。";
        } else {
            QwenClient.ModelProbe probe = qwen.probeTextModel();
            textState = probe.state().name();
            textDetail = probe.detail();
        }

        String imageState;
        String imageDetail;
        if (!qwenProperties.imageConfigured()) {
            imageState = "NOT_CONFIGURED";
            imageDetail = "服务端尚未配置图片模型编号，网页配图保持关闭。";
        } else {
            QwenClient.ModelProbe probe = qwen.probeImageModel();
            imageState = probe.state().name();
            imageDetail = probe.detail();
        }

        ProviderStatus status = new ProviderStatus(properties.configured(), "volcengine-ark",
                host(properties.baseUrl()), properties.videoModel(), state, detail, Instant.now(),
                properties.defaultDurationSeconds(), properties.defaultResolution(),
                qwenProperties.textLabel(), textState, textDetail,
                qwenProperties.imageModel(), imageState, imageDetail);
        cached = status;
        cachedAt = Instant.now();
        return status;
    }

    private static String host(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (IllegalArgumentException error) {
            return "";
        }
    }
}
