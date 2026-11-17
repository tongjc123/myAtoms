package com.deepwistom.atoms.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal client for the Volcengine Ark asynchronous content generation API
 * ({@code POST/GET/DELETE {baseUrl}/contents/generations/tasks}).
 *
 * <p>Every outbound call is a paid operation except {@link #probeModel()}, which submits an empty
 * content list that the provider rejects during validation. Callers are expected to reserve budget
 * before invoking {@link #createVideoTask}.
 */
@Component
public class ArkClient {
    private static final Logger log = LoggerFactory.getLogger(ArkClient.class);

    public enum ModelState { ACTIVATED, NOT_ACTIVATED, MODEL_NOT_FOUND, AUTH_FAILED, UNREACHABLE, UNKNOWN }

    public record ModelProbe(ModelState state, String detail) {}

    public record VideoRequest(
            String prompt,
            String imageUrl,
            int durationSeconds,
            String resolution,
            String ratio,
            boolean generateAudio,
            boolean watermark) {}

    public record VideoTask(
            String id,
            String model,
            String status,
            String videoUrl,
            String lastFrameUrl,
            String errorCode,
            String errorMessage,
            long usageTokens,
            String createdAt) {
        public boolean succeeded() { return "succeeded".equals(status); }
        public boolean failed() { return "failed".equals(status); }
        public boolean terminal() { return succeeded() || failed(); }
    }

    private final ProviderProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public ArkClient(ProviderProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean configured() {
        return properties.configured();
    }

    public String videoModel() {
        return properties.videoModel();
    }

    public VideoTask createVideoTask(VideoRequest request) {
        return createVideoTask(properties.videoModel(), request);
    }

    public VideoTask createVideoTask(String modelId, VideoRequest request) {
        requireConfiguration();
        if (modelId == null || modelId.isBlank()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED", "所选模型尚未配置编号，暂时无法生成。", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("content", content(request.prompt(), request.imageUrl()));
        body.put("duration", request.durationSeconds());
        body.put("resolution", request.resolution());
        if (request.ratio() != null && !request.ratio().isBlank()) body.put("ratio", request.ratio());
        body.put("generate_audio", request.generateAudio());
        body.put("watermark", request.watermark());
        JsonNode response = send("POST", "/contents/generations/tasks", json.writeValueAsString(body));
        String id = text(response, "id");
        if (id == null) throw new ProviderException(502, "PROVIDER_ERROR", "视频服务未返回任务编号，请稍后重试。", true);
        return new VideoTask(id, modelId, "queued", null, null, null, null, 0, null);
    }

    public VideoTask getVideoTask(String taskId) {
        requireConfiguration();
        JsonNode response = send("GET", "/contents/generations/tasks/" + safeId(taskId), null);
        JsonNode error = response.path("error");
        JsonNode content = response.path("content");
        return new VideoTask(
                text(response, "id"),
                text(response, "model"),
                text(response, "status"),
                text(content, "video_url"),
                text(content, "last_frame_url"),
                error.isMissingNode() || error.isNull() ? null : text(error, "code"),
                error.isMissingNode() || error.isNull() ? null : text(error, "message"),
                response.path("usage").path("completion_tokens").asLong(0),
                text(response, "created_at"));
    }

    /** Best-effort external cancellation; the provider may already have finished the task. */
    public void deleteVideoTask(String taskId) {
        requireConfiguration();
        send("DELETE", "/contents/generations/tasks/" + safeId(taskId), null);
    }

    /**
     * Downloads the finished video from the URL the provider returned. The URL is validated the
     * same way any user-influenced address would be: http(s) only, no private/loopback targets
     * unless explicitly allowed, and a hard size cap.
     */
    public byte[] download(String url, long maxBytes) {
        URI uri = validateMediaUrl(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            log.warn("Media download failed exception={}", error.getClass().getSimpleName());
            throw new ProviderException(503, "PROVIDER_UNREACHABLE", "暂时无法下载生成的视频，请稍后重试。", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "PROVIDER_INTERRUPTED", "视频下载被中断，请稍后重试。", true);
        }
        if (response.statusCode() != 200) {
            throw new ProviderException(502, "PROVIDER_ERROR", "视频文件暂时无法下载，请稍后重试。", true);
        }
        long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (announced > maxBytes) {
            throw new ProviderException(502, "PROVIDER_ERROR", "视频文件超出大小限制，已放弃下载。", true);
        }
        try (var stream = response.body()) {
            byte[] buffer = new byte[1 << 15];
            var out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > maxBytes) {
                    throw new ProviderException(502, "PROVIDER_ERROR", "视频文件超出大小限制，已放弃下载。", true);
                }
            }
            return out.toByteArray();
        } catch (IOException error) {
            throw new ProviderException(503, "PROVIDER_UNREACHABLE", "视频下载中断，请稍后重试。", true);
        }
    }

    private URI validateMediaUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException error) {
            throw new ProviderException(502, "PROVIDER_ERROR", "视频服务返回了无效的下载地址。", false);
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme) || uri.getHost() == null) {
            throw new ProviderException(502, "PROVIDER_ERROR", "视频服务返回了无效的下载地址。", false);
        }
        if (!properties.allowPrivateMediaHosts()) {
            try {
                for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                    if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                            || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
                        throw new ProviderException(502, "PROVIDER_ERROR", "视频服务返回了内网下载地址，已拒绝。", false);
                    }
                }
            } catch (java.net.UnknownHostException error) {
                throw new ProviderException(502, "PROVIDER_ERROR", "无法解析视频下载地址。", false);
            }
        }
        return uri;
    }

    /**
     * Verifies that the configured API key can reach the configured model without creating a paid
     * task: Ark validates and rejects an empty {@code content} list before scheduling any work.
     */
    public ModelProbe probeModel() {
        if (properties.apiKey().isBlank()) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "服务端未配置模型访问密钥。");
        }
        if (properties.videoModel().isBlank()) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "服务端未配置视频模型编号。");
        }
        Map<String, Object> body = Map.of("model", properties.videoModel(), "content", List.of());
        int status;
        String raw;
        try {
            var response = exchange("POST", "/contents/generations/tasks", json.writeValueAsString(body));
            status = response.statusCode();
            raw = response.body();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Provider probe unreachable exception={}", error.getClass().getSimpleName());
            return new ModelProbe(ModelState.UNREACHABLE, "暂时无法连接火山方舟服务。");
        }
        if (status == 200) {
            // Should not happen for an empty content list; clean up so no billable task is left behind.
            String id = text(parse(raw), "id");
            if (id != null) deleteQuietly(id);
            return new ModelProbe(ModelState.ACTIVATED, "模型可访问。");
        }
        JsonNode error = parse(raw).path("error");
        String code = text(error, "code");
        if ("ModelNotOpen".equals(code)) {
            return new ModelProbe(ModelState.NOT_ACTIVATED, "账号尚未在火山方舟控制台开通该模型服务。");
        }
        if ("InvalidParameter".equals(code)) {
            return new ModelProbe(ModelState.ACTIVATED, "模型已开通，参数校验正常。");
        }
        if (status == 401 || status == 403) {
            return new ModelProbe(ModelState.AUTH_FAILED, "模型访问密钥无效或权限不足。");
        }
        if (status == 404) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "模型编号不存在或当前账号无权访问。");
        }
        return new ModelProbe(ModelState.UNKNOWN, "模型状态待核实（HTTP " + status + "）。");
    }

    private List<Object> content(String prompt, String imageUrl) {        List<Object> content = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", prompt);
        content.add(text);
        if (imageUrl != null && !imageUrl.isBlank()) {
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("type", "image_url");
            image.put("image_url", Map.of("url", imageUrl));
            image.put("role", "reference_image");
            content.add(image);
        }
        return content;
    }

    private JsonNode send(String method, String path, String body) {
        return send(method, path, body, "视频", properties.requestTimeout());
    }

    private JsonNode send(String method, String path, String body, String label, Duration timeout) {
        HttpResponse<String> response;
        try {
            response = exchange(method, path, body, timeout);
        } catch (IOException error) {
            log.warn("Provider call failed method={} exception={}", method, error.getClass().getSimpleName());
            throw new ProviderException(503, "PROVIDER_UNREACHABLE", "暂时无法连接" + label + "服务，请稍后重试。", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "PROVIDER_INTERRUPTED", label + "服务调用被中断，请稍后重试。", true);
        }
        JsonNode parsed = parse(response.body());
        if (response.statusCode() >= 200 && response.statusCode() < 300) return parsed;
        JsonNode error = parsed.path("error");
        String code = text(error, "code");
        String message = text(error, "message");
        log.warn("Provider error http={} code={} requestId={}", response.statusCode(), code, requestId(message));
        throw mapError(response.statusCode(), code, label);
    }

    private ProviderException mapError(int status, String code, String label) {
        if ("ModelNotOpen".equals(code)) {
            return new ProviderException(503, "PROVIDER_MODEL_NOT_ACTIVATED",
                    label + "模型尚未在火山方舟控制台开通，开通后重新生成即可。", false);
        }
        if (status == 401 || status == 403 || "AuthenticationError".equals(code)) {
            return new ProviderException(502, "PROVIDER_AUTH_FAILED", label + "服务鉴权失败，请检查服务端配置。", false);
        }
        if (status == 404) {
            return new ProviderException(502, "PROVIDER_MODEL_NOT_FOUND", label + "模型编号不存在或无权访问。", false);
        }
        if (status == 429) {
            return new ProviderException(429, "PROVIDER_RATE_LIMITED", label + "服务当前限流，请稍后重试。", true);
        }
        if (status >= 500) {
            return new ProviderException(503, "PROVIDER_ERROR", label + "服务暂时不可用，请稍后重试。", true);
        }
        return new ProviderException(502, "PROVIDER_REJECTED", label + "服务拒绝了请求，请检查输入内容。", false);
    }

    private HttpResponse<String> exchange(String method, String path, String body)
            throws IOException, InterruptedException {
        return exchange(method, path, body, properties.requestTimeout());
    }

    private HttpResponse<String> exchange(String method, String path, String body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimSlash(properties.baseUrl()) + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) return json.createObjectNode();
        try {
            return json.readTree(body);
        } catch (RuntimeException error) {
            return json.createObjectNode();
        }
    }

    private void deleteQuietly(String taskId) {
        try {
            deleteVideoTask(taskId);
        } catch (RuntimeException error) {
            log.warn("Provider probe cleanup failed exception={}", error.getClass().getSimpleName());
        }
    }

    private void requireConfiguration() {
        if (!properties.configured()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED", "视频服务尚未配置，暂时无法生成。", false);
        }
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new ProviderException(400, "INVALID_TASK_ID", "任务编号不合法。", false);
        }
        return id;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    private static String requestId(String message) {
        if (message == null) return "-";
        int index = message.indexOf("Request id:");
        if (index < 0) return "-";
        String value = message.substring(index + "Request id:".length()).trim();
        return value.isEmpty() ? "-" : value.split("\\s+")[0];
    }

    public Duration requestTimeout() {
        return properties.requestTimeout();
    }
}
