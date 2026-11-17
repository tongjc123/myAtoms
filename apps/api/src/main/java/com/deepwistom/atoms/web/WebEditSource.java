package com.deepwistom.atoms.web;

import com.deepwistom.atoms.common.ApiException;
import com.deepwistom.atoms.provider.ProviderException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reversible transport for large inline assets; surrounding source is never truncated or reformatted. */
public final class WebEditSource {
    private static final String PREFIX = "__ATOMS_ASSET_";
    private static final Pattern ASSET = Pattern.compile(
            "(?i)data:(?:image|audio|video|font)/[a-z0-9.+-]+;base64,[a-z0-9+/]+={0,2}(?=[\\s\"'`)<>]|$)");
    private static final Pattern TOKEN = Pattern.compile("__ATOMS_ASSET_[0-9a-f]{32}_[0-9]+__");
    private final String html;
    private final Map<String, String> assets;

    private WebEditSource(String html, Map<String, String> assets) {
        this.html = html;
        this.assets = Map.copyOf(assets);
    }

    public static WebEditSource prepare(String source) {
        String namespace = PREFIX + UUID.randomUUID().toString().replace("-", "") + "_";
        Map<String, String> assets = new LinkedHashMap<>();
        Matcher matcher = ASSET.matcher(source);
        StringBuilder compact = new StringBuilder();
        while (matcher.find()) {
            String asset = matcher.group();
            // Small inline assets and ordinary small sources remain byte-for-byte model context.
            if (asset.length() < 2048) continue;
            String token = namespace + assets.size() + "__";
            assets.put(token, asset);
            matcher.appendReplacement(compact, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(compact);
        String html = compact.toString();
        if (html.length() > 100_000 || html.getBytes(StandardCharsets.UTF_8).length > 180_000) {
            throw new ApiException(413, "EDIT_CONTEXT_TOO_LARGE", "当前源码超出修改上下文上限，未调用模型。");
        }
        return new WebEditSource(html, assets);
    }

    public String html() { return html; }

    public String restore(String generated) {
        if (generated == null) return null; // The shared HTML validator reports empty model output.
        Matcher matcher = TOKEN.matcher(generated);
        StringBuilder restored = new StringBuilder();
        var seen = new HashSet<String>();
        int end = 0;
        while (matcher.find()) {
            String token = matcher.group();
            String asset = assets.get(token);
            if (asset == null) throw rejected();
            if ((long) restored.length() + matcher.start() - end + asset.length() > WebHtml.MAX_BYTES) {
                throw rejected();
            }
            restored.append(generated, end, matcher.start()).append(asset);
            seen.add(token);
            end = matcher.end();
        }
        restored.append(generated, end, generated.length());
        // Unknown, damaged or omitted references must never silently lose an original asset.
        if (!seen.containsAll(assets.keySet()) || restored.indexOf(PREFIX) >= 0) throw rejected();
        return restored.toString();
    }

    private static ProviderException rejected() {
        return new ProviderException(502, "PROVIDER_OUTPUT_REJECTED",
                "模型未完整保留内嵌素材引用或返回的素材超出大小限制，未写入新版本。", true);
    }
}
