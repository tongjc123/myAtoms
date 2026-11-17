package com.deepwistom.atoms.slides;

import com.deepwistom.atoms.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders a deck into one self-contained HTML file.
 *
 * <p>The stylesheet and the engine are inlined on purpose: previews run inside a sandboxed iframe
 * with an opaque origin, so the response CSP can only allow inline script/style. One file also
 * makes each version easy to export or download later.
 */
@Component
public class DeckRenderer {
    private static final String PAGE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              <style>%s</style>
            </head>
            <body>
              <div class="deck-app">
                <aside class="rail" aria-label="幻灯片缩略图">
                  <div class="rail-head"><strong>幻灯片</strong><span id="deck-count"></span></div>
                  <div class="rail-list" id="rail-list"></div>
                </aside>
                <main class="stage-wrap">
                  <header class="top-bar">
                    <strong>%s</strong>
                    <span class="spacer"></span>
                    <span>16:9 · %d 页</span>
                  </header>
                  <div class="stage-holder" id="stage-holder">
                    <div class="stage" id="stage"></div>
                  </div>
                  <div class="controls">
                    <span>← → 翻页</span>
                    <div class="nav-buttons">
                      <button type="button" id="prev">上一页</button>
                      <button type="button" id="next">下一页</button>
                    </div>
                  </div>
                </main>
              </div>
              <script type="application/json" id="deck-spec">%s</script>
              <script>%s</script>
            </body>
            </html>
            """;

    private final ObjectMapper json;
    private final SlideEngine engine;

    public DeckRenderer(ObjectMapper json, SlideEngine engine) {
        this.json = json;
        this.engine = engine;
    }

    public Map<String, byte[]> files(SlideDeck deck) {
        String spec = json.writeValueAsString(deck).replace("</", "<\\/");
        String css = new String(engine.css(), StandardCharsets.UTF_8);
        String js = new String(engine.js(), StandardCharsets.UTF_8).replace("</script", "<\\/script");
        String html = PAGE.formatted(escape(deck.title()), css, escape(deck.title()), deck.size(), spec, js);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("index.html", html.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    /** Parses the deck spec embedded by this renderer; used to inherit a template's theme. */
    public SlideDeck read(byte[] indexHtml) {
        String content = new String(indexHtml, StandardCharsets.UTF_8);
        int open = content.indexOf("id=\"deck-spec\">");
        int close = content.indexOf("</script>", open < 0 ? 0 : open);
        if (open < 0 || close < 0) return null;
        String spec = content.substring(open + "id=\"deck-spec\">".length(), close).replace("<\\/", "</");
        try {
            return json.readValue(spec, SlideDeck.class);
        } catch (RuntimeException error) {
            throw new ApiException(500, "TEMPLATE_UNREADABLE", "模板内容无法解析。");
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
