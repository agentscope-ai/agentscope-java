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
import java.util.List;
import java.util.Map;
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
 * <h2>Deferred persistence</h2>
 * Entries are buffered in memory and only flushed to disk on the first call to {@link #flush()}
 * (typically after the first assistant message). This avoids partial session files from
 * failed/short interactions.
 */
public class SessionTree {

    private static final Logger log = LoggerFactory.getLogger(SessionTree.class);
    private static final int SESSION_FORMAT_VERSION = 1;

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

    public SessionTree(Path contextFile) {
        this(contextFile, null, null);
    }

    public SessionTree(Path contextFile, Path workspaceRoot, AbstractFilesystem filesystem) {
        this.contextFile = contextFile;
        String name = contextFile.getFileName().toString();
        String baseName = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        this.logFile = contextFile.resolveSibling(baseName + ".log.jsonl");
        this.workspaceRoot = workspaceRoot;
        this.filesystem = filesystem;
    }

    /**
     * Loads existing entries from the context JSONL file (if it exists).
     * Safe to call multiple times; only loads once.
     */
    public void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        restoreFromMirror(contextFile);
        if (!Files.isRegularFile(contextFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(contextFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    SessionEntry entry =
                            JsonUtils.getJsonCodec().fromJson(line, SessionEntry.class);
                    entriesById.put(entry.getId(), entry);
                    appendOrder.add(entry);

                    if (entry instanceof SessionEntry.CompactionEntry ce) {
                        lastCompactionFirstKeptId = ce.getFirstKeptEntryId();
                        lastSummaryEntryId = ce.getSummaryEntryId();
                    }
                } catch (Exception e) {
                    log.warn("Skipping malformed session entry: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load session file {}: {}", contextFile, e.getMessage());
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
     * Flushes all pending entries to both the context file and the log file.
     * Creates parent directories as needed. Marks the session as flushed.
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
        mirrorToFilesystem(contextFile);
        mirrorToFilesystem(logFile);
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

    private void restoreFromMirror(Path file) {
        if (filesystem == null || workspaceRoot == null || Files.isRegularFile(file)) {
            return;
        }
        String relativePath = toWorkspaceRelative(file);
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        ReadResult read = filesystem.read(relativePath, 0, 0);
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
            filesystem.uploadFiles(List.of(Map.entry(relativePath, bytes)));
        } catch (IOException e) {
            log.warn("Failed to mirror session file {} to filesystem: {}", file, e.getMessage());
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
