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
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.TASKS_DIR;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless accessor for workspace content using a two-layer read architecture.
 *
 * <p><strong>Read path:</strong> For every read (AGENTS.md, MEMORY.md, knowledge, etc.),
 * the {@link AbstractFilesystem} is queried first. If it returns non-empty content, that
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
 * ├── subagents/&lt;id&gt;.md                     (subagent declarations)
 * ├── agents/&lt;agentId&gt;/workspace/           (isolated subagent runtime root, auto-created)
 * ├── agents/&lt;agentId&gt;/sessions/sessions.json
 * └── agents/&lt;agentId&gt;/sessions/&lt;sessionId&gt;.log.jsonl
 * </pre>
 */
public class WorkspaceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final ObjectMapper SESSION_STORE_JSON = new ObjectMapper();
    private static final ObjectMapper TASK_RECORD_JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, TaskRecord>> TASK_MAP_TYPE =
            new TypeReference<>() {};

    /**
     * Per-path locks for workspace-relative files to prevent concurrent read-modify-write races.
     * Keyed by workspace-relative path (e.g. {@code agents/X/tasks/Y.json},
     * {@code agents/X/sessions/sessions.json}, {@code memory/YYYY-MM-DD.md}).
     *
     * <p>This is an in-process lock only. For cross-process (multi-node) deployments the Remote
     * backend must additionally use server-side CAS / optimistic locking.
     */
    private final Map<String, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    private final Path workspace;
    private final AbstractFilesystem filesystem;

    /** Best-effort local file index; may be {@code null} if SQLite is unavailable. */
    private final WorkspaceIndex index;

    private final NamespaceFactory namespaceFactory;

    /**
     * {@code true} when this manager allocated its own {@link #index} (via the {@code (workspace,
     * filesystem)} constructor) and is therefore responsible for closing it. When the index is
     * supplied externally (e.g. by {@link io.agentscope.harness.agent.HarnessAgent}'s builder), the
     * external owner manages its lifecycle and {@link #close()} here is a no-op.
     */
    private final boolean ownsIndex;

    public WorkspaceManager(Path workspace) {
        this(workspace, null, null, null, false);
    }

    public WorkspaceManager(Path workspace, AbstractFilesystem filesystem) {
        this(workspace, filesystem, WorkspaceIndex.open(workspace), null, true);
    }

    public WorkspaceManager(Path workspace, AbstractFilesystem filesystem, WorkspaceIndex index) {
        this(workspace, filesystem, index, null, false);
    }

    public WorkspaceManager(
            Path workspace,
            AbstractFilesystem filesystem,
            WorkspaceIndex index,
            NamespaceFactory namespaceFactory) {
        this(workspace, filesystem, index, namespaceFactory, false);
    }

    private WorkspaceManager(
            Path workspace,
            AbstractFilesystem filesystem,
            WorkspaceIndex index,
            NamespaceFactory namespaceFactory,
            boolean ownsIndex) {
        this.workspace = workspace;
        this.filesystem = filesystem;
        this.index = index;
        this.namespaceFactory = namespaceFactory;
        this.ownsIndex = ownsIndex;
    }

    /**
     * Releases the SQLite-backed {@link WorkspaceIndex} when this manager owns it.
     *
     * <p>Required for tests that use {@code @TempDir} on Windows: the JDBC driver keeps a file
     * handle on {@code .index/workspace.db}, and Windows refuses to delete the temp directory
     * while the handle is open.
     */
    @Override
    public void close() {
        if (ownsIndex && index != null) {
            index.close();
        }
    }

    public NamespaceFactory getNamespaceFactory() {
        return namespaceFactory;
    }

    /** Returns the best-effort workspace index; may be {@code null} when unavailable. */
    public WorkspaceIndex getIndex() {
        return index;
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
        boolean agentsMdExists = Files.isRegularFile(workspace.resolve(AGENTS_MD));
        if (!agentsMdExists && filesystem != null) {
            try {
                agentsMdExists = filesystem.exists(RuntimeContext.empty(), AGENTS_MD);
            } catch (Exception e) {
                log.debug(
                        "Filesystem not available at build time, skipping exists check: {}",
                        e.getMessage());
            }
        }
        if (!agentsMdExists) {
            log.warn(
                    "AGENTS.md not found in workspace: {}. "
                            + "AGENTS.md defines persona and local conventions for the agent.",
                    workspace.toAbsolutePath());
        }
    }

    public Path getWorkspace() {
        return workspace;
    }

    /**
     * Resolves a workspace-relative path for runtime user data, applying namespace prefix.
     * Use for paths that contain per-user data (sessions, tasks, memory).
     *
     * <p>The runtime context is forwarded to the {@link NamespaceFactory} so per-call
     * identity (user/session) drives the namespace, not a shared mutable reference.
     */
    public Path resolveRuntimeDataPath(RuntimeContext rc, String relativePath) {
        if (namespaceFactory == null) {
            return workspace.resolve(relativePath);
        }
        List<String> ns = namespaceFactory.getNamespace(rc != null ? rc : RuntimeContext.empty());
        if (ns == null || ns.isEmpty()) {
            return workspace.resolve(relativePath);
        }
        return workspace.resolve(String.join("/", ns)).resolve(relativePath);
    }

    /** Reads AGENTS.md content, returns empty string if not found. */
    public String readAgentsMd(RuntimeContext rc) {
        return readWithOverride(rc, AGENTS_MD);
    }

    /** Reads KNOWLEDGE.md content from the knowledge directory. */
    public String readKnowledgeMd(RuntimeContext rc) {
        return readWithOverride(rc, KNOWLEDGE_DIR + "/" + KNOWLEDGE_MD);
    }

    /** Reads MEMORY.md content (two-layer: filesystem override, local fallback). */
    public String readMemoryMd(RuntimeContext rc) {
        return readWithOverride(rc, MEMORY_MD);
    }

    /**
     * Reads a UTF-8 file under the workspace, using the two-layer pattern:
     * filesystem first, then local disk fallback.
     */
    public String readManagedWorkspaceFileUtf8(RuntimeContext rc, String relativePath) {
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
        return readWithOverride(rc, normalized);
    }

    public Path getMemoryDir(RuntimeContext rc) {
        return resolveRuntimeDataPath(rc, MEMORY_DIR);
    }

    public Path getSkillsDir() {
        return workspace.resolve(SKILLS_DIR);
    }

    public Path getKnowledgeDir() {
        return workspace.resolve(KNOWLEDGE_DIR);
    }

    /** Lists all files under the knowledge directory tree (union of filesystem + local disk). */
    public List<Path> listKnowledgeFiles(RuntimeContext rc) {
        Set<String> relativePaths = new LinkedHashSet<>();

        if (filesystem != null) {
            GlobResult glob = filesystem.glob(rc, "*", KNOWLEDGE_DIR);
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

    public Path getSessionDir(RuntimeContext rc, String agentId) {
        return resolveRuntimeDataPath(rc, AGENTS_DIR + "/" + agentId + "/" + SESSIONS_DIR);
    }

    /**
     * Returns the legacy session file path (.json) without creating directories.
     *
     * @deprecated Use {@link #resolveSessionContextFile(RuntimeContext, String, String)} for the
     *     JSONL format.
     */
    @Deprecated
    public Path resolveSessionFile(RuntimeContext rc, String agentId, String sessionId) {
        return getSessionDir(rc, agentId).resolve(sessionId + ".json");
    }

    /** Returns the JSONL session context file path (LLM-facing, compacted). */
    public Path resolveSessionContextFile(RuntimeContext rc, String agentId, String sessionId) {
        return getSessionDir(rc, agentId)
                .resolve(sessionId + WorkspaceConstants.SESSION_CONTEXT_EXT);
    }

    /** Returns the JSONL session log file path (full history, append-only). */
    public Path resolveSessionLogFile(RuntimeContext rc, String agentId, String sessionId) {
        return getSessionDir(rc, agentId).resolve(sessionId + WorkspaceConstants.SESSION_LOG_EXT);
    }

    /**
     * Appends UTF-8 text to a workspace-relative file, creating parent directories when needed.
     * All writes go through the {@link AbstractFilesystem}.
     *
     * <p>A per-path {@link ReentrantLock} serialises concurrent callers so that the
     * read→merge→write cycle is atomic within this process. For cross-process / multi-node
     * deployments the {@link AbstractFilesystem} backend must additionally provide server-side
     * concurrency control.
     */
    public void appendUtf8WorkspaceRelative(
            RuntimeContext rc, String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        ReentrantLock lock = pathLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
        lock.lock();
        try {
            if (filesystem == null) {
                appendLocalFile(normalized, content);
                return;
            }
            ReadResult rr = filesystem.read(rc, normalized, 0, 0);
            String existing = "";
            if (rr.isSuccess() && rr.fileData() != null && rr.fileData().content() != null) {
                existing = rr.fileData().content();
            }
            String merged = existing + content;
            filesystem.uploadFiles(
                    rc, List.of(Map.entry(normalized, merged.getBytes(StandardCharsets.UTF_8))));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Upserts metadata for a session in {@code agents/&lt;agentId&gt;/sessions/sessions.json}
     * (small mutable JSON, keyed by {@code sessionId}).
     *
     * <p>A per-path {@link ReentrantLock} serialises concurrent callers so that the
     * read→merge→write cycle is atomic within this process.
     */
    public void updateSessionIndex(
            RuntimeContext rc, String agentId, String sessionId, String summary) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String rel = AGENTS_DIR + "/" + agentId + "/" + SESSIONS_DIR + "/" + SESSIONS_STORE;
        ReentrantLock lock = pathLocks.computeIfAbsent(rel, k -> new ReentrantLock());
        lock.lock();
        try {
            String existing = readWritableWorkspaceRelativeUtf8(rc, rel);
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
                        SESSION_STORE_JSON
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(root);
                writeUtf8WorkspaceRelative(rc, rel, serialized);
            } catch (IOException e) {
                log.warn("Failed to write session store {}: {}", rel, e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    // ==================== Task record methods ====================

    /**
     * Upserts a {@link TaskRecord} in {@code agents/<agentId>/tasks/<sessionId>.json}.
     *
     * <p>Reads the existing map, merges or inserts the record keyed by {@code taskId}, then
     * writes the updated map back. Acquires a per-file {@link ReentrantLock} to prevent
     * concurrent read-modify-write races when multiple tasks share the same session file.
     */
    public void writeTaskRecord(
            RuntimeContext rc, String agentId, String sessionId, TaskRecord record) {
        if (agentId == null
                || agentId.isBlank()
                || sessionId == null
                || sessionId.isBlank()
                || record == null
                || record.getTaskId() == null) {
            return;
        }
        String rel = taskRecordPath(agentId, sessionId);
        ReentrantLock lock = pathLocks.computeIfAbsent(rel, k -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, TaskRecord> map;
            try {
                map = readTaskMap(rc, rel); // already holding lock
            } catch (IOException e) {
                // Never overwrite a malformed store with partial data from a failed parse.
                log.error(
                        "Failed to parse task record store {}, aborting write to avoid data loss.",
                        rel,
                        e);
                return;
            }
            record.touch();
            map.put(record.getTaskId(), record);
            persistTaskMap(rc, rel, map);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads a single {@link TaskRecord} by task ID, or {@link Optional#empty()} if not found.
     */
    public Optional<TaskRecord> readTaskRecord(
            RuntimeContext rc, String agentId, String sessionId, String taskId) {
        if (agentId == null
                || agentId.isBlank()
                || sessionId == null
                || sessionId.isBlank()
                || taskId == null
                || taskId.isBlank()) {
            return Optional.empty();
        }
        String rel = taskRecordPath(agentId, sessionId);
        Map<String, TaskRecord> map = readTaskMapLocked(rc, rel);
        return Optional.ofNullable(map.get(taskId));
    }

    /**
     * Returns all {@link TaskRecord}s for the given agent and session, in insertion order.
     */
    public Collection<TaskRecord> listTaskRecords(
            RuntimeContext rc, String agentId, String sessionId) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        String rel = taskRecordPath(agentId, sessionId);
        return List.copyOf(readTaskMapLocked(rc, rel).values());
    }

    /**
     * Returns all {@link TaskRecord}s for the given agent across <em>all</em> sessions that have
     * been active within {@code recentWindow}, in no particular order.
     *
     * <p>Unions task JSON files from the local disk and the filesystem layer. Files whose last
     * modification time (from disk mtime or {@link FileInfo#modifiedAt()}) is known and older than
     * {@code recentWindow} are skipped: once all tasks in a session reach a terminal state the file
     * is never modified again, so stale files cannot contain orphaned tasks.
     *
     * <p>Used by the orphan-task sweeper in
     * {@link io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository} to bound the
     * number of files read per sweep cycle without missing any genuinely running tasks.
     *
     * @param agentId the parent agent identifier
     * @param recentWindow only consider files modified within this duration; files known to be
     *     older are assumed to contain only terminal tasks and are skipped
     */
    public Collection<TaskRecord> listAllTaskRecords(
            RuntimeContext rc, String agentId, Duration recentWindow) {
        if (agentId == null || agentId.isBlank()) {
            return Collections.emptyList();
        }
        Instant cutoff = Instant.now().minus(recentWindow);
        String tasksRelDir = AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR;

        // workspace-relative path → Optional<Instant> last-modified (empty = mtime unknown)
        Map<String, Optional<Instant>> relPaths = new LinkedHashMap<>();

        if (filesystem != null) {
            GlobResult glob = filesystem.glob(rc, "*.json", tasksRelDir);
            if (glob.isSuccess() && glob.matches() != null) {
                for (FileInfo fi : glob.matches()) {
                    if (fi.path() == null || fi.path().isBlank()) {
                        continue;
                    }
                    String rel = normalizeRelativePath(fi.path().trim());
                    Instant mtime = parseInstantQuiet(fi.modifiedAt());
                    relPaths.put(rel, Optional.ofNullable(mtime));
                }
            }
        }

        Path tasksDir = resolveRuntimeDataPath(rc, AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR);
        if (Files.isDirectory(tasksDir)) {
            try (Stream<Path> stream = Files.list(tasksDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(
                                p -> {
                                    String rel = tasksRelDir + "/" + p.getFileName();
                                    if (!relPaths.containsKey(rel)) {
                                        relPaths.put(rel, Optional.ofNullable(diskMtime(p)));
                                    }
                                });
            } catch (IOException e) {
                log.warn("Failed to list task files for agent {}: {}", agentId, e.getMessage());
            }
        }

        List<TaskRecord> all = new ArrayList<>();
        for (Map.Entry<String, Optional<Instant>> entry : relPaths.entrySet()) {
            Optional<Instant> mtime = entry.getValue();
            // Skip only when mtime is known and clearly before the cutoff
            if (mtime.isPresent() && mtime.get().isBefore(cutoff)) {
                continue;
            }
            all.addAll(readTaskMapLocked(rc, entry.getKey()).values());
        }
        return all;
    }

    private static Instant parseInstantQuiet(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant diskMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads the timestamp written by the most recent successful orphan-sweep for this agent, or
     * {@link Optional#empty()} if no sweep has been recorded yet.
     *
     * <p>Stored in {@code agents/<agentId>/tasks/_sweep.marker} as a plain ISO-8601 string. Any
     * node can write to this path, so it naturally propagates through the shared filesystem layer.
     */
    public Optional<Instant> readSweepMarker(RuntimeContext rc, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        String rel = sweepMarkerPath(agentId);
        String content = readWritableWorkspaceRelativeUtf8(rc, rel);
        return Optional.ofNullable(parseInstantQuiet(content == null ? null : content.strip()));
    }

    /**
     * Records the current timestamp as the completion time of the most recent orphan-sweep for
     * this agent. Subsequent nodes that read this marker within the sweep interval will skip their
     * own sweep, reducing redundant workspace I/O in multi-node deployments.
     */
    public void writeSweepMarker(RuntimeContext rc, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        String rel = sweepMarkerPath(agentId);
        try {
            writeUtf8WorkspaceRelative(rc, rel, Instant.now().toString());
        } catch (Exception e) {
            log.warn("Failed to write sweep marker for agent {}: {}", agentId, e.getMessage());
        }
    }

    private String sweepMarkerPath(String agentId) {
        return AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR + "/_sweep.marker";
    }

    private String taskRecordPath(String agentId, String sessionId) {
        return AGENTS_DIR + "/" + agentId + "/" + TASKS_DIR + "/" + sessionId + ".json";
    }

    /**
     * Acquires the per-file lock before delegating to {@link #readTaskMap(String)}, so that reads
     * are mutually exclusive with the read-modify-write cycle in {@link #writeTaskRecord}. This
     * prevents a concurrent writer's non-atomic file update (truncate → write) from being observed
     * as a partial JSON read.
     */
    private Map<String, TaskRecord> readTaskMapLocked(RuntimeContext rc, String rel) {
        ReentrantLock lock = pathLocks.computeIfAbsent(rel, k -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return readTaskMap(rc, rel);
            } catch (IOException e) {
                // Surface corruption loudly, but do not mutate or reinitialize the backing file.
                log.error(
                        "Failed to parse task record store {}, returning empty in-memory view.",
                        rel,
                        e);
                return Collections.emptyMap();
            }
        } finally {
            lock.unlock();
        }
    }

    private Map<String, TaskRecord> readTaskMap(RuntimeContext rc, String rel) throws IOException {
        String json = readWritableWorkspaceRelativeUtf8(rc, rel);
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, TaskRecord> map = TASK_RECORD_JSON.readValue(json, TASK_MAP_TYPE);
        return map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
    }

    private void persistTaskMap(RuntimeContext rc, String rel, Map<String, TaskRecord> map) {
        try {
            String serialized =
                    TASK_RECORD_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(map);
            writeUtf8WorkspaceRelative(rc, rel, serialized);
        } catch (IOException e) {
            log.warn("Failed to write task record store {}: {}", rel, e.getMessage());
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

    private String readWritableWorkspaceRelativeUtf8(RuntimeContext rc, String relativePath) {
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return "";
        }
        return readWithOverride(rc, normalized);
    }

    /** Overwrites a workspace-relative UTF-8 file. All writes go through the filesystem. */
    public void writeUtf8WorkspaceRelative(RuntimeContext rc, String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        if (filesystem == null) {
            writeLocalFile(normalized, content);
            return;
        }
        filesystem.uploadFiles(
                rc, List.of(Map.entry(normalized, content.getBytes(StandardCharsets.UTF_8))));
        // Best-effort: record upload size in index (no local file to stat from)
        if (index != null) {
            index.upsert(normalized, content.getBytes(StandardCharsets.UTF_8).length, null);
        }
    }

    // ==================== Skill self-learning helpers====================

    /**
     * Detection helper for the heuristic used by {@link #writeDraftSkillFile} and
     * {@link #moveSkill}: returns {@code true} when the lower layer of an overlay is a
     * non-local KV / remote backend (e.g. {@code RemoteFilesystem}). Drafts on such
     * stores should be stored on the lower layer so external approval systems can read
     * them across replicas.
     */
    private static boolean isRemoteLowerLayer(AbstractFilesystem fs) {
        if (!(fs instanceof OverlayFilesystem overlay)) {
            return false;
        }
        AbstractFilesystem lower = overlay.lower();
        if (lower == null) {
            return false;
        }
        String name = lower.getClass().getSimpleName();
        // Non-local KV / KV-overlay implementations expose their content cross-replica.
        return name.contains("Remote") || name.contains("Kv") || name.contains("KV");
    }

    /**
     * Write a single file under {@code skills/_drafts/} (or whatever drafts dir the caller
     * passes). When the underlying filesystem is an {@link OverlayFilesystem} backed by a
     * {@code RemoteFilesystem} lower layer, the draft is written directly to that lower layer
     * so it is visible to other replicas / external approval systems. Otherwise it goes
     * through the standard upper-layer write path.
     *
     * @param rc the runtime context (passed straight through to the filesystem)
     * @param relativePath workspace-relative path (must include the drafts prefix)
     * @param content UTF-8 content
     */
    public void writeDraftSkillFile(RuntimeContext rc, String relativePath, String content) {
        if (relativePath == null || content == null) {
            return;
        }
        String normalized = requireSafeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return;
        }
        if (filesystem instanceof OverlayFilesystem overlay && isRemoteLowerLayer(filesystem)) {
            // Cross-replica draft: target the shared (lower) backend directly.
            overlay.lower()
                    .uploadFiles(
                            rc,
                            List.of(
                                    Map.entry(
                                            normalized, content.getBytes(StandardCharsets.UTF_8))));
            return;
        }
        // Default path — same as writeUtf8WorkspaceRelative.
        writeUtf8WorkspaceRelative(rc, relativePath, content);
    }

    /**
     * Move a directory inside the workspace (used by promotion: {@code skills/_drafts/<x>}
     * → {@code skills/<x>}). Best-effort — falls back to the underlying filesystem's native
     * {@code move} when available; returns {@code true} on success.
     */
    public boolean moveSkill(RuntimeContext rc, String fromRelative, String toRelative) {
        if (fromRelative == null || toRelative == null || filesystem == null) {
            return false;
        }
        String src = requireSafeRelativePath(fromRelative);
        String dst = requireSafeRelativePath(toRelative);
        if (src.isEmpty() || dst.isEmpty()) {
            return false;
        }
        try {
            // For overlay+remote-lower setups: source might live on lower (drafts) and we
            // want destination to also be on lower so it stays cross-replica. The overlay
            // contract handles this transparently via its own move.
            WriteResult r = filesystem.move(rc, src, dst);
            return r.isSuccess();
        } catch (Exception e) {
            log.warn("moveSkill {} → {} failed: {}", src, dst, e.getMessage());
            return false;
        }
    }

    // ==================== Two-layer read/write helpers ====================

    /**
     * Two-layer read: filesystem first (namespaced by {@link
     * NamespaceFactory}), local disk fallback.
     */
    private String readWithOverride(RuntimeContext rc, String relativePath) {
        String fsContent = readTextThroughFilesystem(rc, relativePath);
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

    private String readTextThroughFilesystem(RuntimeContext rc, String filePath) {
        if (filesystem == null) {
            return "";
        }
        ReadResult r = filesystem.read(rc, filePath, 0, 0);
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
            if (index != null) {
                index.upsertFromLocalFile(relativePath, local);
            }
        } catch (IOException e) {
            log.warn("Failed to append {}: {}", local, e.getMessage());
        }
    }

    /**
     * Atomically overwrites a workspace-relative UTF-8 file on local disk.
     *
     * <p>The content is first written to a sibling temp file, then renamed over the target using
     * {@link StandardCopyOption#ATOMIC_MOVE} (best-effort; falls back to a plain move when the
     * underlying filesystem does not support atomic rename). This prevents concurrent readers from
     * observing a partially-written file.
     */
    private void writeLocalFile(String relativePath, String content) {
        Path local = workspace.resolve(relativePath).normalize();
        if (!local.startsWith(workspace)) {
            log.warn("Refusing to write outside workspace: {}", relativePath);
            return;
        }
        Path temp = local.resolveSibling(local.getFileName() + ".tmp." + UUID.randomUUID());
        try {
            if (local.getParent() != null) {
                Files.createDirectories(local.getParent());
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        local,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, local, StandardCopyOption.REPLACE_EXISTING);
            }
            if (index != null) {
                index.upsertFromLocalFile(relativePath, local);
            }
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", local, e.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
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

    private static String requireSafeRelativePath(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (!normalized.isEmpty()) {
            AbstractFilesystem.validatePath(normalized);
        }
        return normalized;
    }

    /**
     * Returns workspace-relative paths of all memory files ({@code MEMORY.md} and {@code
     * memory/*.md}). Unions results from the {@link AbstractFilesystem} layer and the local disk,
     * deduplicating by relative path.
     */
    public List<String> listMemoryFilePaths(RuntimeContext rc) {
        Set<String> paths = new LinkedHashSet<>();

        if (filesystem != null) {
            ReadResult memMd = filesystem.read(rc, MEMORY_MD, 0, 1);
            if (memMd.isSuccess()) {
                paths.add(MEMORY_MD);
            }
            GlobResult glob = filesystem.glob(rc, "*.md", MEMORY_DIR);
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

        if (Files.isRegularFile(resolveRuntimeDataPath(rc, MEMORY_MD))) {
            paths.add(MEMORY_MD);
        }
        Path memDir = getMemoryDir(rc);
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
    public List<String> listSessionLogFiles(RuntimeContext rc) {
        Set<String> paths = new LinkedHashSet<>();

        if (filesystem != null) {
            GlobResult glob = filesystem.glob(rc, "*.log.jsonl", AGENTS_DIR);
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

        Path agentsDir = resolveRuntimeDataPath(rc, AGENTS_DIR);
        if (Files.isDirectory(agentsDir)) {
            try (Stream<Path> walk = Files.walk(agentsDir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(WorkspaceConstants.SESSION_LOG_EXT))
                        .forEach(
                                p -> {
                                    String rel =
                                            agentsDir
                                                    .getParent()
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
