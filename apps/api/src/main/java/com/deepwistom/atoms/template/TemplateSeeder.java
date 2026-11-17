package com.deepwistom.atoms.template;

import com.deepwistom.atoms.slides.DeckRenderer;
import com.deepwistom.atoms.slides.SlideDeck;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Seeds the catalogue from {@code classpath:template-seed}. A bundle whose files changed becomes a
 * new immutable template version instead of overwriting the previous one, so cloned projects that
 * reference older versions keep their exact snapshot.
 */
@Component
public class TemplateSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TemplateSeeder.class);
    private static final String ROOT = "template-seed/";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final String ENGINE_DECK = "slide-deck";

    private final TemplateRepository templates;
    private final ObjectMapper json;
    private final DeckRenderer renderer;

    public TemplateSeeder(TemplateRepository templates, ObjectMapper json, DeckRenderer renderer) {
        this.templates = templates;
        this.json = json;
        this.renderer = renderer;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Resource> manifests;
        try {
            manifests = new ArrayList<>(List.of(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + ROOT + "*/manifest.json")));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read bundled templates", error);
        }
        manifests.sort((left, right) -> left.getFilename().compareTo(right.getFilename()));
        for (Resource manifest : manifests) {
            try {
                seed(manifest);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot seed template " + manifest, error);
            }
        }
    }

    private void seed(Resource manifestResource) throws IOException {
        Map<?, ?> manifest;
        try (InputStream input = manifestResource.getInputStream()) {
            manifest = json.readValue(input, Map.class);
        }
        String slug = text(manifest, "slug", true);
        if (!slug.matches("[a-z0-9][a-z0-9-]{1,47}")) throw new IllegalStateException("Bad template slug: " + slug);

        String engineMode = text(manifest, "engine", false);
        List<String> files = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        if (ENGINE_DECK.equals(engineMode)) {
            // Slides keep only their content spec in the repository; index.html and the shared
            // engine are rendered from the same code the generator uses at run time.
            SlideDeck deck = json.readValue(read(slug, "deck.json"), SlideDeck.class);
            files.add("index.html");
            contents.add(renderer.files(deck).get("index.html"));
        } else {
            files = new ArrayList<>(strings(manifest, "files"));
            for (String path : files) contents.add(read(slug, path));
        }

        String entry = text(manifest, "entry", false);
        if (entry == null || entry.isBlank()) entry = "index.html";
        if (!files.contains(entry)) throw new IllegalStateException("Template " + slug + " is missing entry " + entry);
        String cover = text(manifest, "cover", false);
        if (cover != null && !cover.isBlank() && !files.contains(cover)) {
            throw new IllegalStateException("Template " + slug + " declares a missing cover " + cover);
        }

        MessageDigest digest = sha256();
        long totalBytes = 0;
        for (int index = 0; index < files.size(); index++) {
            byte[] content = contents.get(index);
            if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("Template " + slug + " file " + files.get(index)
                        + " has an unsupported size");
            }
            totalBytes += content.length;
            digest.update(files.get(index).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(sha256().digest(content));
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        String name = text(manifest, "name", true);
        String type = text(manifest, "type", true);
        String category = text(manifest, "category", true);
        String summary = text(manifest, "summary", true);
        String description = text(manifest, "description", false);
        if (description == null || description.isBlank()) description = summary;
        String author = text(manifest, "author", false);
        if (author == null || author.isBlank()) author = "Atoms Demo 自研示例";
        String sourceUrl = text(manifest, "sourceUrl", false);
        String license = text(manifest, "license", false);
        int sortOrder = manifest.get("sortOrder") instanceof Number number ? number.intValue() : 0;

        var existing = templates.find(slug).orElse(null);
        if (existing == null) {
            String templateId = UUID.randomUUID().toString();
            templates.insert(templateId, slug, name, type, category, summary, description, author,
                    sourceUrl, license, cover, sortOrder, now);
            String versionId = UUID.randomUUID().toString();
            templates.insertVersion(new TemplateRepository.VersionRow(versionId, templateId, 1, hash, entry,
                    files.size(), totalBytes, now));
            for (int index = 0; index < files.size(); index++) {
                templates.insertFile(versionId, files.get(index), MediaTypes.of(files.get(index)), contents.get(index));
            }
            templates.setCurrentVersion(templateId, versionId, now);
            log.info("Seeded template {} v1 ({} files, {} bytes)", slug, files.size(), totalBytes);
            return;
        }

        templates.updateMetadata(existing.id(), name, category, summary, description, author, sourceUrl,
                license, cover, sortOrder, now);
        String currentHash = templates.currentVersion(existing.id())
                .map(TemplateRepository.VersionRow::contentHash).orElse(null);
        if (hash.equals(currentHash)) return;

        int versionNo = templates.nextVersionNo(existing.id());
        String versionId = UUID.randomUUID().toString();
        templates.insertVersion(new TemplateRepository.VersionRow(versionId, existing.id(), versionNo, hash,
                entry, files.size(), totalBytes, now));
        for (int index = 0; index < files.size(); index++) {
            templates.insertFile(versionId, files.get(index), MediaTypes.of(files.get(index)), contents.get(index));
        }
        templates.setCurrentVersion(existing.id(), versionId, now);
        log.info("Template {} content changed: published v{}", slug, versionNo);
    }

    private byte[] read(String slug, String path) throws IOException {
        String safe = MediaTypes.normalize(path, null);
        ClassPathResource resource = new ClassPathResource(ROOT + slug + "/" + safe);
        if (!resource.exists()) throw new IllegalStateException("Missing template resource " + resource);
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static String text(Map<?, ?> manifest, String key, boolean required) {
        Object value = manifest.get(key);
        if (value instanceof String string && !string.isBlank()) return string.strip();
        if (required) throw new IllegalStateException("Template manifest is missing " + key);
        return null;
    }

    private static List<String> strings(Map<?, ?> manifest, String key) {
        if (!(manifest.get(key) instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("Template manifest is missing " + key);
        }
        return list.stream().map(value -> String.valueOf(value)).toList();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
