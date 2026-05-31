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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.legacy.skill.AgentSkill;
import io.agentscope.core.legacy.skill.util.SkillUtil;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.skill.WritableFilesystemSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillAuditLog;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Agent-callable tool that lets a {@code ReActAgent} create, edit, patch, and archive skills in
 * its workspace. Ported from the hermes-agent {@code skill_manage} tool surface so existing skill
 * authoring prompts stay portable.
 *
 * <p>Six actions, dispatched on the {@code action} parameter:
 * <ul>
 *   <li>{@code create} — write a brand-new {@code SKILL.md} (frontmatter required)</li>
 *   <li>{@code edit} — full-rewrite the {@code SKILL.md} of an existing skill</li>
 *   <li>{@code patch} — targeted find-and-replace within {@code SKILL.md} or a support file</li>
 *   <li>{@code write_file} — add/overwrite a single file under {@code references/} /
 *       {@code templates/} / {@code scripts/} / {@code assets/}</li>
 *   <li>{@code remove_file} — delete a single support file</li>
 *   <li>{@code delete} — archive the entire skill directory (non-destructive)</li>
 * </ul>
 *
 * <p><b>Staging</b>: by default ({@link SkillManageConfig#autoPromote()} = false), {@code create}
 * writes to {@code skills/_drafts/<name>/}; {@code edit}/{@code patch}/{@code write_file}/{@code
 * remove_file}/{@code delete} resolve the skill by name across both draft and main directories
 * (drafts win on collision). Set {@code autoPromote=true} to write straight to {@code skills/}.
 */
public class SkillManageTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SkillManageTool.class);

    public static final String NAME = "skill_manage";

    // Validation constants (mirrored from hermes tools/skill_manager_tool.py)
    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_DESCRIPTION_LENGTH = 1024;
    static final int MAX_SKILL_CONTENT_CHARS = 100_000;
    static final int MAX_SKILL_FILE_BYTES = 1_048_576; // 1 MiB
    static final Pattern VALID_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    static final Set<String> ALLOWED_SUBDIRS =
            Set.of("references", "templates", "scripts", "assets");

    /** Repository pointing at the live skills root (e.g. {@code skills/}). */
    private final WritableFilesystemSkillRepository mainRepo;

    /** Repository pointing at the draft staging dir (e.g. {@code skills/_drafts/}). */
    private final WritableFilesystemSkillRepository draftsRepo;

    /** Optional telemetry sidecar; null disables provenance + counter writes. */
    private final SkillUsageStore usageStore;

    /** Optional audit log; null disables auditing. */
    private final SkillAuditLog auditLog;

    private final SkillManageConfig config;

    public SkillManageTool(
            WritableFilesystemSkillRepository mainRepo,
            WritableFilesystemSkillRepository draftsRepo,
            SkillManageConfig config) {
        this(mainRepo, draftsRepo, config, null, null);
    }

    public SkillManageTool(
            WritableFilesystemSkillRepository mainRepo,
            WritableFilesystemSkillRepository draftsRepo,
            SkillManageConfig config,
            SkillUsageStore usageStore) {
        this(mainRepo, draftsRepo, config, usageStore, null);
    }

    public SkillManageTool(
            WritableFilesystemSkillRepository mainRepo,
            WritableFilesystemSkillRepository draftsRepo,
            SkillManageConfig config,
            SkillUsageStore usageStore,
            SkillAuditLog auditLog) {
        this.mainRepo = java.util.Objects.requireNonNull(mainRepo, "mainRepo");
        this.draftsRepo = java.util.Objects.requireNonNull(draftsRepo, "draftsRepo");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.usageStore = usageStore;
        this.auditLog = auditLog;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Author, edit, patch, and archive procedural skills in the agent's workspace. "
                + "Use this when you discover a reusable approach to a task type that future "
                + "calls should be able to apply directly without rediscovery.\n\n"
                + "Six actions (pass via 'action' parameter):\n"
                + "  - create      : write a new SKILL.md (frontmatter must include name + "
                + "description)\n"
                + "  - edit        : full-rewrite the SKILL.md of an existing skill\n"
                + "  - patch       : exact-string find-and-replace in SKILL.md or a support file\n"
                + "  - write_file  : add/overwrite references/, templates/, scripts/, or "
                + "assets/ file\n"
                + "  - remove_file : delete a single support file\n"
                + "  - delete      : archive the entire skill directory (non-destructive, "
                + "moves to .archive/)\n\n"
                + (config.autoPromote()
                        ? "New skills become immediately visible on the next reasoning turn."
                        : "New skills land in skills/_drafts/ and are NOT visible until "
                                + "explicitly promoted. Use this freely — drafts cost nothing.");
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "action",
                Map.of(
                        "type", "string",
                        "enum",
                                List.of(
                                        "create",
                                        "edit",
                                        "patch",
                                        "write_file",
                                        "remove_file",
                                        "delete"),
                        "description", "Which sub-operation to perform."));
        properties.put(
                "name",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Skill name (lowercase letters/digits/dots/underscores/hyphens, "
                                + "max "
                                + MAX_NAME_LENGTH
                                + " chars)."));
        properties.put(
                "content",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Full SKILL.md content (frontmatter + body). Required for "
                                + "create/edit."));
        properties.put(
                "old_string",
                Map.of(
                        "type", "string",
                        "description", "Required for patch: the exact text to replace."));
        properties.put(
                "new_string",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Required for patch: the replacement text (empty string" + " deletes)."));
        properties.put(
                "replace_all",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "If true, patch every occurrence; default false (must be" + " unique)."));
        properties.put(
                "file_path",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "For write_file/remove_file: path relative to skill root, must "
                                + "start with references/, templates/, scripts/, or "
                                + "assets/. For patch: optional target file (defaults "
                                + "to SKILL.md)."));
        properties.put(
                "file_content",
                Map.of(
                        "type", "string",
                        "description", "Required for write_file: the file body."));
        properties.put(
                "absorbed_into",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "For delete: name of the umbrella skill that absorbed this one, "
                                + "or empty string for pure pruning. Optional."));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action", "name"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext ctx = param.getRuntimeContext();
        return Mono.fromCallable(() -> dispatchSync(param.getInput(), ctx))
                .onErrorResume(
                        e -> {
                            log.warn("skill_manage failed: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "skill_manage failed: " + e.getMessage()));
                        });
    }

    private ToolResultBlock dispatchSync(Map<String, Object> input, RuntimeContext ctx) {
        if (input == null) {
            return ToolResultBlock.error("Missing input parameters.");
        }
        String action = stringOf(input, "action");
        String name = stringOf(input, "name");
        if (action == null || action.isBlank()) {
            return ToolResultBlock.error("Missing required parameter: action");
        }
        if (name == null || name.isBlank()) {
            return ToolResultBlock.error("Missing required parameter: name");
        }
        String nameErr = validateName(name);
        if (nameErr != null) {
            return ToolResultBlock.error(nameErr);
        }
        switch (action) {
            case "create":
                return doCreate(name, stringOf(input, "content"), sessionIdOf(ctx));
            case "edit":
                return doEdit(name, stringOf(input, "content"));
            case "patch":
                return doPatch(
                        name,
                        stringOf(input, "old_string"),
                        stringOf(input, "new_string"),
                        stringOf(input, "file_path"),
                        boolOf(input, "replace_all"));
            case "write_file":
                return doWriteFile(
                        name, stringOf(input, "file_path"), stringOf(input, "file_content"));
            case "remove_file":
                return doRemoveFile(name, stringOf(input, "file_path"));
            case "delete":
                return doDelete(name, stringOf(input, "absorbed_into"));
            default:
                return ToolResultBlock.error("Unknown action: " + action);
        }
    }

    private static String sessionIdOf(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getSessionId();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    //  Actions
    // ---------------------------------------------------------------------

    private ToolResultBlock doCreate(String name, String content, String sessionId) {
        if (content == null || content.isBlank()) {
            return ToolResultBlock.error(
                    "Missing 'content' parameter (full SKILL.md including frontmatter).");
        }
        String contentErr = validateContent(content);
        if (contentErr != null) {
            return ToolResultBlock.error(contentErr);
        }
        // Reject if a skill with this name already exists in either repo.
        if (mainRepo.skillExists(name) || draftsRepo.skillExists(name)) {
            return ToolResultBlock.error(
                    "A skill named '" + name + "' already exists. Use action=edit to update it.");
        }
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(content, null, "agent");
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to parse frontmatter: " + e.getMessage());
        }
        if (skill == null || skill.getName() == null || !name.equals(skill.getName())) {
            return ToolResultBlock.error(
                    "The 'name' parameter must match the SKILL.md frontmatter 'name' field "
                            + "(got param='"
                            + name
                            + "', frontmatter='"
                            + (skill == null ? null : skill.getName())
                            + "').");
        }
        if (skill.getDescription() == null || skill.getDescription().isBlank()) {
            return ToolResultBlock.error(
                    "SKILL.md frontmatter must include a 'description' field.");
        }
        if (skill.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            return ToolResultBlock.error(
                    "Description exceeds " + MAX_DESCRIPTION_LENGTH + " chars.");
        }

        WritableFilesystemSkillRepository target = config.autoPromote() ? mainRepo : draftsRepo;
        boolean ok = target.save(List.of(skill), false);
        if (!ok) {
            return ToolResultBlock.error(
                    "Failed to write skill '" + name + "'. Check logs for details.");
        }
        // Static security scan post-write; roll back on DANGEROUS so the agent can revise.
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scan(name, content, skill.getResources());
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                target.delete(name); // best-effort rollback (archives the bad draft)
                return ToolResultBlock.error(
                        "Security scan blocked this skill (" + scan.verdict() + "):\n"
                                + scan.reportText());
            }
        }
        // Telemetry: record provenance so visibility filters / curator can see this skill.
        if (usageStore != null) {
            try {
                if (config.autoPromote()) {
                    usageStore.markAgentCreated(name, "auto", List.of("prod"));
                } else {
                    usageStore.markAgentDraft(name, sessionId);
                }
            } catch (Exception e) {
                log.debug("usageStore mark on create({}) failed: {}", name, e.getMessage());
            }
        }
        String where = config.autoPromote() ? config.mainDir() : config.draftsDir();
        if (auditLog != null) {
            auditLog.append(
                    SkillAuditLog.manageEntry(
                            "agent", name, "create", config.autoPromote() ? "main" : "draft",
                            "SAFE"));
        }
        return ToolResultBlock.text(
                "Skill '"
                        + name
                        + "' created at "
                        + where
                        + "/"
                        + name
                        + "/SKILL.md."
                        + (config.autoPromote()
                                ? " It will become visible on the next reasoning turn."
                                : " It is a draft and will NOT be auto-loaded until promoted."));
    }

    private ToolResultBlock doEdit(String name, String content) {
        if (content == null || content.isBlank()) {
            return ToolResultBlock.error("Missing 'content' parameter (full SKILL.md).");
        }
        String contentErr = validateContent(content);
        if (contentErr != null) {
            return ToolResultBlock.error(contentErr);
        }
        WritableFilesystemSkillRepository target = locate(name);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(content, null, "agent");
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to parse frontmatter: " + e.getMessage());
        }
        if (skill == null || !name.equals(skill.getName())) {
            return ToolResultBlock.error(
                    "The 'name' parameter must match the SKILL.md frontmatter 'name' field.");
        }
        // Stash the previous SKILL.md so we can roll back on a DANGEROUS scan verdict.
        String previous = target.readSkillFile(name, "SKILL.md");
        boolean ok = target.save(List.of(skill), true /* force = overwrite */);
        if (!ok) {
            return ToolResultBlock.error("Failed to edit skill '" + name + "'.");
        }
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scan(name, content, skill.getResources());
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                if (previous != null) {
                    target.writeSkillFile(name, "SKILL.md", previous);
                }
                return ToolResultBlock.error(
                        "Security scan blocked this edit (" + scan.verdict() + "):\n"
                                + scan.reportText());
            }
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text("Skill '" + name + "' SKILL.md replaced.");
    }

    private ToolResultBlock doPatch(
            String name, String oldString, String newString, String filePath, boolean replaceAll) {
        if (oldString == null) {
            return ToolResultBlock.error("Missing 'old_string' for patch.");
        }
        if (newString == null) {
            return ToolResultBlock.error(
                    "Missing 'new_string' for patch (use empty string to delete matched text).");
        }
        WritableFilesystemSkillRepository target = locate(name);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        String relPath;
        if (filePath == null || filePath.isBlank()) {
            relPath = "SKILL.md";
        } else {
            String filePathErr = validateSubFilePath(filePath);
            if (filePathErr != null) {
                return ToolResultBlock.error(filePathErr);
            }
            relPath = filePath;
        }
        String existing = target.readSkillFile(name, relPath);
        if (existing == null) {
            return ToolResultBlock.error(
                    "File not found: " + relPath + " (in skill '" + name + "')");
        }

        // TODO(fuzzy-match): port hermes fuzzy_match. M1 only does exact-match + uniqueness.
        int firstIdx = existing.indexOf(oldString);
        if (firstIdx < 0) {
            return ToolResultBlock.error(
                    "old_string not found in "
                            + relPath
                            + ". "
                            + "M1 patch requires an exact match; "
                            + "consider using action=edit to fully rewrite SKILL.md.");
        }
        String updated;
        int replacements;
        if (replaceAll) {
            updated = existing.replace(oldString, newString);
            replacements = countOccurrences(existing, oldString);
        } else {
            int secondIdx = existing.indexOf(oldString, firstIdx + 1);
            if (secondIdx >= 0) {
                return ToolResultBlock.error(
                        "old_string is not unique in "
                                + relPath
                                + " (found at least 2 occurrences). "
                                + "Add more surrounding context to make it unique, or set "
                                + "replace_all=true.");
            }
            updated =
                    existing.substring(0, firstIdx)
                            + newString
                            + existing.substring(firstIdx + oldString.length());
            replacements = 1;
        }

        if (updated.length() > MAX_SKILL_CONTENT_CHARS) {
            return ToolResultBlock.error(
                    "Patched content exceeds " + MAX_SKILL_CONTENT_CHARS + " chars.");
        }
        boolean ok = target.writeSkillFile(name, relPath, updated);
        if (!ok) {
            return ToolResultBlock.error("Failed to write patched file.");
        }
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scanSingleFile(relPath, updated);
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                target.writeSkillFile(name, relPath, existing); // rollback
                return ToolResultBlock.error(
                        "Security scan blocked this patch (" + scan.verdict() + "):\n"
                                + scan.reportText());
            }
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text(
                "Patched "
                        + relPath
                        + " in '"
                        + name
                        + "' ("
                        + replacements
                        + " replacement"
                        + (replacements == 1 ? "" : "s")
                        + ").");
    }

    private ToolResultBlock doWriteFile(String name, String filePath, String fileContent) {
        if (filePath == null || filePath.isBlank()) {
            return ToolResultBlock.error("Missing 'file_path' for write_file.");
        }
        String filePathErr = validateSubFilePath(filePath);
        if (filePathErr != null) {
            return ToolResultBlock.error(filePathErr);
        }
        if (fileContent == null) {
            return ToolResultBlock.error("Missing 'file_content' for write_file.");
        }
        if (fileContent.length() > MAX_SKILL_FILE_BYTES) {
            return ToolResultBlock.error(
                    "file_content exceeds " + MAX_SKILL_FILE_BYTES + " bytes.");
        }
        WritableFilesystemSkillRepository target = locate(name);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        String previous = target.readSkillFile(name, filePath);
        boolean ok = target.writeSkillFile(name, filePath, fileContent);
        if (!ok) {
            return ToolResultBlock.error("Failed to write " + filePath + ".");
        }
        if (config.securityScan()) {
            SkillSecurityScanner.ScanResult scan =
                    SkillSecurityScanner.scanSingleFile(filePath, fileContent);
            if (!SkillSecurityScanner.shouldAllow(
                    SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
                if (previous != null) {
                    target.writeSkillFile(name, filePath, previous);
                } else {
                    target.deleteSkillFile(name, filePath);
                }
                return ToolResultBlock.error(
                        "Security scan blocked write_file (" + scan.verdict() + "):\n"
                                + scan.reportText());
            }
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text("Wrote " + filePath + " in skill '" + name + "'.");
    }

    private ToolResultBlock doRemoveFile(String name, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return ToolResultBlock.error("Missing 'file_path' for remove_file.");
        }
        String filePathErr = validateSubFilePath(filePath);
        if (filePathErr != null) {
            return ToolResultBlock.error(filePathErr);
        }
        WritableFilesystemSkillRepository target = locate(name);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        boolean ok = target.deleteSkillFile(name, filePath);
        if (!ok) {
            return ToolResultBlock.error("Failed to remove " + filePath + ".");
        }
        bumpPatchSilent(name);
        return ToolResultBlock.text("Removed " + filePath + " from skill '" + name + "'.");
    }

    private ToolResultBlock doDelete(String name, String absorbedInto) {
        WritableFilesystemSkillRepository target = locate(name);
        if (target == null) {
            return ToolResultBlock.error("Skill '" + name + "' not found.");
        }
        // absorbedInto validation: if non-empty, the target must exist (matches hermes intent so
        // the Curator can later classify consolidation vs pruning).
        if (absorbedInto != null && !absorbedInto.isBlank()) {
            String trimmed = absorbedInto.trim();
            if (trimmed.equals(name)) {
                return ToolResultBlock.error("absorbed_into cannot equal the skill being deleted.");
            }
            if (!mainRepo.skillExists(trimmed) && !draftsRepo.skillExists(trimmed)) {
                return ToolResultBlock.error(
                        "absorbed_into='"
                                + trimmed
                                + "' does not exist. Create or edit the umbrella skill first.");
            }
        }
        boolean ok = target.delete(name);
        if (!ok) {
            return ToolResultBlock.error(
                    "Failed to archive skill '" + name + "'. Check logs for details.");
        }
        if (usageStore != null) {
            try {
                usageStore.setState(name, SkillUsageRecord.State.ARCHIVED);
            } catch (Exception e) {
                log.debug("usageStore setState ARCHIVED({}) failed: {}", name, e.getMessage());
            }
        }
        String msg = "Skill '" + name + "' archived (moved under .archive/).";
        if (absorbedInto != null) {
            msg =
                    msg
                            + (absorbedInto.isBlank()
                                    ? " Declared as pure pruning (no merge target)."
                                    : " Declared as absorbed into '" + absorbedInto.trim() + "'.");
        }
        return ToolResultBlock.text(msg);
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    /** Best-effort {@code bumpPatch}; never throws into the caller. */
    private void bumpPatchSilent(String name) {
        if (usageStore == null) {
            return;
        }
        try {
            usageStore.bumpPatch(name);
        } catch (Exception e) {
            log.debug("usageStore.bumpPatch({}) failed: {}", name, e.getMessage());
        }
    }

    /** Locate the repository that owns a skill by name; drafts win on collision. */
    private WritableFilesystemSkillRepository locate(String name) {
        if (draftsRepo.skillExists(name)) {
            return draftsRepo;
        }
        if (mainRepo.skillExists(name)) {
            return mainRepo;
        }
        return null;
    }

    static String validateName(String name) {
        if (name == null || name.isEmpty()) {
            return "Skill name is required.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Skill name exceeds " + MAX_NAME_LENGTH + " characters.";
        }
        if (!VALID_NAME_RE.matcher(name).matches()) {
            return "Invalid skill name '"
                    + name
                    + "'. Use lowercase letters, digits, hyphens, dots, and underscores. "
                    + "Must start with a letter or digit.";
        }
        return null;
    }

    static String validateContent(String content) {
        if (content.length() > MAX_SKILL_CONTENT_CHARS) {
            return "SKILL.md content exceeds " + MAX_SKILL_CONTENT_CHARS + " chars.";
        }
        // Must look like frontmatter: starts with "---" on first line and contains closing "---".
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) {
            return "SKILL.md must start with '---' (YAML frontmatter).";
        }
        int second = trimmed.indexOf("\n---", 3);
        if (second < 0) {
            return "SKILL.md must include a closing '---' line after the frontmatter.";
        }
        return null;
    }

    static String validateSubFilePath(String filePath) {
        if (filePath.contains("..") || filePath.startsWith("/")) {
            return "file_path must be relative and may not contain '..' segments.";
        }
        int slash = filePath.indexOf('/');
        if (slash <= 0 || slash == filePath.length() - 1) {
            return "file_path must include a subdirectory and filename, e.g. 'scripts/probe.sh'.";
        }
        String head = filePath.substring(0, slash);
        if (!ALLOWED_SUBDIRS.contains(head)) {
            return "file_path must start with one of " + ALLOWED_SUBDIRS + " (got '" + head + "').";
        }
        return null;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String stringOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean boolOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }
}
