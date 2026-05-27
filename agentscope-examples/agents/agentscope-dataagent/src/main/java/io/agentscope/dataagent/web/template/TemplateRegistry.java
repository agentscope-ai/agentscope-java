/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Registry of starter agent templates. Templates are loaded from two sources:
 *
 * <ol>
 *   <li><b>Bundled templates</b> on the classpath under {@code templates/<id>/template.json},
 *       discovered once at startup using a {@link ResourcePatternResolver}.
 *   <li><b>User overrides</b> on disk under {@code ${cwd}/.agentscope/templates/<id>/}, scanned
 *       lazily on every {@link #list()} / {@link #get(String)} call so users can drop in or edit
 *       a template without restarting.
 * </ol>
 *
 * <p>When the same id exists in both sources, the cwd override wins.
 *
 * <p>Each template directory is expected to contain a {@code template.json} (id, name,
 * description, tags) plus an arbitrary tree of files that get copied verbatim into a new agent's
 * workspace. {@link #instantiate(String, Path)} performs the copy with write-if-missing semantics
 * and atomic temp-file writes for individual file contents.
 */
@Component
public class TemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(TemplateRegistry.class);
    private static final int PREVIEW_CHARS = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataAgentBootstrap bootstrap;
    private final ResourcePatternResolver resolver;

    /** Template id → location on the classpath (URI of the template root directory). */
    private final Map<String, ClasspathTemplate> classpathTemplates = new LinkedHashMap<>();

    public TemplateRegistry(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    void scanClasspath() {
        try {
            Resource[] manifests = resolver.getResources("classpath*:templates/*/template.json");
            for (Resource manifest : manifests) {
                try (InputStream in = manifest.getInputStream()) {
                    TemplateMetadata meta = MAPPER.readValue(in, TemplateMetadata.class);
                    if (meta == null || meta.id() == null || meta.id().isBlank()) {
                        log.warn("Skipping classpath template missing id: {}", manifest.getURI());
                        continue;
                    }
                    URI manifestUri = manifest.getURI();
                    URI rootUri = parentUri(manifestUri);
                    classpathTemplates.put(meta.id(), new ClasspathTemplate(meta, rootUri));
                } catch (Exception e) {
                    log.warn(
                            "Failed to read classpath template {}: {}",
                            safeUri(manifest),
                            e.getMessage());
                }
            }
            log.info(
                    "TemplateRegistry: discovered {} bundled template(s): {}",
                    classpathTemplates.size(),
                    classpathTemplates.keySet());
        } catch (IOException e) {
            log.warn("TemplateRegistry: failed to scan classpath templates: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    /** Lists all templates, with cwd overrides taking precedence over bundled templates. */
    public List<TemplateSummary> list() {
        Map<String, TemplateSummary> merged = new LinkedHashMap<>();
        // Classpath first so cwd overrides replace them in iteration order.
        for (ClasspathTemplate t : classpathTemplates.values()) {
            merged.put(t.metadata.id(), summarize(t.metadata, classpathPreview(t)));
        }
        for (CwdTemplate t : listCwdTemplates()) {
            merged.put(t.metadata.id(), summarize(t.metadata, cwdPreview(t)));
        }
        return new ArrayList<>(merged.values());
    }

    /** Gets the full file listing for a template, or {@link Optional#empty()} if unknown. */
    public Optional<TemplateDetail> get(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        Optional<CwdTemplate> cwd = findCwdTemplate(id);
        if (cwd.isPresent()) {
            return Optional.of(detailFromCwd(cwd.get()));
        }
        ClasspathTemplate cp = classpathTemplates.get(id);
        if (cp != null) {
            return Optional.of(detailFromClasspath(cp));
        }
        return Optional.empty();
    }

    /**
     * Copies all files of the given template into {@code workspaceDir} with write-if-missing
     * semantics. Returns {@code false} if the template id is unknown.
     */
    public boolean instantiate(String id, Path workspaceDir) throws IOException {
        if (id == null || id.isBlank()) return false;
        Files.createDirectories(workspaceDir);

        Optional<CwdTemplate> cwd = findCwdTemplate(id);
        if (cwd.isPresent()) {
            copyFromFs(cwd.get().root, workspaceDir);
            return true;
        }
        ClasspathTemplate cp = classpathTemplates.get(id);
        if (cp != null) {
            for (LoadedFile lf : loadClasspathFiles(cp)) {
                if ("template.json".equals(lf.relPath())) continue;
                Path dest = workspaceDir.resolve(lf.relPath());
                if (Files.exists(dest)) continue;
                Path parent = dest.getParent();
                if (parent != null) Files.createDirectories(parent);
                writeAtomic(dest, lf.content());
            }
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    //  Cwd overrides (scanned on every call)
    // -----------------------------------------------------------------

    private List<CwdTemplate> listCwdTemplates() {
        Path dir = cwdTemplatesDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<CwdTemplate> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(
                            child -> {
                                Path manifest = child.resolve("template.json");
                                if (!Files.isRegularFile(manifest)) return;
                                try {
                                    TemplateMetadata meta =
                                            MAPPER.readValue(
                                                    manifest.toFile(), TemplateMetadata.class);
                                    if (meta == null || meta.id() == null || meta.id().isBlank()) {
                                        return;
                                    }
                                    out.add(new CwdTemplate(meta, child));
                                } catch (IOException e) {
                                    log.warn(
                                            "Failed to read cwd template {}: {}",
                                            child,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to list cwd templates dir {}: {}", dir, e.getMessage());
        }
        return out;
    }

    private Optional<CwdTemplate> findCwdTemplate(String id) {
        Path dir = cwdTemplatesDir().resolve(id);
        Path manifest = dir.resolve("template.json");
        if (!Files.isRegularFile(manifest)) return Optional.empty();
        try {
            TemplateMetadata meta = MAPPER.readValue(manifest.toFile(), TemplateMetadata.class);
            if (meta == null || meta.id() == null || meta.id().isBlank()) return Optional.empty();
            if (!id.equals(meta.id())) {
                // Manifest id mismatches directory name — trust the directory.
                meta = new TemplateMetadata(id, meta.name(), meta.description(), meta.tags());
            }
            return Optional.of(new CwdTemplate(meta, dir));
        } catch (IOException e) {
            log.warn("Failed to read cwd template {}: {}", manifest, e.getMessage());
            return Optional.empty();
        }
    }

    private Path cwdTemplatesDir() {
        return bootstrap.cwd().resolve(".agentscope").resolve("templates");
    }

    // -----------------------------------------------------------------
    //  Detail / file-listing builders
    // -----------------------------------------------------------------

    private TemplateDetail detailFromCwd(CwdTemplate t) {
        List<TemplateFile> files = new ArrayList<>();
        if (Files.isDirectory(t.root)) {
            try (Stream<Path> walk = Files.walk(t.root)) {
                walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(
                                p -> {
                                    String rel = t.root.relativize(p).toString().replace('\\', '/');
                                    if ("template.json".equals(rel)) return;
                                    files.add(new TemplateFile(rel, readUtf8(p)));
                                });
            } catch (IOException e) {
                log.warn("Failed to walk cwd template {}: {}", t.root, e.getMessage());
            }
        }
        return new TemplateDetail(
                t.metadata.id(),
                t.metadata.displayName(),
                t.metadata.description(),
                t.metadata.tagsOrEmpty(),
                files);
    }

    private TemplateDetail detailFromClasspath(ClasspathTemplate t) {
        List<TemplateFile> files = new ArrayList<>();
        try {
            for (LoadedFile lf : loadClasspathFiles(t)) {
                if ("template.json".equals(lf.relPath())) continue;
                files.add(
                        new TemplateFile(
                                lf.relPath(), new String(lf.content(), StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.warn(
                    "Failed to enumerate classpath template '{}': {}",
                    t.metadata.id(),
                    e.getMessage());
        }
        files.sort(Comparator.comparing(TemplateFile::path));
        return new TemplateDetail(
                t.metadata.id(),
                t.metadata.displayName(),
                t.metadata.description(),
                t.metadata.tagsOrEmpty(),
                files);
    }

    // -----------------------------------------------------------------
    //  Preview (first 600 chars of AGENTS.md)
    // -----------------------------------------------------------------

    private String cwdPreview(CwdTemplate t) {
        Path agentsMd = t.root.resolve("AGENTS.md");
        if (!Files.isRegularFile(agentsMd)) return "";
        return truncate(readUtf8(agentsMd));
    }

    private String classpathPreview(ClasspathTemplate t) {
        URL url = resourceUrl(t, "AGENTS.md");
        if (url == null) return "";
        try (InputStream in = url.openStream()) {
            return truncate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    // -----------------------------------------------------------------
    //  Copy implementations
    // -----------------------------------------------------------------

    private static void copyFromFs(Path templateRoot, Path workspaceDir) throws IOException {
        if (!Files.isDirectory(templateRoot)) return;
        try (Stream<Path> walk = Files.walk(templateRoot)) {
            List<Path> entries = walk.sorted().toList();
            for (Path src : entries) {
                String rel = templateRoot.relativize(src).toString().replace('\\', '/');
                if (rel.isEmpty()) continue;
                if ("template.json".equals(rel)) continue;
                Path dest = workspaceDir.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else if (!Files.exists(dest)) {
                    writeAtomic(dest, Files.readAllBytes(src));
                }
            }
        }
    }

    /**
     * Eagerly loads every regular file under a classpath-bundled template into memory. Returns
     * relative paths and raw byte contents.
     *
     * <p>Handles both filesystem (development) and JAR-bundled (production) classpath layouts.
     */
    private List<LoadedFile> loadClasspathFiles(ClasspathTemplate t) throws IOException {
        URI rootUri = t.rootUri;
        String scheme = rootUri.getScheme();
        List<LoadedFile> out = new ArrayList<>();
        if ("file".equals(scheme)) {
            Path root = Paths.get(rootUri);
            if (!Files.isDirectory(root)) return out;
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> entries = walk.sorted().toList();
                for (Path p : entries) {
                    if (Files.isDirectory(p)) continue;
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                }
            }
        } else if ("jar".equals(scheme)) {
            String s = rootUri.toString();
            int bang = s.indexOf("!/");
            if (bang < 0) return out;
            URI jarFile = URI.create(s.substring(0, bang + 2));
            String inJarPath = s.substring(bang + 2);
            if (!inJarPath.endsWith("/")) inJarPath = inJarPath + "/";
            try (FileSystem fs = openOrCreateJarFs(jarFile)) {
                Path root = fs.getPath(inJarPath);
                if (!Files.isDirectory(root)) return out;
                try (Stream<Path> walk = Files.walk(root)) {
                    List<Path> entries = walk.sorted().toList();
                    for (Path p : entries) {
                        if (Files.isDirectory(p)) continue;
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                    }
                }
            }
        } else {
            // Fallback: enumerate via a glob pattern.
            Resource[] children =
                    resolver.getResources("classpath*:templates/" + t.metadata.id() + "/**");
            String prefix = "templates/" + t.metadata.id() + "/";
            for (Resource child : children) {
                if (!child.isReadable()) continue;
                URI uri = safeUri(child);
                if (uri == null) continue;
                String us = uri.toString();
                int idx = us.indexOf(prefix);
                if (idx < 0) continue;
                String rel = us.substring(idx + prefix.length());
                if (rel.isEmpty() || rel.endsWith("/")) continue;
                try (InputStream in = child.getInputStream()) {
                    out.add(new LoadedFile(rel, in.readAllBytes()));
                }
            }
        }
        return out;
    }

    private static FileSystem openOrCreateJarFs(URI jarFile) throws IOException {
        try {
            return FileSystems.getFileSystem(jarFile);
        } catch (Exception ignore) {
            return FileSystems.newFileSystem(jarFile, Map.of());
        }
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static URL resourceUrl(ClasspathTemplate t, String relPath) {
        return Thread.currentThread()
                .getContextClassLoader()
                .getResource("templates/" + t.metadata.id() + "/" + relPath);
    }

    private static URI parentUri(URI manifestUri) {
        String s = manifestUri.toString();
        int slash = s.lastIndexOf('/');
        return URI.create(s.substring(0, slash + 1));
    }

    private static URI safeUri(Resource r) {
        try {
            return r.getURI();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readUtf8(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= PREVIEW_CHARS) return s;
        return s.substring(0, PREVIEW_CHARS);
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static TemplateSummary summarize(TemplateMetadata meta, String preview) {
        return new TemplateSummary(
                meta.id(),
                meta.displayName(),
                meta.description(),
                meta.tagsOrEmpty(),
                preview != null ? preview : "");
    }

    // -----------------------------------------------------------------
    //  Internal records
    // -----------------------------------------------------------------

    private record ClasspathTemplate(TemplateMetadata metadata, URI rootUri) {}

    private record CwdTemplate(TemplateMetadata metadata, Path root) {}

    private record LoadedFile(String relPath, byte[] content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TemplateMetadata(String id, String name, String description, List<String> tags) {

        String displayName() {
            return name != null && !name.isBlank() ? name : id;
        }

        List<String> tagsOrEmpty() {
            return tags != null ? tags : List.of();
        }
    }

    // -----------------------------------------------------------------
    //  Public records (API shape)
    // -----------------------------------------------------------------

    public record TemplateSummary(
            String id,
            String name,
            String description,
            List<String> tags,
            String agentsMdPreview) {}

    public record TemplateDetail(
            String id,
            String name,
            String description,
            List<String> tags,
            List<TemplateFile> files) {}

    public record TemplateFile(String path, String content) {}
}
