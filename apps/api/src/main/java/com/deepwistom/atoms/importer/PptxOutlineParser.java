package com.deepwistom.atoms.importer;

import com.deepwistom.atoms.slides.SlidePlanner.OutlineSlide;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads slide titles and bullet text straight out of a .pptx package. This is a text-level
 * reader: it keeps what the file actually says and deliberately copies no styling.
 */
public final class PptxOutlineParser {
    private static final Pattern SLIDE_ENTRY = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");
    private static final Pattern TEXT_RUN = Pattern.compile("<a:t>(.*?)</a:t>", Pattern.DOTALL);

    private PptxOutlineParser() {}

    public record Parsed(int slideCount, List<OutlineSlide> outline) {}

    private record SlideEntry(int number, String xml) {}

    public static Parsed parse(byte[] content) throws IOException {
        List<SlideEntry> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Matcher matcher = SLIDE_ENTRY.matcher(entry.getName());
                if (!matcher.matches() || entries.size() >= 60) continue;
                entries.add(new SlideEntry(Integer.parseInt(matcher.group(1)),
                        new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        if (entries.isEmpty()) return new Parsed(0, List.of());
        entries.sort(Comparator.comparingInt(SlideEntry::number));

        List<OutlineSlide> outline = new ArrayList<>();
        for (SlideEntry slide : entries) {
            String xml = slide.xml();
            List<String> texts = new ArrayList<>();
            Matcher matcher = TEXT_RUN.matcher(xml);
            while (matcher.find() && texts.size() < 12) {
                String value = unescape(matcher.group(1)).replaceAll("\\s+", " ").strip();
                if (value.isEmpty() || texts.contains(value)) continue;
                texts.add(value);
            }
            if (texts.isEmpty()) continue;
            String title = texts.get(0);
            List<String> bullets = new ArrayList<>();
            for (int position = 1; position < texts.size() && bullets.size() < 4; position++) {
                bullets.add(texts.get(position));
            }
            outline.add(new OutlineSlide(title, bullets));
        }
        return new Parsed(entries.size(), outline);
    }

    /** Page count for formats we store but cannot yet read as text. */
    public static int pdfPages(byte[] content) {
        String text = new String(content, StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile("/Type\\s*/Page[^s]").matcher(text);
        int count = 0;
        while (matcher.find() && count < 500) count++;
        return Math.max(1, count);
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
