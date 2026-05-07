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
package io.agentscope.harness.agent.memory.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages an append-only JSONL session tree (pi-mono-inspired).
 *
 * <p>The session file is a JSONL file where each line is a JSON-serialized {@link SessionEntry}.
 * Entries form a tree via {@code id}/{@code parentId} links. A companion {@code .log.jsonl} file
 * stores the full history for grep-ability (dual-file pattern from pi-mono mom).
 *
 * <h2>File layout</h2>
 * <pre>
 *   agents/{agentId}/sessions/{sessionId}.jsonl      — LLM context (compacted)
 *   agents/{agentId}/sessions/{sessionId}.log.jsonl   — full history (append-only, never compacted)
 * </pre>
 *
 * <h2>Persistence model</h2>
 * The local file is the working copy; the remote {@link AbstractFilesystem} (when configured) is
 * the cross-replica mirror. On every {@link #load()}, remote content is fetched and union-merged
 * with the local file so that entries written on another machine are visible to the current one.
 * On every {@link #flush()}, pending entries are appended to the local files synchronously and
 * then mirrored to the remote filesystem asynchronously (fire-and-forget, best-effort).
 *
 * <h2>Deferred persistence</h2>
 * Entries are buffered in memory and only flushed to disk on the first call to {@link #flush()}
 * (typically after the first assistant message). This avoids partial session files from
 * failed/short interactions.
 */
public class SessionTree {

    private static final RuntimeContext DEFAULT_FS_RUNTIME = RuntimeContext.empty();

    private static final Logger log = LoggerFactory.getLogger(SessionTree.class);

    /**
     * Daemon executor used for fire-and-forget remote mirrors so that flush() never blocks callers
     * on remote I/O. A single thread is intentional: serialises uploads for the same session.
     */
    private static final ExecutorService MIRROR_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "session-tree-mirror");
                        t.setDaemon(true);
                        return t;
                    });

    private final Path contextFile;
    private final Path logFile;
    private final Path workspaceRoot;
    private final AbstractFilesystem filesystem;

    private final Map<String, SessionEntry> entriesById = new LinkedHashMap<>();
    private final List<SessionEntry> appendOrder = new ArrayList<>();
    private final List<SessionEntry> pendingWrites = new ArrayList<>();

    private String lastCompactionFirstKeptId;
    private String lastSummaryEntryId;
    private boolean loaded = false;
    private boolean flushed = false;

    /**
     * Creates a SessionTree backed by the given filesystem for remote mirroring.
     *
     * @param contextFile  path to the {@code .jsonl} context file (LLM-facing, compacted)
     * @param workspaceRoot root of the agent workspace; used to derive workspace-relative paths
     * @param filesystem   {@link AbstractFilesystem} used for remote read/write; may be
     *                     {@code null} to disable remote mirroring (local-only mode)
     */
    public SessionTree(Path contextFile, Path workspaceRoot, AbstractFilesystem filesystem) {
        this.contextFile = contextFile;
        String name = contextFile.getFileName().toString();
        String baseName = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        this.logFile = contextFile.resolveSibling(baseName + ".log.jsonl");
        this.workspaceRoot = workspaceRoot;
        this.filesystem = filesystem;
    }

    /**
     * Loads existing entries from the local context file into the in-memory tree.
     *
     * <p>This is a <b>local-only, zero-network</b> operation. If the local file is absent, the
     * tree starts empty. To additionally pull and union-merge entries from the remote filesystem
     * (e.g., before a write that may follow a cross-machine handoff), call
     * {@link #syncFromRemote()} after this method.
     *
     * <p>Safe to call multiple times; only loads once.
     */
    public void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        // Cold-start restore: if local file is absent but remote has a copy, pull it down once.
        if (!Files.isRegularFile(contextFile)) {
            restoreFromMirror(contextFile);
        }

        List<SessionEntry> localEntries = readLocalEntries(contextFile);
        for (SessionEntry entry : localEntries) {
            entriesById.put(entry.getId(), entry);
            appendOrder.add(entry);

            if (entry instanceof SessionEntry.CompactionEntry ce) {
                lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                lastSummaryEntryId = ce.getSummaryEntryId();
            }
        }
    }

    /**
     * Pulls the remote context file and union-merges any entries not yet present locally.
     *
     * <p>Remote is treated as the authoritative base: remote entries come first, followed by any
     * local-only entries (written but not yet mirrored). If the remote has entries the local file
     * does not, the local file is overwritten with the merged content and the new entries are
     * appended to the local log file.
     *
     * <p>This is a <b>network operation</b> — call it only when cross-machine consistency is
     * required (typically in write paths such as
     * {@link io.agentscope.harness.agent.memory.MemoryFlushManager}). Read-only tools should use
     * {@link #load()} alone to keep queries fast and local.
     *
     * <p>No-op if no filesystem is configured or the remote read fails (failures are logged as
     * warnings).
     *
     * <p>{@link #load()} must be called before this method.
     */
    public void syncFromRemote() {
        if (filesystem == null || workspaceRoot == null) {
            return;
        }

        List<SessionEntry> remoteEntries = pullRemoteEntries(contextFile);
        if (remoteEntries.isEmpty()) {
            return;
        }

        Set<String> localIds =
                appendOrder.stream().map(SessionEntry::getId).collect(Collectors.toSet());

        List<SessionEntry> remoteNewEntries =
                remoteEntries.stream().filter(re -> !localIds.contains(re.getId())).toList();
        if (remoteNewEntries.isEmpty()) {
            return;
        }

        // Rebuild merged list: remote base + local-only extras at the end.
        Set<String> remoteIds =
                remoteEntries.stream()
                        .map(SessionEntry::getId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<SessionEntry> merged = new ArrayList<>(remoteEntries);
        for (SessionEntry e : appendOrder) {
            if (!remoteIds.contains(e.getId())) {
                merged.add(e);
            }
        }

        overwriteFile(contextFile, merged);
        appendToFile(logFile, remoteNewEntries);

        // Update in-memory state with the newly discovered remote entries.
        for (SessionEntry entry : remoteNewEntries) {
            entriesById.put(entry.getId(), entry);
        }
        // Re-build appendOrder to match the merged order (remote base first).
        appendOrder.clear();
        appendOrder.addAll(merged);
        for (SessionEntry entry : remoteNewEntries) {
            if (entry instanceof SessionEntry.CompactionEntry ce) {
                lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                lastSummaryEntryId = ce.getSummaryEntryId();
            }
        }

        log.info(
                "syncFromRemote: merged {} new remote entries into local session file {}",
                remoteNewEntries.size(),
                contextFile.getFileName());
    }

    /**
     * Appends an entry to the in-memory tree. The entry will be written to disk
     * on the next {@link #flush()} call.
     *
     * @return the entry (for chaining)
     */
    public SessionEntry append(SessionEntry entry) {
        entriesById.put(entry.getId(), entry);
        appendOrder.add(entry);
        pendingWrites.add(entry);

        if (entry instanceof SessionEntry.CompactionEntry ce) {
            lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
            lastSummaryEntryId = ce.getSummaryEntryId();
        }

        return entry;
    }

    /**
     * Flushes all pending entries to both the local context file and the local log file
     * synchronously, then schedules an asynchronous best-effort mirror to the remote filesystem.
     *
     * <p>The remote mirror is fire-and-forget: failures are logged as warnings and do not affect
     * the return of this method. The local write is always the primary guarantee.
     */
    public void flush() {
        if (pendingWrites.isEmpty()) {
            return;
        }

        flushed = true;
        List<SessionEntry> toWrite = new ArrayList<>(pendingWrites);
        pendingWrites.clear();

        appendToFile(contextFile, toWrite);
        appendToFile(logFile, toWrite);
        scheduleMirror();
    }

    /**
     * Returns whether {@link #flush()} has been called at least once.
     */
    public boolean isFlushed() {
        return flushed;
    }

    /**
     * Builds the LLM-visible context from the session tree.
     *
     * <p>Returns entries that the LLM should see:
     * <ul>
     *   <li>If compaction has occurred, starts with the summary entry, then all entries
     *       from {@code firstKeptEntryId} onward</li>
     *   <li>If no compaction, returns all message entries in order</li>
     * </ul>
     */
    public List<SessionEntry> buildContext() {
        if (appendOrder.isEmpty()) {
            return Collections.emptyList();
        }

        if (lastCompactionFirstKeptId == null) {
            return new ArrayList<>(appendOrder);
        }

        List<SessionEntry> context = new ArrayList<>();

        if (lastSummaryEntryId != null) {
            SessionEntry summary = entriesById.get(lastSummaryEntryId);
            if (summary != null) {
                context.add(summary);
            }
        }

        boolean found = false;
        for (SessionEntry entry : appendOrder) {
            if (entry.getId().equals(lastCompactionFirstKeptId)) {
                found = true;
            }
            if (found && entry instanceof SessionEntry.MessageEntry) {
                context.add(entry);
            }
        }

        return context;
    }

    /**
     * Returns all entries in append order (full history).
     */
    public List<SessionEntry> getAllEntries() {
        return Collections.unmodifiableList(appendOrder);
    }

    /**
     * Returns only message entries in append order.
     */
    public List<SessionEntry.MessageEntry> getMessageEntries() {
        return appendOrder.stream()
                .filter(e -> e instanceof SessionEntry.MessageEntry)
                .map(e -> (SessionEntry.MessageEntry) e)
                .toList();
    }

    public int size() {
        return appendOrder.size();
    }

    public Path getContextFile() {
        return contextFile;
    }

    public Path getLogFile() {
        return logFile;
    }

    /**
     * Syncs entries from the log file that are not yet in the context file.
     * This handles offline messages that were appended to the log while the
     * agent was inactive.
     *
     * @return the number of new entries synced
     */
    public int syncFromLog() {
        restoreFromMirror(logFile);
        if (!Files.isRegularFile(logFile)) {
            return 0;
        }

        int syncCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    SessionEntry entry =
                            JsonUtils.getJsonCodec().fromJson(line, SessionEntry.class);
                    if (!entriesById.containsKey(entry.getId())) {
                        entriesById.put(entry.getId(), entry);
                        appendOrder.add(entry);
                        pendingWrites.add(entry);
                        syncCount++;

                        if (entry instanceof SessionEntry.CompactionEntry ce) {
                            lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                            lastSummaryEntryId = ce.getSummaryEntryId();
                        }
                    }
                } catch (Exception e) {
                    log.debug("Skipping malformed log entry during sync: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to sync from log file {}: {}", logFile, e.getMessage());
        }

        if (syncCount > 0) {
            log.info("Synced {} offline entries from log to context", syncCount);
        }
        return syncCount;
    }

    // -------------------------------------------------------------------------
    //  Private helpers
    // -------------------------------------------------------------------------

    /**
     * Schedules an asynchronous, best-effort mirror of both session files to the remote
     * filesystem. Uses a daemon single-thread executor to serialise uploads and avoid
     * blocking the caller on remote I/O.
     */
    private void scheduleMirror() {
        if (filesystem == null || workspaceRoot == null) {
            return;
        }
        MIRROR_EXECUTOR.execute(
                () -> {
                    mirrorToFilesystem(contextFile);
                    mirrorToFilesystem(logFile);
                });
    }

    /**
     * Fetches the remote copy of {@code file} and parses it as JSONL session entries.
     * Returns an empty list if no filesystem is configured or the remote read fails.
     */
    private List<SessionEntry> pullRemoteEntries(Path file) {
        if (filesystem == null || workspaceRoot == null) {
            return List.of();
        }
        String relativePath = toWorkspaceRelative(file);
        if (relativePath == null || relativePath.isBlank()) {
            return List.of();
        }
        ReadResult read = filesystem.read(DEFAULT_FS_RUNTIME, relativePath, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || read.fileData().content() == null) {
            return List.of();
        }
        return parseJsonlEntries(read.fileData().content());
    }

    /**
     * Reads and parses the local copy of {@code file} as JSONL session entries.
     * Returns an empty list if the file does not exist or cannot be read.
     */
    private List<SessionEntry> readLocalEntries(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parseJsonlEntries(sb.toString());
        } catch (IOException e) {
            log.warn("Failed to read local session file {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /** Parses a JSONL string into a list of {@link SessionEntry} objects, skipping bad lines. */
    private List<SessionEntry> parseJsonlEntries(String content) {
        List<SessionEntry> result = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                result.add(JsonUtils.getJsonCodec().fromJson(line, SessionEntry.class));
            } catch (Exception e) {
                log.warn("Skipping malformed session entry: {}", e.getMessage());
            }
        }
        return result;
    }

    /** Overwrites {@code file} with the serialised form of {@code entries} (TRUNCATE + WRITE). */
    private void overwriteFile(Path file, List<SessionEntry> entries) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (BufferedWriter writer =
                    Files.newBufferedWriter(
                            file,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                for (SessionEntry entry : entries) {
                    writer.write(JsonUtils.getJsonCodec().toJson(entry));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to overwrite session file {}: {}", file, e.getMessage());
        }
    }

    private void appendToFile(Path file, List<SessionEntry> entries) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (BufferedWriter writer =
                    Files.newBufferedWriter(
                            file,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)) {
                for (SessionEntry entry : entries) {
                    String json = JsonUtils.getJsonCodec().toJson(entry);
                    writer.write(json);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to append to session file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Uploads {@code file} to the remote filesystem (full-file upload). Only called from the
     * mirror executor thread; failures are logged as warnings.
     */
    private void mirrorToFilesystem(Path file) {
        if (filesystem == null || workspaceRoot == null || !Files.isRegularFile(file)) {
            return;
        }
        String relativePath = toWorkspaceRelative(file);
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            filesystem.uploadFiles(DEFAULT_FS_RUNTIME, List.of(Map.entry(relativePath, bytes)));
        } catch (IOException e) {
            log.warn("Failed to mirror session file {} to filesystem: {}", file, e.getMessage());
        }
    }

    /**
     * Restores {@code file} from the remote filesystem mirror when the local file is absent.
     * Used by {@link #syncFromLog()} to ensure the log file is available locally before reading.
     */
    private void restoreFromMirror(Path file) {
        if (filesystem == null || workspaceRoot == null || Files.isRegularFile(file)) {
            return;
        }
        String relativePath = toWorkspaceRelative(file);
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        ReadResult read = filesystem.read(DEFAULT_FS_RUNTIME, relativePath, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || read.fileData().content() == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(
                    file,
                    read.fileData().content(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn(
                    "Failed to restore session file {} from filesystem mirror: {}",
                    file,
                    e.getMessage());
        }
    }

    private String toWorkspaceRelative(Path file) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }
        return root.relativize(candidate).toString().replace('\\', '/');
    }
}
