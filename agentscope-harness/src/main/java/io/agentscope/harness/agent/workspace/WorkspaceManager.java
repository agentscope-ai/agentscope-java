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
package io.agentscope.harness.agent.workspace;

import static io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.KNOWLEDGE_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.KNOWLEDGE_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.MEMORY_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.MEMORY_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSIONS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSIONS_STORE;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SKILLS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SUBAGENT_YML;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless accessor for workspace content using a two-layer read architecture.
 *
 * <p><strong>Read path:</strong> For every read (AGENTS.md, MEMORY.md, knowledge, subagent.yml,
 * etc.), the {@link AbstractFilesystem} is queried first. If it returns non-empty content, that
 * content is used (filesystem overrides). Otherwise, the local workspace disk is read as a
 * fallback. The filesystem layer applies user/session scoping transparently via
 * {@link NamespaceFactory}.
 *
 * <p><strong>Write path:</strong> All writes (memory, sessions, etc.) go through the
 * {@link AbstractFilesystem}.
 *
 * <p><strong>Listing:</strong> File listings (memory files, knowledge files, session logs) union
 * results from both the filesystem layer and local disk, deduplicating by relative path.
 *
 * <p>Expected layout:
 *
 * <pre>
 * workspace/
 * ├── AGENTS.md
 * ├── MEMORY.md
 * ├── memory/YYYY-MM-DD.md
 * ├── skills/&lt;skill-name&gt;/SKILL.md
 * ├── knowledge/KNOWLEDGE.md
 * ├── knowledge/*
 * ├── agents/&lt;agentId&gt;/sessions/sessions.json
 * ├── agents/&lt;agentId&gt;/sessions/&lt;sessionId&gt;.log.jsonl
 * └── subagent.yml
 * </pre>
 */
public class WorkspaceManager {

    private static final RuntimeContext DEFAULT_FS_RUNTIME = RuntimeContext.empty();

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final ObjectMapper SESSION_STORE_JSON = new ObjectMapper();

    private final Path workspace;
    private final AbstractFilesystem filesystem;

    public WorkspaceManager(Path workspace) {
        this(workspace, null);
    }

    public WorkspaceManager(Path workspace, AbstractFilesystem filesystem) {
        this.workspace = workspace;
        this.filesystem = filesystem;
    }

    public AbstractFilesystem getFilesystem() {
        return filesystem;
    }

    /**
     * Validates the workspace exists and key files are present. Logs warnings for anything
     * missing. Called once at HarnessAgent build time.
     */
    public void validate() {
        if (!Files.isDirectory(workspace)) {
            log.warn(
                    "Workspace directory does not exist: {}. "
                            + "Please create it and add AGENTS.md.",
                    workspace.toAbsolutePath());
            return;
        }
        if (!Files.isRegularFile(workspace.resolve(AGENTS_MD))) {
            log.warn(
                    "AGENTS.md not found in workspace: {}. "
                            + "AGENTS.md defines persona and local conventions for the agent.",
                    workspace.toAbsolutePath());
        }
    }

    public Path getWorkspace() {
        return workspace;
    }

    /** Reads AGENTS.md content, returns empty string if not found. */
    public String readAgentsMd() {
        return readWithOverride(AGENTS_MD);
    }

    /** Reads KNOWLEDGE.md content from the knowledge directory. */
    public String readKnowledgeMd() {
        return readWithOverride(KNOWLEDGE_DIR + "/" + KNOWLEDGE_MD);
    }

    /** Reads MEMORY.md content (two-layer: filesystem override, local fallback). */
    public String readMemoryMd() {
        return readWithOverride(MEMORY_MD);
    }

