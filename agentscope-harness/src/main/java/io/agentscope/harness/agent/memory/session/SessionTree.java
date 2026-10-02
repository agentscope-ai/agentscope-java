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
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * The local file is the working copy. Cross-replica durability uses one of:
 * <ul>
 *   <li>{@link TranscriptStore} (preferred) — each flush writes an <em>immutable segment</em>;
 *       concurrent writers never overwrite each other.</li>
 *   <li>{@link AbstractFilesystem} full-file mirror (legacy) — O(N²) uploads and last-writer-wins
 *       across replicas; kept for backward compatibility when no {@link TranscriptStore} is set.</li>
 * </ul>
 *
 * <h2>Deferred persistence</h2>
 * Entries are buffered in memory and only flushed to disk on the first call to {@link #flush()}
 * (typically after the first assistant message). This avoids partial session files from
 * failed/short interactions.
 */
public class SessionTree {

    /**
     * Captured at construction time (or via {@link #setRuntimeContext}); used as the
     * {@code RuntimeContext} for all remote filesystem operations so that namespace-aware stores
     * resolve to the writer's namespace even when invoked from the async mirror thread.
     */
    private volatile RuntimeContext fsRc = RuntimeContext.empty();

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

    /**
     * Blocks until all remote-mirror tasks submitted before this call have finished.
     *
     * <p>The mirror executor is single-threaded and serial, so waiting on a sentinel task
     * guarantees that every previously scheduled mirror upload has completed. Intended for
     * graceful shutdown ({@code HarnessAgent.close()}) so asynchronous transcript/session
     * mirrors do not race with resource cleanup (e.g., temp workspace deletion).
     *
     * @param timeout maximum time to wait
     * @param unit time unit of {@code timeout}
     * @return {@code true} if the mirrors quiesced within the timeout
     */
    public static boolean awaitMirrorQuiescence(long timeout, TimeUnit unit) {
        try {
            MIRROR_EXECUTOR.submit(() -> {}).get(timeout, unit);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("awaitMirrorQuiescence did not complete cleanly: {}", e.getMessage());
            return false;
        }
    }

    private final Path contextFile;
    private final Path logFile;
    private final Path workspaceRoot;
    private final AbstractFilesystem filesystem;
    private final WorkspaceIndex index;
    private final String contextRelativePath;
    private final String logRelativePath;
    private final String writerId = UUID.randomUUID().toString().substring(0, 8);

    private TranscriptStore transcriptStore;
    private TranscriptRef transcriptRef;

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
        this(contextFile, workspaceRoot, filesystem, null, null);
    }

    public SessionTree(
            Path contextFile,
            Path workspaceRoot,
            AbstractFilesystem filesystem,
            WorkspaceIndex index) {
        this(contextFile, workspaceRoot, filesystem, index, null);
    }

    /**
     * Creates a SessionTree with optional best-effort workspace index support.
     *
     * @param contextFile          path to the {@code .jsonl} context file
     * @param workspaceRoot        root of the agent workspace
     * @param filesystem           remote filesystem; may be {@code null}
     * @param index                best-effort workspace index; may be {@code null}
     * @param contextRelativePath  workspace-relative path for the context file WITHOUT namespace
     *                             prefix (e.g. {@code agents/X/sessions/Y.jsonl}); when non-null,
     *                             used for filesystem mirror/restore instead of computing via
     *                             {@link #toWorkspaceRelative(Path)}
     */
    public SessionTree(
            Path contextFile,
            Path workspaceRoot,
            AbstractFilesystem filesystem,
            WorkspaceIndex index,
            String contextRelativePath) {
        this.contextFile = contextFile;
        String name = contextFile.getFileName().toString();
        String baseName = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        this.logFile = contextFile.resolveSibling(baseName + ".log.jsonl");
        this.workspaceRoot = workspaceRoot;
        this.filesystem = filesystem;
        this.index = index;
        this.contextRelativePath = contextRelativePath;
        if (contextRelativePath != null) {
            String dir =
                    contextRelativePath.contains("/")
                            ? contextRelativePath.substring(
                                    0, contextRelativePath.lastIndexOf('/') + 1)
                            : "";
            this.logRelativePath = dir + baseName + ".log.jsonl";
        } else {
            this.logRelativePath = null;
        }
    }

    /**
     * Binds a {@link RuntimeContext} to this tree so that all subsequent remote filesystem reads
     * and writes (including async mirror operations) propagate the caller's identity into
     * namespace-aware stores. Defaults to {@link RuntimeContext#empty()} when never set.
     *
     * @param rc the runtime context to bind; {@code null} resets to empty
     * @return this tree, for fluent chaining
     */
    public SessionTree setRuntimeContext(RuntimeContext rc) {
        this.fsRc = rc != null ? rc : RuntimeContext.empty();
        return this;
    }

    /**
     * Binds a {@link TranscriptStore} for segmented remote persistence. When set, {@link #flush()}
     * writes immutable segments instead of full-file mirrors, and {@link #syncFromRemote()} merges
     * from listed segments.
     * history UI paths stay aligned with the local workspace. {@code null} disables segments
     *
     * @param store segment store; {@code null} disables segmented persistence
     * @param ref   transcript identity; required when {@code store} is non-null
     * @return this tree, for fluent chaining
     */
    public SessionTree setTranscriptStore(TranscriptStore store, TranscriptRef ref) {
        this.transcriptStore = store;
        this.transcriptRef = store == null ? null : ref;
        return this;
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
     * Pulls remote entries and union-merges any not yet present locally.
     *
     * <p>When a {@link TranscriptStore} is bound, reads immutable segments (no whole-file
     * overwrite race). Otherwise falls back to the legacy full-file remote mirror.
     *
     * <p>{@link #load()} must be called before this method.
     */
    public void syncFromRemote() {
        if (transcriptStore != null && transcriptRef != null) {
            syncFromTranscriptStore();
            return;
        }
        if (filesystem == null || workspaceRoot == null) {
            return;
        }

        List<SessionEntry> remoteEntries = pullRemoteEntries(contextFile);
        if (remoteEntries.isEmpty()) {
            return;
        }
        int before = appendOrder.size();
        mergeRemoteEntries(remoteEntries);
        int added = appendOrder.size() - before;
        if (added > 0) {
            log.info(
                    "syncFromRemote: merged {} new remote entries into local session file {}",
                    added,
                    contextFile.getFileName());
        }
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
     * Flushes pending entries to the local context and log files, then schedules asynchronous
     * remote mirrors. When a {@link TranscriptStore} is bound, both immutable segments and the
     * canonical context/log files are mirrored. The remote mirror is fire-and-forget: failures are
     * logged as warnings and do not affect the return of this method. The local write is always the
     * primary guarantee.
     */
    public void flush() {
        if (pendingWrites.isEmpty()) {
            return;
        }

        flushed = true;
        List<SessionEntry> toWrite = new ArrayList<>(pendingWrites);
        pendingWrites.clear();

        long seqEnd = appendOrder.size() - 1L;
        long seqStart = seqEnd - toWrite.size() + 1L;

        appendToFile(contextFile, toWrite);
        appendToFile(logFile, toWrite);

        if (transcriptStore != null && transcriptRef != null) {
            scheduleSegmentMirror(toWrite, seqStart, seqEnd);
        }
        // Align with the local session write logic and provide read access
        // to the historical UI / sandbox
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

    private void syncFromTranscriptStore() {
        try {
            TranscriptStore scopedStore = transcriptStore.withRuntimeContext(fsRc);
            List<TranscriptStore.SegmentInfo> segments = scopedStore.listSegments(transcriptRef);
            if (segments.isEmpty()) {
                return;
            }
            List<SessionEntry> remoteEntries = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (TranscriptStore.SegmentInfo seg : segments) {
                try (InputStream in = scopedStore.readSegment(seg.key())) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (SessionEntry e : parseJsonlEntries(content)) {
                        if (seen.add(e.getId())) {
                            remoteEntries.add(e);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to read transcript segment {}: {}", seg.key(), e.getMessage());
                }
            }
            if (!remoteEntries.isEmpty()) {
                mergeRemoteEntries(remoteEntries);
            }
        } catch (Exception e) {
            log.warn("syncFromTranscriptStore failed: {}", e.getMessage());
        }
    }

    /**
     * Union-merges remote entries into local state: remote base first, then local-only extras.
     * New remote-only entries are appended to the local log; context file is rewritten.
     */
    private void mergeRemoteEntries(List<SessionEntry> remoteEntries) {
        Set<String> localIds =
                appendOrder.stream().map(SessionEntry::getId).collect(Collectors.toSet());

        List<SessionEntry> remoteNewEntries =
                remoteEntries.stream().filter(re -> !localIds.contains(re.getId())).toList();
        if (remoteNewEntries.isEmpty()) {
            return;
        }

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

        for (SessionEntry entry : remoteNewEntries) {
            entriesById.put(entry.getId(), entry);
        }
        appendOrder.clear();
        appendOrder.addAll(merged);
        for (SessionEntry entry : remoteNewEntries) {
            if (entry instanceof SessionEntry.CompactionEntry ce) {
                lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                lastSummaryEntryId = ce.getSummaryEntryId();
            }
        }
    }

    private void scheduleSegmentMirror(List<SessionEntry> entries, long seqStart, long seqEnd) {
        if (transcriptStore == null || transcriptRef == null || entries.isEmpty()) {
            return;
        }
        // Same pin as scheduleMirror: async segment upload must survive call unbind.
        final AbstractFilesystem mirrorFs = pinIfSandbox(filesystem);
        final TranscriptStore store = transcriptStoreForMirror(mirrorFs);
        final TranscriptRef ref = transcriptRef;
        StringBuilder sb = new StringBuilder();
        for (SessionEntry entry : entries) {
            sb.append(JsonUtils.getJsonCodec().toJson(entry)).append('\n');
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        String wid = writerId;
        MIRROR_EXECUTOR.execute(
                () -> {
                    try {
                        store.appendSegment(ref, seqStart, seqEnd, wid, payload);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to append transcript segment for {}: {}",
                                ref.prefix(),
                                e.getMessage());
                    }
                });
    }

    /**
     * Schedules an asynchronous, best-effort mirror of both session files to the remote
     * filesystem. Uses a daemon single-thread executor to serialise uploads and avoid
     * blocking the caller on remote I/O.
     * Avoid the situation where the asynchronous write sandbox pre-agent call has been completed,
     * resulting in the unbinding of the sandbox and the error message "No active sandbox".
     */
    private void scheduleMirror() {
        if (filesystem == null || workspaceRoot == null) {
            return;
        }
        final AbstractFilesystem mirrorFs = pinIfSandbox(filesystem);
        final String contextRel = resolveRelativePath(contextFile);
        final String logRel = resolveRelativePath(logFile);
        MIRROR_EXECUTOR.execute(
                () -> {
                    mirrorToFilesystem(mirrorFs, contextFile, contextRel);
                    mirrorToFilesystem(mirrorFs, logFile, logRel);
                });
    }

    /**
     * When {@code fs} is a call-scoped sandbox proxy with an active binding, return a pinned
     * filesystem that keeps that sandbox for async uploads. Otherwise return {@code fs} as-is.
     */
    private static AbstractFilesystem pinIfSandbox(AbstractFilesystem fs) {
        if (fs instanceof SandboxAware aware) {
            Sandbox sb = aware.getSandbox();
            if (sb != null) {
                return new PinnedSandboxFilesystem(sb);
            }
        }
        return fs;
    }

    private TranscriptStore transcriptStoreForMirror(AbstractFilesystem mirrorFs) {
        if (transcriptStore instanceof ObjectStoreTranscriptStore ost) {
            return ost.withFilesystem(mirrorFs).withRuntimeContext(fsRc);
        }
        return transcriptStore.withRuntimeContext(fsRc);
    }

    /**
     * Fetches the remote copy of {@code file} and parses it as JSONL session entries.
     * Returns an empty list if no filesystem is configured or the remote read fails.
     */
    private List<SessionEntry> pullRemoteEntries(Path file) {
        if (filesystem == null || workspaceRoot == null) {
            return List.of();
        }
        String relativePath = resolveRelativePath(file);
        if (relativePath == null || relativePath.isBlank()) {
            return List.of();
        }
        ReadResult read = filesystem.read(fsRc, relativePath, 0, 0);
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
    private void mirrorToFilesystem(AbstractFilesystem fs, Path file, String relativePath) {
        if (fs == null || workspaceRoot == null || !Files.isRegularFile(file)) {
            return;
        }
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            fs.uploadFiles(fsRc, List.of(Map.entry(relativePath, bytes)));
            // Best-effort: the local file already exists — update index with its current stats
            if (index != null) {
                index.upsertFromLocalFile(relativePath, file);
            }
        } catch (IOException e) {
            log.warn("Failed to mirror session file {} to filesystem: {}", file, e.getMessage());
        } catch (RuntimeException e) {
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
        String relativePath = resolveRelativePath(file);
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        ReadResult read = filesystem.read(fsRc, relativePath, 0, 0);
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
            // Best-effort: record restored file in local index
            if (index != null) {
                index.upsertFromLocalFile(relativePath, file);
            }
        } catch (IOException e) {
            log.warn(
                    "Failed to restore session file {} from filesystem mirror: {}",
                    file,
                    e.getMessage());
        }
    }

    private String resolveRelativePath(Path file) {
        if (contextRelativePath != null) {
            if (file.equals(contextFile)) {
                return contextRelativePath;
            }
            if (file.equals(logFile)) {
                return logRelativePath;
            }
        }
        return toWorkspaceRelative(file);
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
