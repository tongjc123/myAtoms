package com.deepwistom.atoms.provider;

import java.io.ByteArrayOutputStream;
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
 * Minimal client for the Qianwen (通义千问) gateway, DashScope-style endpoints:
 * <ul>
 *   <li>text: {@code POST {baseUrl}/services/aigc/text-generation/generation}</li>
 *   <li>image: {@code POST {baseUrl}/services/aigc/multimodal-generation/generation}</li>
 * </ul>
 * Chat replies arrive as {@code output.choices[0].message.content}; image replies as
 * {@code output.choices[0].message.content[].image} (a URL to download). Every call is paid except
 * {@link #probeTextModel()}, which submits an empty message list rejected during validation.
 */
@Component
public class QwenClient {
    private static final Logger log = LoggerFactory.getLogger(QwenClient.class);

    public enum ModelState { ACTIVATED, NOT_ACTIVATED, MODEL_NOT_FOUND, AUTH_FAILED, UNREACHABLE, UNKNOWN }

    public record ModelProbe(ModelState state, String detail) {}

    public record ChatResult(String content, String model, long tokens) {}

    private final QwenProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public QwenClient(QwenProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean textConfigured() {
        return properties.textConfigured();
    }

    public boolean imageConfigured() {
        return properties.imageConfigured();
    }

    public String textModel() {
        return properties.textModel();
    }

    public String textLabel() {
        return properties.textLabel();
    }

    public String imageModel() {
        return properties.imageModel();
    }

    /** One chat completion against the configured text model. This is a paid call. */
    public ChatResult chat(String system, String user, int maxTokens) {
        if (properties.apiKey().isBlank()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED",
                    "服务端尚未配置模型访问密钥，暂时无法生成。", false);
        }
        if (properties.textModel().isBlank()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED",
                    "服务端尚未配置文本模型编号，暂时无法生成或修改网页和游戏。", false);
        }
        List<Object> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", user));
        Map<String, Object> input = Map.of("messages", messages);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.textModel());
        body.put("input", input);
        body.put("parameters", Map.of("max_tokens", maxTokens, "result_format", "message"));
        JsonNode response = send("POST", "/services/aigc/text-generation/generation",
                json.writeValueAsString(body), "文本", properties.textTimeout());
        JsonNode content = response.path("output").path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull() || content.asString().isBlank()) {
            throw new ProviderException(502, "PROVIDER_EMPTY_OUTPUT",
                    "模型没有返回可用内容，请重新生成。", true);
        }
        long tokens = response.path("usage").path("output_tokens").asLong(0);
        return new ChatResult(content.asString(), properties.textModel(), tokens);
    }

    /**
     * Generates one image and returns its public download URL. The caller downloads and inlines the
     * bytes so the sandboxed preview never depends on an external host.
     */
    public String generateImage(String prompt, String size) {
        if (properties.apiKey().isBlank()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED",
                    "服务端尚未配置模型访问密钥，暂时无法生成配图。", false);
        }
        if (properties.imageModel().isBlank()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED",
                    "服务端尚未配置图片模型编号，暂时无法生成配图。", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.imageModel());
        body.put("input", Map.of("messages", List.of(Map.of("role", "user",
                "content", List.of(Map.of("text", prompt))))));
        body.put("parameters", Map.of("prompt_extend", true, "size", size, "n", 1));
        JsonNode response = send("POST", "/services/aigc/multimodal-generation/generation",
                json.writeValueAsString(body), "图片", properties.imageTimeout());
        JsonNode first = response.path("output").path("choices").path(0).path("message").path("content");
        for (JsonNode item : first) {
            if ("image".equals(item.path("type").asString())) {
                String url = item.path("image").asString(null);
                if (url != null && !url.isBlank()) return url;
            }
        }
        throw new ProviderException(502, "PROVIDER_EMPTY_OUTPUT",
                "图片模型没有返回可用图片，请重新生成。", true);
    }

    /**
     * Downloads an image the provider returned. The URL is validated the same way any
     * user-influenced address would be: http(s) only, no private/loopback targets unless explicitly
     * allowed, and a hard size cap.
     */
    public byte[] download(String url, long maxBytes) {
        URI uri = validateMediaUrl(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            log.warn("Image download failed exception={}", error.getClass().getSimpleName());
            throw new ProviderException(503, "PROVIDER_UNREACHABLE", "暂时无法下载生成的图片，请稍后重试。", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "PROVIDER_INTERRUPTED", "图片下载被中断，请稍后重试。", true);
        }
        if (response.statusCode() != 200) {
            throw new ProviderException(502, "PROVIDER_ERROR", "图片文件暂时无法下载，请稍后重试。", true);
        }
        long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (announced > maxBytes) {
            throw new ProviderException(502, "PROVIDER_ERROR", "图片文件超出大小限制，已放弃下载。", true);
        }
        try (var stream = response.body()) {
            byte[] buffer = new byte[1 << 15];
            var out = new ByteArrayOutputStream();
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > maxBytes) {
                    throw new ProviderException(502, "PROVIDER_ERROR", "图片文件超出大小限制，已放弃下载。", true);
                }
            }
            return out.toByteArray();
        } catch (IOException error) {
            throw new ProviderException(503, "PROVIDER_UNREACHABLE", "图片下载中断，请稍后重试。", true);
        }
    }

    /**
     * Verifies the configured chat model without producing billable output: the gateway rejects an
     * empty message list during request validation, before any tokens are generated.
     */
    public ModelProbe probeTextModel() {
        if (properties.apiKey().isBlank()) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "服务端未配置模型访问密钥。");
        }
        if (properties.textModel().isBlank()) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "服务端未配置文本模型编号。");
        }
        Map<String, Object> body = Map.of("model", properties.textModel(),
                "input", Map.of("messages", List.of()),
                "parameters", Map.of("max_tokens", 1, "result_format", "message"));
        int status;
        String raw;
        try {
            var response = exchange("POST", "/services/aigc/text-generation/generation",
                    json.writeValueAsString(body), Duration.ofSeconds(20));
            status = response.statusCode();
            raw = response.body();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Text model probe unreachable exception={}", error.getClass().getSimpleName());
            return new ModelProbe(ModelState.UNREACHABLE, "暂时无法连接千问服务。");
        }
        if (status == 200) {
            return new ModelProbe(ModelState.ACTIVATED, "模型可访问。");
        }
        JsonNode error = parse(raw).path("error");
        String code = error.isMissingNode() ? parse(raw).path("code").asString() : error.path("code").asString();
        if ("InvalidParameter".equals(code)) {
            return new ModelProbe(ModelState.ACTIVATED, "模型已开通，参数校验正常。");
        }
        if ("AccessDenied.Unpurchased".equals(code) || "AccessDenied".equals(code)) {
            return new ModelProbe(ModelState.NOT_ACTIVATED, "账号尚未购买该文本模型，生成会失败。");
        }
        if ("InvalidApiKey".equals(code) || status == 401) {
            return new ModelProbe(ModelState.AUTH_FAILED, "模型访问密钥无效或权限不足。");
        }
        if (status == 403) {
            return new ModelProbe(ModelState.NOT_ACTIVATED, "账号无权访问该文本模型。");
        }
        if ("ModelNotOpen".equals(code)) {
            return new ModelProbe(ModelState.NOT_ACTIVATED, "账号尚未开通该文本模型。");
        }
        if (status == 404 || "ModelNotFound".equals(code)) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "文本模型编号不存在或当前账号无权访问。");
        }
        return new ModelProbe(ModelState.UNKNOWN, "文本模型状态待核实（HTTP " + status + "）。");
    }

    /**
     * Verifies the configured image model without scheduling a real image: an empty text content is
     * rejected during validation (already-purchased models) or by the access check (unpurchased
     * models) before any pixels are produced.
     */
    public ModelProbe probeImageModel() {
        if (properties.apiKey().isBlank()) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "服务端未配置模型访问密钥。");
        }
        if (properties.imageModel().isBlank()) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "服务端未配置图片模型编号。");
        }
        Map<String, Object> body = Map.of("model", properties.imageModel(),
                "input", Map.of("messages", List.of(Map.of("role", "user",
                        "content", List.of(Map.of("text", ""))))),
                "parameters", Map.of("prompt_extend", false, "size", "512*512", "n", 1));
        int status;
        String raw;
        try {
            var response = exchange("POST", "/services/aigc/multimodal-generation/generation",
                    json.writeValueAsString(body), Duration.ofSeconds(20));
            status = response.statusCode();
            raw = response.body();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Image model probe unreachable exception={}", error.getClass().getSimpleName());
            return new ModelProbe(ModelState.UNREACHABLE, "暂时无法连接千问服务。");
        }
        if (status == 200) {
            return new ModelProbe(ModelState.ACTIVATED, "图片模型可访问。");
        }
        String code = text(parse(raw), "code");
        if ("AccessDenied.Unpurchased".equals(code) || "AccessDenied".equals(code)) {
            return new ModelProbe(ModelState.NOT_ACTIVATED, "账号尚未购买该图片模型，配图会降级为无配图版式。");
        }
        if ("InvalidParameter".equals(code)) {
            return new ModelProbe(ModelState.ACTIVATED, "图片模型已购买，参数校验正常。");
        }
        if ("InvalidApiKey".equals(code) || status == 401) {
            return new ModelProbe(ModelState.AUTH_FAILED, "模型访问密钥无效或权限不足。");
        }
        if (status == 403) {
            return new ModelProbe(ModelState.NOT_ACTIVATED, "账号无权访问该图片模型。");
        }
        if (status == 404 || "ModelNotFound".equals(code)) {
            return new ModelProbe(ModelState.MODEL_NOT_FOUND, "图片模型编号不存在或当前账号无权访问。");
        }
        return new ModelProbe(ModelState.UNKNOWN, "图片模型状态待核实（HTTP " + status + "）。");
    }

    private URI validateMediaUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException error) {
            throw new ProviderException(502, "PROVIDER_ERROR", "图片服务返回了无效的下载地址。", false);
        }
        String scheme = uri.getScheme();
        if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new ProviderException(502, "PROVIDER_ERROR", "图片服务返回了无效的下载地址。", false);
        }
        if (!properties.allowPrivateMediaHosts()) {
            try {
                for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                    if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                            || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
                        throw new ProviderException(502, "PROVIDER_ERROR", "图片服务返回了内网下载地址，已拒绝。", false);
                    }
                }
            } catch (java.net.UnknownHostException error) {
                throw new ProviderException(502, "PROVIDER_ERROR", "无法解析图片下载地址。", false);
            }
        }
        return uri;
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
        String code = text(parsed, "code");
        String message = text(parsed, "message");
        log.warn("Provider error http={} code={} requestId={}", response.statusCode(), code, requestId(message));
        throw mapError(response.statusCode(), code, label);
    }

    private ProviderException mapError(int status, String code, String label) {
        if ("ModelNotOpen".equals(code) || "AccessDenied.Unpurchased".equals(code)
                || "AccessDenied".equals(code)) {
            return new ProviderException(503, "PROVIDER_MODEL_NOT_ACTIVATED",
                    label + "模型尚未在千问控制台开通或购买，开通后重新生成即可。", false);
        }
        if (status == 401 || "InvalidApiKey".equals(code)
                || "AuthenticationError".equals(code)) {
            return new ProviderException(502, "PROVIDER_AUTH_FAILED", label + "服务鉴权失败，请检查服务端配置。", false);
        }
        if (status == 403 && !"AccessDenied.Unpurchased".equals(code) && !"AccessDenied".equals(code)) {
            return new ProviderException(502, "PROVIDER_AUTH_FAILED", label + "服务拒绝访问，请检查服务端配置。", false);
        }
        if (status == 404 || "ModelNotFound".equals(code)) {
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

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
}