    /**
     * Reads a UTF-8 file under the workspace, using the two-layer pattern:
     * filesystem first, then local disk fallback.
     */
    public String readManagedWorkspaceFileUtf8(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return "";
        }
        Path resolved = workspace.resolve(normalized).normalize();
        if (!resolved.startsWith(workspace)) {
            return "";
        }
        return readWithOverride(normalized);
    }

    /** Reads subagent.yml content (two-layer: filesystem override, local fallback). */
    public String readSubagentYml() {
        return readWithOverride(SUBAGENT_YML);
    }

    public Path getMemoryDir() {
        return workspace.resolve(MEMORY_DIR);
    }

    public Path getSkillsDir() {
        return workspace.resolve(SKILLS_DIR);
    }

    public Path getKnowledgeDir() {
        return workspace.resolve(KNOWLEDGE_DIR);
    }

    /** Lists all files under the knowledge directory tree (union of filesystem + local disk). */
    public List<Path> listKnowledgeFiles() {
        Set<String> relativePaths = new LinkedHashSet<>();

        if (filesystem != null) {
            GlobResult glob = filesystem.glob(DEFAULT_FS_RUNTIME, "*", KNOWLEDGE_DIR);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() != null && !fi.path().isBlank()) {
                        relativePaths.add(normalizeRelativePath(fi.path().trim()));
                    }
                }
            }
        }

        Path dir = getKnowledgeDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .forEach(
                                p -> {
                                    String rel =
                                            workspace
                                                    .relativize(p.normalize())
                                                    .toString()
                                                    .replace('\\', '/');
                                    relativePaths.add(rel);
                                });
            } catch (IOException e) {
                log.warn("Failed to list knowledge files: {}", e.getMessage());
            }
        }

        List<Path> result = new ArrayList<>();
        for (String rel : relativePaths) {
            result.add(workspace.resolve(rel));
        }
        return result;
    }

    public Path getSessionDir(String agentId) {
        return workspace.resolve(AGENTS_DIR).resolve(agentId).resolve(SESSIONS_DIR);
    }

    /**
     * Returns the legacy session file path (.json) without creating directories.
     *
     * @deprecated Use {@link #resolveSessionContextFile(String, String)} for the JSONL format.
     */
    @Deprecated
    public Path resolveSessionFile(String agentId, String sessionId) {
        return getSessionDir(agentId).resolve(sessionId + ".json");
    }

    /** Returns the JSONL session context file path (LLM-facing, compacted). */
    public Path resolveSessionContextFile(String agentId, String sessionId) {
        return getSessionDir(agentId).resolve(sessionId + WorkspaceConstants.SESSION_CONTEXT_EXT);
    }

    /** Returns the JSONL session log file path (full history, append-only). */
    public Path resolveSessionLogFile(String agentId, String sessionId) {
        return getSessionDir(agentId).resolve(sessionId + WorkspaceConstants.SESSION_LOG_EXT);
    }

    /**
     * Appends UTF-8 text to a workspace-relative file, creating parent directories when needed.
     * All writes go through the {@link AbstractFilesystem}.
     */
    public void appendUtf8WorkspaceRelative(String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        if (filesystem == null) {
            appendLocalFile(normalized, content);
            return;
        }
        ReadResult rr = filesystem.read(DEFAULT_FS_RUNTIME, normalized, 0, 0);
        String existing = "";
        if (rr.isSuccess() && rr.fileData() != null && rr.fileData().content() != null) {
            existing = rr.fileData().content();
        }
        String merged = existing + content;
        filesystem.uploadFiles(
                DEFAULT_FS_RUNTIME,
                List.of(Map.entry(normalized, merged.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Upserts metadata for a session in {@code agents/&lt;agentId&gt;/sessions/sessions.json}
     * (small mutable JSON, keyed by {@code sessionId}).
     */
    public void updateSessionIndex(String agentId, String sessionId, String summary) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String rel = AGENTS_DIR + "/" + agentId + "/" + SESSIONS_DIR + "/" + SESSIONS_STORE;
        String existing = readWritableWorkspaceRelativeUtf8(rel);
        ObjectNode root = parseSessionStoreOrEmpty(existing);
        ObjectNode sessions = ensureSessionsObject(root);
        ObjectNode entry = SESSION_STORE_JSON.createObjectNode();
        entry.put("summary", summary != null ? summary : "");
        entry.put("updatedAt", java.time.Instant.now().toString());
        sessions.set(sessionId, entry);
        if (!root.has("version")) {
            root.put("version", 1);
        }
        try {
            String serialized =
                    SESSION_STORE_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            writeUtf8WorkspaceRelative(rel, serialized);
        } catch (IOException e) {
            log.warn("Failed to write session store {}: {}", rel, e.getMessage());
        }
    }

    private ObjectNode parseSessionStoreOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return SESSION_STORE_JSON.createObjectNode();
        }
        try {
            var node = SESSION_STORE_JSON.readTree(json);
            if (node instanceof ObjectNode on) {
                return on;
            }
        } catch (IOException e) {
            log.warn("Corrupt or unreadable session store, reinitializing: {}", e.getMessage());
        }
        return SESSION_STORE_JSON.createObjectNode();
    }

    private ObjectNode ensureSessionsObject(ObjectNode root) {
        var n = root.get("sessions");
        if (n instanceof ObjectNode on) {
            return on;
        }
        ObjectNode fresh = SESSION_STORE_JSON.createObjectNode();
        root.set("sessions", fresh);
        return fresh;
    }

    private String readWritableWorkspaceRelativeUtf8(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return "";
        }
        return readWithOverride(normalized);
    }

    /** Overwrites a workspace-relative UTF-8 file. All writes go through the filesystem. */
    public void writeUtf8WorkspaceRelative(String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        if (filesystem == null) {
            writeLocalFile(normalized, content);
            return;
        }
        filesystem.uploadFiles(
                DEFAULT_FS_RUNTIME,
                List.of(Map.entry(normalized, content.getBytes(StandardCharsets.UTF_8))));
    }

    // ==================== Two-layer read/write helpers ====================

    /**
     * Two-layer read: filesystem first (namespaced by {@link
     * NamespaceFactory}), local disk fallback.
     */
    private String readWithOverride(String relativePath) {
        String fsContent = readTextThroughFilesystem(relativePath);
        if (!fsContent.isEmpty()) {
            return fsContent;
        }
        return readFileQuietly(workspace.resolve(relativePath));
    }

    private String readFileQuietly(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
            return "";
        }
    }

    private String readTextThroughFilesystem(String filePath) {
        if (filesystem == null) {
            return "";
        }
        ReadResult r = filesystem.read(DEFAULT_FS_RUNTIME, filePath, 0, 0);
        if (!r.isSuccess() || r.fileData() == null) {
            return "";
        }
        String c = r.fileData().content();
        return c != null ? c : "";
    }

    private void appendLocalFile(String relativePath, String content) {
        Path local = workspace.resolve(relativePath).normalize();
        if (!local.startsWith(workspace)) {
            log.warn("Refusing to write outside workspace: {}", relativePath);
            return;
        }
        try {
            if (local.getParent() != null) {
                Files.createDirectories(local.getParent());
            }
            Files.writeString(
                    local,
                    content,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append {}: {}", local, e.getMessage());
        }
    }

    private void writeLocalFile(String relativePath, String content) {
        Path local = workspace.resolve(relativePath).normalize();
        if (!local.startsWith(workspace)) {
            log.warn("Refusing to write outside workspace: {}", relativePath);
            return;
        }
        try {
            if (local.getParent() != null) {
                Files.createDirectories(local.getParent());
            }
            Files.writeString(
                    local,
                    content,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", local, e.getMessage());
        }
    }

    static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String s = relativePath.replace('\\', '/').stripLeading();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * Returns workspace-relative paths of all memory files ({@code MEMORY.md} and {@code
     * memory/*.md}). Unions results from the {@link AbstractFilesystem} layer and the local disk,
     * deduplicating by relative path.
     */
    public List<String> listMemoryFilePaths() {
        Set<String> paths = new LinkedHashSet<>();

        if (filesystem != null) {
            ReadResult memMd = filesystem.read(DEFAULT_FS_RUNTIME, MEMORY_MD, 0, 1);
            if (memMd.isSuccess()) {
                paths.add(MEMORY_MD);
            }
            GlobResult glob = filesystem.glob(DEFAULT_FS_RUNTIME, "*.md", MEMORY_DIR);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() != null && !fi.path().isBlank()) {
                        String rel = normalizeRelativePath(fi.path().trim());
                        if (!rel.isEmpty()) {
                            paths.add(rel);
                        }
                    }
                }
            }
        }

        if (Files.isRegularFile(workspace.resolve(MEMORY_MD))) {
            paths.add(MEMORY_MD);
        }
        Path memDir = getMemoryDir();
        if (Files.isDirectory(memDir)) {
            try (Stream<Path> walk = Files.list(memDir)) {
                walk.filter(p -> p.toString().endsWith(".md"))
                        .filter(Files::isRegularFile)
                        .forEach(p -> paths.add(MEMORY_DIR + "/" + p.getFileName()));
            } catch (IOException e) {
                log.warn("Failed to list memory dir: {}", e.getMessage());
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * Lists workspace-relative paths of all session log files ({@code *.log.jsonl}).
     * Unions results from the {@link AbstractFilesystem} layer and the local disk.
     */
    public List<String> listSessionLogFiles() {
        Set<String> paths = new LinkedHashSet<>();

        if (filesystem != null) {
            GlobResult glob = filesystem.glob(DEFAULT_FS_RUNTIME, "*.log.jsonl", AGENTS_DIR);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() != null && !fi.path().isBlank()) {
                        String rel = normalizeRelativePath(fi.path().trim());
                        if (!rel.isEmpty()) {
                            paths.add(rel);
                        }
                    }
                }
            }
        }

        Path agentsDir = workspace.resolve(AGENTS_DIR);
        if (Files.isDirectory(agentsDir)) {
            try (Stream<Path> walk = Files.walk(agentsDir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(WorkspaceConstants.SESSION_LOG_EXT))
                        .forEach(
                                p -> {
                                    String rel =
                                            workspace
                                                    .relativize(p.normalize())
                                                    .toString()
                                                    .replace('\\', '/');
                                    paths.add(rel);
                                });
            } catch (IOException e) {
                log.warn("Failed to list session log files: {}", e.getMessage());
            }
        }
        return new ArrayList<>(paths);
    }

    /** Workspace-relative path for indexing. */
    public String toWorkspaceRelativeString(Path absoluteUnderWorkspace) {
        return workspace
                .relativize(absoluteUnderWorkspace.normalize())
                .toString()
                .replace('\\', '/');
    }
}
