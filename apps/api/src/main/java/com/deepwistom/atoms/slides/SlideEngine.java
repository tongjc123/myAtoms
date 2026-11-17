package com.deepwistom.atoms.slides;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the shared rendering engine once. Seeded templates and generated decks both receive
 * these exact bytes, so a bug fixed here is fixed everywhere after the next seed.
 */
@Component
public class SlideEngine {
    public static final String CSS = "deck.css";
    public static final String JS = "deck.js";
    private static final String ROOT = "slide-engine/";

    private final Map<String, byte[]> files;

    public SlideEngine() {
        Map<String, byte[]> loaded = new LinkedHashMap<>();
        loaded.put(CSS, read(CSS));
        loaded.put(JS, read(JS));
        this.files = Map.copyOf(loaded);
    }

    /** Engine files keyed by their path inside a deck. */
    public Map<String, byte[]> files() { return files; }

    public byte[] css() { return files.get(CSS); }

    public byte[] js() { return files.get(JS); }

    public boolean isEngineFile(String path) { return files.containsKey(path); }

    private static byte[] read(String name) {
        ClassPathResource resource = new ClassPathResource(ROOT + name);
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("Missing slide engine asset " + name, error);
        }
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
