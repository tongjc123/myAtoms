package com.deepwistom.atoms.slides;

import java.util.List;
import java.util.Map;

/** A deck is pure data: the same JSON is embedded into index.html and read by deck.js. */
public record SlideDeck(String title, String theme, Map<String, String> brand,
                        List<Map<String, Object>> slides) {

    public int size() { return slides.size(); }

    /** One-line description of the structure, used by run events and version summaries. */
    public String outline() {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> slide : slides) {
            String title = String.valueOf(slide.getOrDefault("title", slide.getOrDefault("text", "")));
            if (text.length() > 0) text.append(" / ");
            text.append(title.length() > 18 ? title.substring(0, 18) : title);
        }
        return text.toString();
    }
}
