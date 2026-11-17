package com.deepwistom.atoms.web;

import com.deepwistom.atoms.provider.ProviderException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a model reply into the single file that may be published. The preview iframe runs with an
 * opaque origin and a CSP that blocks everything except inline code, so a page that depends on
 * external resources would look broken; those replies are rejected instead of stored.
 */
public final class WebHtml {
    private WebHtml() {}

    /** Upper bound for one generated page; a single-file site above this is almost always runaway. */
    public static final int MAX_BYTES = 600_000;
    private static final int MIN_BYTES = 400;

    private static final Pattern EXTERNAL_REF = Pattern.compile(
            "(?i)(?:\\b(?:src|href|action)\\s*=\\s*[\"']\\s*(?:https?:)?//|url\\(\\s*[\"']?(?:https?:)?//)");

    public static byte[] sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw rejected("模型没有返回页面内容，请重新生成。");
        }
        String value = stripFence(raw.strip());
        int doctype = indexOfIgnoreCase(value, "<!doctype");
        int htmlTag = indexOfIgnoreCase(value, "<html");
        int start = doctype >= 0 ? doctype : htmlTag;
        if (start < 0) throw rejected("模型返回的内容不是完整的网页，请重新生成。");
        value = value.substring(start).strip();
        if (!value.toLowerCase(Locale.ROOT).startsWith("<!doctype")) {
            value = "<!doctype html>\n" + value;
        }
        if (indexOfIgnoreCase(value, "</html>") < 0) {
            throw rejected("模型返回的网页没有正常结束，请重新生成。");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) throw rejected("模型返回的网页内容过少，请重新生成。");
        if (bytes.length > MAX_BYTES) throw rejected("模型返回的网页超过大小上限，已放弃入库。");
        if (EXTERNAL_REF.matcher(value).find()) {
            throw rejected("模型返回的页面引用了外部资源，预览环境会拦截，已拒绝入库。请重试。");
        }
        return bytes;
    }

    /**
     * Inlines the generated hero image as a CSS background on the {@code .hero-image} placeholder the
     * model was told to emit. The data URI is local and passes the preview CSP. If the model did not
     * emit the placeholder, the original page is returned unchanged so a missing image never breaks
     * the page. Applied after {@link #sanitize(String)} so the injected bytes are never stripped.
     */
    public static byte[] injectHero(byte[] sanitized, String dataUri) {
        if (dataUri == null || dataUri.isBlank()) {
            return sanitized;
        }
        String style = "<style>.hero-image{background-image:url(\"" + dataUri
                + "\");background-size:cover;background-position:center;min-height:220px;border-radius:16px;}</style>";
        String value = new String(sanitized, StandardCharsets.UTF_8);
        if (indexOfIgnoreCase(value, "</head>") >= 0) {
            value = value.replaceFirst("(?i)</head>", style + "</head>");
        } else if (indexOfIgnoreCase(value, "<body") >= 0) {
            value = value.replaceFirst("(?i)(<body[^>]*>)", "$1" + style);
        } else {
            return sanitized;
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String stripFence(String value) {
        if (!value.startsWith("```")) return value;
        int newline = value.indexOf('\n');
        if (newline < 0) return value;
        String body = value.substring(newline + 1);
        int fence = body.lastIndexOf("```");
        return fence >= 0 ? body.substring(0, fence).stripTrailing() : body;
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).indexOf(needle);
    }

    private static ProviderException rejected(String message) {
        return new ProviderException(502, "PROVIDER_OUTPUT_REJECTED", message, true);
    }
}
