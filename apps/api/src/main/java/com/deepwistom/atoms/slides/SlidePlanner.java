package com.deepwistom.atoms.slides;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic planning engine for slide decks.
 *
 * <p>It does not invent facts: every title, bullet and metric comes from text the visitor
 * actually supplied, regrouped into a readable deck. When a language model is wired in later it
 * can replace this class without changing the run pipeline or the renderer.
 */
@Component
public class SlidePlanner {
    private static final Pattern SENTENCE = Pattern.compile("[。！？；;\\n]+|\\.\\s+");
    private static final Pattern FRAGMENT = Pattern.compile("[，,、]+");
    private static final Pattern METRIC = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:%|万|亿|倍|元|人|天|年|个|页|项)");
    /** Sentences that only carry logistics (date, venue, sign-up) make weak full-page claims. */
    private static final Pattern LOGISTICS = Pattern.compile(
            "^(?:活动|发布会|会议|大会|展会|论坛|开幕|举办|时间|地点|日期|报名|地址|日程)");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record OutlineSlide(String title, List<String> bullets) {}

    public record Plan(SlideDeck deck, String theme, List<String> topics, List<String> notes) {}

    public record Topic(String title, List<String> bullets, String statement) {}

    /** Plans a deck from the visitor's own words, optionally inheriting a template theme. */
    public Plan plan(String projectName, String prompt, String inheritedTheme,
                     List<OutlineSlide> imported, List<String> inheritedSlides) {
        String theme = inheritedTheme == null || inheritedTheme.isBlank()
                ? detectTheme(prompt) : inheritedTheme;
        List<String> notes = new ArrayList<>();
        List<Topic> topics = new ArrayList<>();

        if (imported != null && !imported.isEmpty()) {
            for (OutlineSlide slide : imported) {
                String title = clean(slide.title(), 30);
                if (title.isBlank()) continue;
                List<String> bullets = new ArrayList<>();
                for (String bullet : slide.bullets()) {
                    String value = clean(bullet, 46);
                    if (!value.isBlank() && !value.equals(title)) bullets.add(value);
                }
                if (bullets.size() >= 2) topics.add(new Topic(title, bullets, null));
                else topics.add(new Topic(title, List.of(), bullets.isEmpty() ? title : bullets.get(0)));
            }
            notes.add("已读取导入文件的大纲，共 " + imported.size() + " 页");
        }

        if (topics.isEmpty()) {
            List<String> clauses = clauses(prompt);
            for (String clause : clauses) {
                Topic topic = topic(clause);
                if (topic != null) topics.add(topic);
                if (topics.size() >= 6) break;
            }
            notes.add("从需求中识别出 " + topics.size() + " 个可展开的要点");
        }
        if (topics.size() > 6) {
            notes.add("要点超过 6 个，仅保留前 6 个");
            topics = new ArrayList<>(topics.subList(0, 6));
        }

        // Sentences without a comma cannot carry bullets; promote at most one of them to a
        // full-width statement page and fold the rest into a single supporting slide.
        List<Topic> shaped = new ArrayList<>();
        List<String> leftovers = new ArrayList<>();
        for (Topic topic : topics) {
            if (topic.statement() != null) leftovers.add(topic.statement());
            else shaped.add(topic);
        }
        String claim = pickClaim(leftovers);
        if (claim != null) leftovers.remove(claim);
        if (!leftovers.isEmpty()) {
            shaped.add(new Topic("补充说明", leftovers.subList(0, Math.min(4, leftovers.size())), null));
        }
        topics = shaped;

        List<Map<String, Object>> slides = new ArrayList<>();
        String title = deckTitle(projectName, prompt);
        String subtitle = topics.isEmpty() ? clamp(prompt, 54) : topics.get(0).title();

        slides.add(slide("cover", Map.of(
                "kicker", "SLIDE DECK",
                "title", title,
                "subtitle", subtitle,
                "meta", List.of(LocalDate.now(ZONE).toString().replace('-', '.'), "Atoms Demo"))));
        if (inheritedSlides != null && !inheritedSlides.isEmpty()) {
            notes.add("沿用模板的版式节奏：" + String.join(" / ", inheritedSlides.subList(0,
                    Math.min(3, inheritedSlides.size()))));
        }

        if (!topics.isEmpty()) {
            List<String> agenda = new ArrayList<>();
            for (Topic topic : topics) agenda.add(clamp(topic.title(), 26));
            slides.add(slide("agenda", Map.of("kicker", "CONTENTS", "title", "本次要点", "items", agenda)));
        }

        if (claim != null) {
            slides.add(slide("statement", Map.of("text", claim)));
            notes.add("抽取了一句可独立成页的主张");
        }

        List<Map<String, Object>> stats = metrics(prompt);
        if (stats.size() >= 2) {
            slides.add(slide("stats", Map.of("kicker", "KEY FIGURES", "title", "关键数据",
                    "stats", stats.subList(0, Math.min(3, stats.size())))));
            notes.add("识别到 " + stats.size() + " 处可量化信息");
        }

        int section = 1;
        for (Topic topic : topics) {
            String kicker = String.format(Locale.ROOT, "%02d", section++);
            if (topic.statement() != null) {
                slides.add(slide("statement", Map.of("kicker", kicker,
                        "text", clamp(topic.statement(), 96))));
            } else {
                slides.add(slide("bullets", Map.of("kicker", kicker,
                        "title", topic.title(), "bullets", topic.bullets())));
            }
        }

        slides.add(slide("closing", Map.of(
                "title", "谢谢观看",
                "subtitle", "内容来自你的需求描述，由 Atoms Demo 排版引擎完成版式")));

        List<String> topicTitles = topics.stream().map(Topic::title).toList();
        return new Plan(new SlideDeck(title, theme, Map.of("name", title), slides), theme, topicTitles, notes);
    }

    /**
     * Prefers the visitor's own headline over the raw prompt. A project name is already a
     * human-written label, so cleaning it is enough; otherwise the first clause of the prompt
     * becomes the cover title.
     */
    private String deckTitle(String projectName, String prompt) {
        String fromName = prettyTitle(projectName);
        if (fromName.length() >= 2) return fromName;
        for (String clause : clauses(prompt)) {
            String candidate = prettyTitle(clause);
            if (candidate.length() >= 2) return candidate;
        }
        return "未命名演示";
    }

    /** Promotes one sentence to a full-width statement page, skipping date/venue logistics. */
    private static String pickClaim(List<String> sentences) {
        for (String text : sentences) {
            if (text.length() >= 18 && !LOGISTICS.matcher(text).lookingAt()) return text;
        }
        for (String text : sentences) {
            if (text.length() >= 24) return text;
        }
        return null;
    }

    private Topic topic(String clause) {
        String value = stripListMarker(clause);
        if (value.length() < 4) return null;
        List<String> fragments = new ArrayList<>();
        for (String part : FRAGMENT.split(value)) {
            String fragment = part.strip();
            if (fragment.length() >= 3) fragments.add(fragment);
        }
        if (fragments.size() <= 1) {
            return new Topic(prettyTitle(value), List.of(), clamp(value, 110));
        }
        String title = prettyTitle(fragments.get(0));
        List<String> bullets = new ArrayList<>();
        for (int index = 1; index < fragments.size() && bullets.size() < 4; index++) {
            bullets.add(clamp(fragments.get(index), 52));
        }
        if (bullets.size() >= 1) return new Topic(title, bullets, null);
        return new Topic(title, List.of(), clamp(value, 110));
    }

    private List<Map<String, Object>> metrics(String prompt) {
        List<Map<String, Object>> stats = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String clause : clauses(prompt)) {
            Matcher matcher = METRIC.matcher(clause);
            while (matcher.find() && stats.size() < 4) {
                String value = matcher.group().strip();
                if (value.matches("^(?:19|20)\\d{2}\\s*年$")) continue;
                if (Pattern.compile("\\d+\\s*[月日]").matcher(clause).find()
                        && !value.matches(".*[%万亿倍元人天项页].*")) continue;
                if (!seen.add(value)) continue;
                stats.add(new LinkedHashMap<>(Map.of("value", value,
                        "label", clamp(clause, 22))));
            }
        }
        return stats;
    }

    private static List<String> clauses(String prompt) {
        List<String> clauses = new ArrayList<>();
        if (prompt == null) return clauses;
        for (String raw : SENTENCE.split(prompt.replace("\r", " "))) {
            String value = stripListMarker(raw.strip());
            if (value.length() < 4) continue;
            if (clauses.contains(value)) continue;
            clauses.add(value);
            if (clauses.size() >= 8) break;
        }
        return clauses;
    }

    private static String stripListMarker(String value) {
        return value.replaceFirst("^[-*•·\\d.、()（）\\s]+", "").strip();
    }

    private static String detectTheme(String prompt) {
        String text = prompt == null ? "" : prompt;
        if (containsAny(text, "商务", "汇报", "融资", "年度", "战略", "季度", "经营", "方案")) return "sky";
        if (containsAny(text, "营销", "品牌", "活动", "发布", "推广", "增长", "campaign")) return "swiss";
        if (containsAny(text, "极简", "设计", "作品", "摄影", "艺术", "杂志", "文化")) return "editorial";
        if (containsAny(text, "科技", "未来", "产品", "技术", "AI", "智能")) return "ink";
        return "swiss";
    }

    private static boolean containsAny(String text, String... words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) if (lower.contains(word.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static Map<String, Object> slide(String layout, Map<String, Object> body) {
        Map<String, Object> slide = new LinkedHashMap<>();
        slide.put("layout", layout);
        slide.putAll(body);
        return slide;
    }

    /** Turns "为「创意破界」文化节做一套提案，活动在…" into "创意破界文化节提案". */
    static String prettyTitle(String raw) {
        String original = raw == null ? "" : raw.strip();
        if (original.isEmpty()) return "";
        String value = original.replaceAll("[「」『』\"']", "")
                .replaceAll("(?:…|\\.{3,})+$", "").strip();
        String[] sentences = SENTENCE.split(value);
        if (sentences.length > 0 && !sentences[0].isBlank()) value = sentences[0].strip();
        value = value.replaceFirst("^(?:帮我|请帮我|请|我们|我|希望|需要|想要|准备|要|想)", "");
        value = value.replaceFirst("^(?:为|给|针对|围绕|关于|做|制作|生成|输出|写|策划)", "");
        value = value.replaceFirst("^(?:一[套份个])(?:关于)?", "");
        value = value.replaceAll("(?:做|制作|撰写|写|策划|生成|输出)(?:一[套份个])?(?=提案|方案|演示|汇报|报告|计划|总结|复盘|介绍|分享|发布|推广|宣讲|路演|规划)", "");
        value = value.replaceAll("[，。；;：:、\\s]+$", "").strip();
        if (value.length() < 2) value = original;
        return clamp(value, 22);
    }

    private static String clamp(String value, int max) {
        String text = value == null ? "" : value.strip();
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).strip() + "…";
    }

    private static String clean(String value, int max) {
        return value == null ? "" : clamp(value.replaceAll("\\s+", " ").strip(), max);
    }
}
