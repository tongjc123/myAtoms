package com.deepwistom.atoms.web;

import com.deepwistom.atoms.provider.ProviderException;
import com.deepwistom.atoms.provider.QwenClient;
import java.util.Base64;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a web page with the configured Qianwen chat model: one call plans the structure, a second
 * one writes the single HTML file, and an optional third one paints a hero image that gets inlined
 * as a data URI. Both text calls are real provider requests; nothing is templated locally and
 * nothing is invented when the provider is unavailable.
 */
@Service
public class WebGenerator {
    private static final int OUTLINE_TOKENS = 900;
    private static final int HTML_TOKENS = 8000;
    /** 512×512 keeps the base64 payload small enough for the single-file size cap. */
    private static final String HERO_SIZE = "512*512";
    private static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;

    private final QwenClient qwen;
    private final ObjectMapper json;

    public WebGenerator(QwenClient qwen, ObjectMapper json) {
        this.qwen = qwen;
        this.json = json;
    }

    /** Raw model output; it still has to pass {@link WebHtml#sanitize(String)} before publishing. */
    public record Draft(String raw, long tokens) {}

    public boolean configured() {
        return qwen.textConfigured();
    }

    public String modelLabel() {
        return qwen.textLabel();
    }

    /** Provider model id (ASCII); the display label can contain non-ASCII text. */
    public String modelId() {
        return qwen.textModel();
    }

    public boolean imageConfigured() {
        return qwen.imageConfigured();
    }

    public String imageModel() {
        return qwen.imageModel();
    }

    public WebOutline outline(String projectName, String requirement) {
        requireModel();
        QwenClient.ChatResult reply = qwen.chat(WebPrompts.OUTLINE_SYSTEM,
                WebPrompts.outlineUser(projectName, requirement), OUTLINE_TOKENS);
        WebOutline outline = WebOutline.parse(json, reply.content());
        if (outline == null) {
            throw new ProviderException(502, "PROVIDER_OUTPUT_REJECTED",
                    "模型没有按要求返回页面结构，请重试。", true);
        }
        return outline;
    }

    public Draft write(String projectName, WebOutline outline) {
        requireModel();
        QwenClient.ChatResult reply = qwen.chat(WebPrompts.HTML_SYSTEM,
                WebPrompts.htmlUser(projectName, outline), HTML_TOKENS);
        return new Draft(reply.content(), reply.tokens());
    }

    public Draft game(String projectName, String requirement) {
        requireModel();
        var reply = qwen.chat(WebPrompts.GAME_SYSTEM,
                WebPrompts.outlineUser(projectName, requirement), HTML_TOKENS);
        return new Draft(reply.content(), reply.tokens());
    }

    public Draft edit(String type, String projectName, String requirement, String instruction,
                      String baseVersionId, String source) {
        requireModel();
        var reply = qwen.chat(WebPrompts.EDIT_SYSTEM, "项目类型：" + type + "\n"
                + WebPrompts.outlineUser(projectName, requirement)
                + "\n基础版本：" + baseVersionId + "\n修改说明：" + instruction
                + "\n当前完整 HTML 源码：\n" + source, HTML_TOKENS);
        return new Draft(reply.content(), reply.tokens());
    }

    /**
     * Generates a hero image and returns it as a {@code data:image/png;base64,...} URI so the page
     * stays self-contained under the preview CSP. Image failure must not block the page: the caller
     * decides whether to degrade gracefully.
     */
    public String heroDataUri(String projectName, WebOutline outline) {
        String url = qwen.generateImage(WebPrompts.heroPrompt(projectName, outline), HERO_SIZE);
        byte[] bytes = qwen.download(url, MAX_IMAGE_BYTES);
        if (bytes.length < 64) {
            throw new ProviderException(502, "PROVIDER_ERROR", "下载到的图片内容异常，请重新生成。", true);
        }
        String mime = sniffMime(bytes);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String sniffMime(byte[] bytes) {
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length > 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length > 3 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        return "image/png";
    }

    private void requireModel() {
        if (!qwen.textConfigured()) {
            throw new ProviderException(503, "PROVIDER_NOT_CONFIGURED",
                    "服务端尚未配置文本模型编号，暂时无法生成或修改网页和游戏。", false);
        }
    }
}
