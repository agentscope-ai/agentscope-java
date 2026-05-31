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
import io.agentscope.core.legacy.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Writable extension of {@link FilesystemBackedSkillRepository}. Adds {@code save} / {@code delete}
 * support so the agent can persist skills it authors itself via {@code skill_manage}.
 *
 * <p>All writes go through {@link AbstractFilesystem} so per-user namespacing, sandbox overlay
 * routing, and remote KV backends are honored transparently. Deletes are non-destructive: skill
 * directories are moved under {@code .archive/<name>-<ts>/} rather than removed, matching the
 * Curator's never-delete invariant.
 *
 * <p>This class deliberately does not run security scans or staging routing on its own. Those
 * concerns belong to {@code SkillManageTool} (which composes this repository with the scanner) and
 * the promotion gate. Keeping the repository thin lets it remain a drop-in replacement for the
 * read-only sibling whenever a caller just needs raw write access.
 */
@SuppressWarnings("deprecation")
public class WritableFilesystemSkillRepository extends FilesystemBackedSkillRepository {

    private static final Logger log =
            LoggerFactory.getLogger(WritableFilesystemSkillRepository.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String ARCHIVE_PREFIX = ".archive";

    private volatile boolean writeable = true;

    public WritableFilesystemSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,
            Supplier<RuntimeContext> contextSupplier,
            String source) {
        super(filesystem, skillsRelativeDir, contextSupplier, source);
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (!writeable) {
            log.warn("WritableFilesystemSkillRepository is currently read-only; save() ignored");
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
        if (!writeable) {
            log.warn("WritableFilesystemSkillRepository is currently read-only; delete() ignored");
            return false;
        }
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        AgentSkill existing = getSkill(skillName);
        if (existing == null) {
            return false;
        }
        RuntimeContext ctx = resolveContext();
        AbstractFilesystem fs = filesystem();
        String src = skillDirRelative(skillName);
        String archiveDest = archiveDestRelative(skillName);
        try {
            // Best-effort move (non-destructive archive). Falls back to read+write+delete on
            // backends that don't support native move; CompositeFilesystem already does this.
            WriteResult moveResult = fs.move(ctx, src, archiveDest);
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

    // ---------------------------------------------------------------------
    //  Internal write helpers
    // ---------------------------------------------------------------------

    private void writeSkill(AgentSkill skill) {
        RuntimeContext ctx = resolveContext();
        AbstractFilesystem fs = filesystem();

        // Serialize the skill back to markdown (frontmatter + body).
        String skillMd = toMarkdown(skill);
        String skillDir = skillDirRelative(skill.getName());
        String skillMdPath = skillDir + "/" + SKILL_FILE;

        // Build the upload list: SKILL.md + every resource path.
        java.util.List<Map.Entry<String, byte[]>> uploads = new java.util.ArrayList<>();
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

        fs.uploadFiles(ctx, uploads);
    }

    // ---------------------------------------------------------------------
    //  Sub-file operations used by SkillManageTool (write_file / remove_file / patch).
    //  These bypass save(AgentSkill) so callers can mutate a single resource without
    //  rewriting SKILL.md, which would otherwise destroy frontmatter ordering and
    //  resource ordering carried by the in-memory AgentSkill.
    // ---------------------------------------------------------------------

    /**
     * Read a raw file under {@code <skillsRelativeDir>/<skillName>/<relPath>}. Used by
     * {@code SkillManageTool.patch}. Returns {@code null} when the file does not exist or read
     * fails.
     */
    public String readSkillFile(String skillName, String relPath) {
        if (skillName == null || skillName.isBlank() || relPath == null || relPath.isBlank()) {
            return null;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            var rr = filesystem().read(resolveContext(), path, 0, 0);
            if (rr.isSuccess() && rr.fileData() != null) {
                return rr.fileData().content();
            }
        } catch (Exception e) {
            log.debug("readSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
        }
        return null;
    }

    /**
     * Write (or overwrite) a raw file under {@code <skillsRelativeDir>/<skillName>/<relPath>}.
     * Caller is responsible for validating {@code relPath} (allowed subdirs, path traversal,
     * size limits). Returns {@code true} on success.
     */
    public boolean writeSkillFile(String skillName, String relPath, String content) {
        if (!writeable) {
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
            filesystem()
                    .uploadFiles(
                            resolveContext(),
                            java.util.List.of(
                                    new AbstractMap.SimpleImmutableEntry<>(
                                            path, content.getBytes(StandardCharsets.UTF_8))));
            return true;
        } catch (Exception e) {
            log.warn("writeSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
            return false;
        }
    }

    /**
     * Delete a single raw file under {@code <skillsRelativeDir>/<skillName>/<relPath>}.
     * Idempotent: missing files are treated as success. Returns {@code true} on success.
     */
    public boolean deleteSkillFile(String skillName, String relPath) {
        if (!writeable) {
            return false;
        }
        if (skillName == null || skillName.isBlank() || relPath == null || relPath.isBlank()) {
            return false;
        }
        String path = skillDirRelative(skillName) + "/" + relPath;
        try {
            WriteResult r = filesystem().delete(resolveContext(), path);
            return r.isSuccess();
        } catch (Exception e) {
            log.warn("deleteSkillFile({}, {}) failed: {}", skillName, relPath, e.getMessage());
            return false;
        }
    }

    /** Resolve the skill root path (for callers building sub-paths). */
    public String resolveSkillRoot(String skillName) {
        return skillDirRelative(skillName);
    }

    private String skillDirRelative(String name) {
        return skillsRelativeDir() + "/" + name;
    }

    private String archiveDestRelative(String name) {
        // .archive/<name>-<YYYYMMDDHHMMSS>/
        String ts =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(Instant.now());
        return skillsRelativeDir() + "/" + ARCHIVE_PREFIX + "/" + name + "-" + ts;
    }

    /**
     * Reassemble a skill back into a markdown document with YAML frontmatter. The reverse of
     * {@code MarkdownSkillParser} but tolerant: emits whatever scalar/list/map metadata the
     * {@link AgentSkill} carries.
     */
    static String toMarkdown(AgentSkill skill) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        // Build an ordered map so name / description lead the frontmatter.
        java.util.LinkedHashMap<String, Object> ordered = new java.util.LinkedHashMap<>();
        ordered.put("name", skill.getName());
        ordered.put("description", skill.getDescription());
        Map<String, Object> meta = skill.getMetadata();
        if (meta != null) {
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String k = e.getKey();
                if ("name".equals(k) || "description".equals(k)) {
                    continue; // already emitted
                }
                ordered.put(k, e.getValue());
            }
        }

        String fm = yaml.dump(ordered).trim();
        String body = skill.getSkillContent() != null ? skill.getSkillContent() : "";
        return "---\n" + fm + "\n---\n" + body;
    }
}
