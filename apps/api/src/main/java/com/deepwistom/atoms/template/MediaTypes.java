package com.deepwistom.atoms.template;

import java.util.Locale;
import java.util.Map;

/** Content types the preview surface is allowed to serve. Anything else falls back to octet-stream. */
public final class MediaTypes {
    private MediaTypes() {}

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    public static String of(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXTENSION.getOrDefault(extension, "application/octet-stream");
    }

    /** Rejects absolute paths, traversal, backslashes and anything outside a conservative allow list. */
    public static String normalize(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        if (value == null) throw new IllegalArgumentException("empty preview path");
        if (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty() || value.length() > 300 || value.contains("..") || value.contains("\\")
                || value.contains("\u0000") || !value.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("unsafe preview path");
        }
        return value;
    }
}
