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
package io.agentscope.harness.agent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link AgentSkillRepository} backed by an {@link AbstractFilesystem}, with lazy resource
 * access via {@link LazyResourceCapable}. Supersedes the legacy
 * {@code FilesystemBackedSkillRepository} + {@code WritableFilesystemSkillRepository} pair:
 * writability is now a constructor parameter rather than a subclass.
 *
 * <p>Skills are discovered by globbing {@code SKILL.md} under {@code skillsRelativeDir}. Only
 * the SKILL.md text is read at registration time; resources (references, scripts, assets) are
 * fetched on demand through {@link #resourcesFor(String, RuntimeContext)} and consumed by the
 * harness skill runtime when {@code load_skill_through_path} misses the in-memory map.
 *
 * <p>Per-user namespacing and sandbox routing are honored transparently because every
 * filesystem call passes the current {@link RuntimeContext}. The supplier pattern from the
 * legacy class is retained so each invocation observes whatever context the caller has merged.
 *
 * <p>Deletes are non-destructive: skill directories are moved under
 * {@code .archive/<name>-<ts>/} rather than removed, matching the curator's never-delete
 * invariant.
 */
@SuppressWarnings("deprecation")
public class WorkspaceSkillRepository implements AgentSkillRepository, LazyResourceCapable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSkillRepository.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String DEFAULT_SOURCE = "workspace";
    private static final String ARCHIVE_PREFIX = ".archive";

    private final AbstractFilesystem filesystem;
    private final String skillsRelativeDir;
    private final Supplier<RuntimeContext> contextSupplier;
    private final String source;

    private volatile boolean writable;

    /**
     * Creates a read-only repository.
     *
     * @param filesystem        backing filesystem (non-null)
     * @param skillsRelativeDir relative directory holding {@code <skill>/SKILL.md} (non-null)
     * @param contextSupplier   supplies the {@link RuntimeContext} on each call (non-null)
     */
    public WorkspaceSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,
            Supplier<RuntimeContext> contextSupplier) {
        this(filesystem, skillsRelativeDir, contextSupplier, null, false);
    }

    /**
     * Creates a writable repository (matches the legacy
     * {@code WritableFilesystemSkillRepository} default).
     */
    public WorkspaceSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,
            Supplier<RuntimeContext> contextSupplier,
            String source) {
        this(filesystem, skillsRelativeDir, contextSupplier, source, true);
    }

    /**
     * Creates a repository with explicit source and writability.
     *
     * @param filesystem        backing filesystem (non-null)
     * @param skillsRelativeDir relative directory holding {@code <skill>/SKILL.md} (non-null)
     * @param contextSupplier   supplies the {@link RuntimeContext} on each call (non-null)
     * @param source            source identifier attached to loaded skills; falls back to
     *                          {@code "workspace"} when null or blank
     * @param writable          whether {@link #save} and {@link #delete} are permitted
     */
    public WorkspaceSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,
            Supplier<RuntimeContext> contextSupplier,
            String source,
            boolean writable) {
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.skillsRelativeDir = Objects.requireNonNull(skillsRelativeDir, "skillsRelativeDir");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.source = (source == null || source.isBlank()) ? DEFAULT_SOURCE : source;
        this.writable = writable;
    }

    // =========================================================================
    //  Read path (AgentSkillRepository)
    // =========================================================================

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (AgentSkill skill : getAllSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        List<AgentSkill> skills = getAllSkills();
        List<String> names = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            names.add(skill.getName());
        }
        return names;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        RuntimeContext ctx = currentContext();
        GlobResult glob;
        try {
            glob = filesystem.glob(ctx, SKILL_FILE, skillsRelativeDir);
        } catch (Exception e) {
            log.debug("Filesystem glob for skills failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        if (!glob.isSuccess() || glob.matches() == null || glob.matches().isEmpty()) {
            return Collections.emptyList();
        }

        List<AgentSkill> skills = new ArrayList<>(glob.matches().size());
        for (FileInfo fi : glob.matches()) {
            String path = fi.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            if (hasMetadataAncestor(path, skillsRelativeDir)) {
                continue;
            }
            try {
                ReadResult rr = filesystem.read(ctx, path, 0, 0);
                if (!rr.isSuccess() || rr.fileData() == null || rr.fileData().content() == null) {
                    continue;
                }
                // Resources are intentionally left null here; harness skill runtime resolves
                // them lazily via resourcesFor(name, ctx) so per-call namespace switching is
                // honored without preloading every byte.
                skills.add(SkillUtil.createFrom(rr.fileData().content(), null, source));
            } catch (Exception e) {
                log.warn("Failed to load skill from '{}': {}", path, e.getMessage());
            }
        }
        return skills;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("filesystem", skillsRelativeDir, writable);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public boolean isWriteable() {
        return writable;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writable = writeable;
    }

    // =========================================================================
    //  Lazy resources (LazyResourceCapable)
    // =========================================================================

    @Override
    public SkillResources resourcesFor(String skillName, RuntimeContext ctx) {
        if (skillName == null || skillName.isBlank()) {
            return SkillResources.empty();
        }
        RuntimeContext effectiveCtx = ctx != null ? ctx : RuntimeContext.empty();
        return new FilesystemSkillResources(filesystem, skillDirRelative(skillName), effectiveCtx);
    }

    // =========================================================================
    //  Write path (save / delete)
    // =========================================================================

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (!writable) {
            log.warn("WorkspaceSkillRepository is currently read-only; save() ignored");
            return false;
        }
        if (skills == null || skills.isEmpty()) {
            return false;
        }
        boolean allOk = true;
        for (AgentSkill skill : skills) {
            if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
                allOk = false;
                continue;
            }
            if (!force && skillExists(skill.getName())) {
                log.debug("Skill '{}' already exists; skipping (force=false)", skill.getName());
                allOk = false;
                continue;
            }
            try {
                writeSkill(skill);
            } catch (Exception e) {
                log.warn("Failed to save skill '{}': {}", skill.getName(), e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    @Override
    public boolean delete(String skillName) {
        if (!writable) {
            log.warn("WorkspaceSkillRepository is currently read-only; delete() ignored");
            return false;
        }
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        AgentSkill existing = getSkill(skillName);
        if (existing == null) {
            return false;
        }
        RuntimeContext ctx = currentContext();
        String src = skillDirRelative(skillName);
        String archiveDest = archiveDestRelative(skillName);
        try {
            WriteResult moveResult = filesystem.move(ctx, src, archiveDest);
            if (!moveResult.isSuccess()) {
                log.warn(
                        "Failed to archive skill '{}' from {} to {}: {}",
                        skillName,
                        src,
                        archiveDest,
                        moveResult.error());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Exception archiving skill '{}': {}", skillName, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    //  Sub-file operations (used by SkillManageTool for write_file / remove_file / patch)
    // =========================================================================

    /**
     * Reads a single raw file under {@code <skillsRelativeDir>/<skillName>/<relPath>}.
     * Returns {@code null} when the file does not exist or read fails.
     */
    public String readSkillFile(String skillName, String relPath) {
        if (skillName == null || skillName.isBlank() || relPath == null || relPath.isBlank()) {
            return null;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            ReadResult rr = filesystem.read(currentContext(), path, 0, 0);
            if (rr.isSuccess() && rr.fileData() != null) {
                return rr.fileData().content();
            }
        } catch (Exception e) {
            log.debug("readSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
        }
        return null;
    }

    /**
     * Writes (or overwrites) a single raw file under {@code <skillsRelativeDir>/<skillName>/<relPath>}.
     * Caller is responsible for validating {@code relPath} (allowed subdirs, path traversal,
     * size limits). Returns {@code true} on success.
     */
    public boolean writeSkillFile(String skillName, String relPath, String content) {
        if (!writable) {
            return false;
        }
        if (skillName == null
                || skillName.isBlank()
                || relPath == null
                || relPath.isBlank()
                || content == null) {
            return false;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            filesystem.uploadFiles(
                    currentContext(),
                    List.of(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    path, content.getBytes(StandardCharsets.UTF_8))));
            return true;
        } catch (Exception e) {
            log.warn("writeSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a single raw file under {@code <skillsRelativeDir>/<skillName>/<relPath>}.
     * Idempotent: missing files are treated as success.
     */
    public boolean deleteSkillFile(String skillName, String relPath) {
        if (!writable) {
            return false;
        }
        if (skillName == null || skillName.isBlank() || relPath == null || relPath.isBlank()) {
            return false;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            WriteResult r = filesystem.delete(currentContext(), path);
            return r.isSuccess();
        } catch (Exception e) {
            log.warn("deleteSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
            return false;
        }
    }

    /** Returns the relative skill root path for callers building sub-paths. */
    public String resolveSkillRoot(String skillName) {
        return skillDirRelative(skillName);
    }

    // =========================================================================
    //  Accessors (used by harness skill runtime and supporting code)
    // =========================================================================

    public AbstractFilesystem filesystem() {
        return filesystem;
    }

    public String skillsRelativeDir() {
        return skillsRelativeDir;
    }

    public RuntimeContext resolveContext() {
        return currentContext();
    }

    // =========================================================================
    //  Internals
    // =========================================================================

    private void writeSkill(AgentSkill skill) {
        RuntimeContext ctx = currentContext();
        String skillMd = toMarkdown(skill);
        String skillDir = skillDirRelative(skill.getName());
        String skillMdPath = skillDir + "/" + SKILL_FILE;

        List<Map.Entry<String, byte[]>> uploads = new ArrayList<>();
        uploads.add(
                new AbstractMap.SimpleImmutableEntry<>(
                        skillMdPath, skillMd.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> resources = skill.getResources();
        if (resources != null) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String relPath = entry.getKey();
                String content = entry.getValue();
                if (relPath == null || relPath.isBlank() || content == null) {
                    continue;
                }
                if (relPath.startsWith("/") || relPath.contains("..")) {
                    log.warn(
                            "Skipping resource with unsafe path '{}' in skill '{}'",
                            relPath,
                            skill.getName());
                    continue;
                }
                String targetPath = skillDir + "/" + relPath;
                uploads.add(
                        new AbstractMap.SimpleImmutableEntry<>(
                                targetPath, content.getBytes(StandardCharsets.UTF_8)));
            }
        }

        filesystem.uploadFiles(ctx, uploads);
    }

    private String skillDirRelative(String name) {
        return skillsRelativeDir + "/" + name;
    }

    private String archiveDestRelative(String name) {
        String ts =
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
        return skillsRelativeDir + "/" + ARCHIVE_PREFIX + "/" + name + "-" + ts;
    }

    private RuntimeContext currentContext() {
        RuntimeContext ctx = contextSupplier.get();
        return ctx != null ? ctx : RuntimeContext.empty();
    }

    /**
     * Reassemble a skill back into a markdown document with YAML frontmatter. The reverse of
     * {@code MarkdownSkillParser}, tolerant of whatever scalar/list/map metadata the
     * {@link AgentSkill} carries.
     */
    static String toMarkdown(AgentSkill skill) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("name", skill.getName());
        ordered.put("description", skill.getDescription());
        Map<String, Object> meta = skill.getMetadata();
        if (meta != null) {
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String k = e.getKey();
                if ("name".equals(k) || "description".equals(k)) {
                    continue;
                }
                ordered.put(k, e.getValue());
            }
        }

        String fm = yaml.dump(ordered).trim();
        String body = skill.getSkillContent() != null ? skill.getSkillContent() : "";
        return "---\n" + fm + "\n---\n" + body;
    }

    /**
     * Returns {@code true} when the path represents a skill living inside a metadata subtree
     * (e.g. {@code _drafts/}, {@code .archive/}, {@code .audit/}, {@code .backups/}) directly
     * under {@code base}. Looks for the pattern {@code "/<base>/<x>/"} where {@code <x>} starts
     * with {@code _} or {@code .}.
     *
     * <p>Special-cases {@code base == "."} and {@code base == ""}: in those cases checks
     * whether the path's first segment starts with {@code _} or {@code .}, so a workspace-root
     * scan still filters {@code .skills-cache/} and similar metadata trees.
     *
     * <p>Backend-agnostic: works on absolute paths (LocalFilesystem default), virtual paths,
     * and namespaced paths alike, because it matches a substring rather than relying on the
     * path being relative to the search root.
     */
    static boolean hasMetadataAncestor(String path, String base) {
        if (path == null || base == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        String b = base.replace('\\', '/');

        // base = "." or ""  →  any path whose first non-leading-slash segment starts with
        // '_' or '.' is metadata. Avoids the marker "/./" matching nothing.
        if (b.isEmpty() || ".".equals(b)) {
            String trimmed = normalized.startsWith("/") ? normalized.substring(1) : normalized;
            int slash = trimmed.indexOf('/');
            if (slash <= 0) {
                return false;
            }
            char first = trimmed.charAt(0);
            return first == '_' || first == '.';
        }

        if (b.startsWith("/")) {
            b = b.substring(1);
        }
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String marker = "/" + b + "/";
        int idx = normalized.indexOf(marker);
        if (idx < 0) {
            if (normalized.startsWith(b + "/")) {
                int afterBase = b.length() + 1;
                if (afterBase >= normalized.length()) {
                    return false;
                }
                char first = normalized.charAt(afterBase);
                return first == '_' || first == '.';
            }
            return false;
        }
        int afterBase = idx + marker.length();
        if (afterBase >= normalized.length()) {
            return false;
        }
        char first = normalized.charAt(afterBase);
        return first == '_' || first == '.';
    }

    // =========================================================================
    //  Lazy resource accessor implementation
    // =========================================================================

    private static final class FilesystemSkillResources implements SkillResources {

        private final AbstractFilesystem fs;
        private final String skillDir;
        private final RuntimeContext capturedCtx;

        FilesystemSkillResources(AbstractFilesystem fs, String skillDir, RuntimeContext ctx) {
            this.fs = fs;
            this.skillDir = skillDir;
            this.capturedCtx = ctx;
        }

        @Override
        public Optional<String> read(String relativePath) {
            String safe = sanitize(relativePath);
            if (safe == null) {
                return Optional.empty();
            }
            try {
                ReadResult r = fs.read(capturedCtx, skillDir + "/" + safe, 0, 0);
                if (r.isSuccess() && r.fileData() != null && r.fileData().content() != null) {
                    return Optional.of(r.fileData().content());
                }
            } catch (Exception e) {
                log.debug("SkillResources.read({}) failed: {}", relativePath, e.getMessage());
            }
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> readBinary(String relativePath) {
            String safe = sanitize(relativePath);
            if (safe == null) {
                return Optional.empty();
            }
            String full = skillDir + "/" + safe;
            try {
                List<FileDownloadResponse> resps = fs.downloadFiles(capturedCtx, List.of(full));
                if (resps == null || resps.isEmpty()) {
                    return Optional.empty();
                }
                FileDownloadResponse resp = resps.get(0);
                if (resp != null && resp.isSuccess() && resp.content() != null) {
                    return Optional.of(resp.content());
                }
            } catch (Exception e) {
                log.debug("SkillResources.readBinary({}) failed: {}", relativePath, e.getMessage());
            }
            return Optional.empty();
        }

        @Override
        public List<String> list() {
            try {
                GlobResult g = fs.glob(capturedCtx, "**/*", skillDir);
                if (!g.isSuccess() || g.matches() == null || g.matches().isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> rels = new ArrayList<>(g.matches().size());
                for (FileInfo fi : g.matches()) {
                    if (fi == null || fi.path() == null || fi.isDirectory()) {
                        continue;
                    }
                    String rel = relativeTo(fi.path(), skillDir);
                    if (rel == null || rel.isBlank() || SKILL_FILE.equals(rel)) {
                        continue;
                    }
                    rels.add(rel);
                }
                Collections.sort(rels);
                return rels;
            } catch (Exception e) {
                log.debug("SkillResources.list() failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        }

        private static String sanitize(String relativePath) {
            if (relativePath == null || relativePath.isBlank()) {
                return null;
            }
            String s = relativePath.replace('\\', '/');
            if (s.startsWith("/") || s.contains("..")) {
                return null;
            }
            return s;
        }

        /**
         * Strips {@code skillDir} prefix from {@code path}, handling absolute / virtual /
         * namespaced stores uniformly by matching the last occurrence of the directory
         * marker.
         */
        private static String relativeTo(String path, String skillDir) {
            String normPath = path.replace('\\', '/');
            String normDir = skillDir.replace('\\', '/');
            if (normDir.startsWith("/")) {
                normDir = normDir.substring(1);
            }
            if (normDir.endsWith("/")) {
                normDir = normDir.substring(0, normDir.length() - 1);
            }
            String marker = "/" + normDir + "/";
            int idx = normPath.lastIndexOf(marker);
            if (idx >= 0) {
                return normPath.substring(idx + marker.length());
            }
            String prefix = normDir + "/";
            if (normPath.startsWith(prefix)) {
                return normPath.substring(prefix.length());
            }
            return normPath;
        }
    }
}
