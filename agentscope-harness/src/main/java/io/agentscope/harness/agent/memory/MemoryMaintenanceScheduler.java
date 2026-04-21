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
package io.agentscope.harness.agent.memory;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic background maintenance for the memory system.
 *
 * <p>Performs:
 * <ul>
 *   <li>LLM-based memory consolidation (daily files into MEMORY.md)</li>
 *   <li>Expiration/archival of old daily memory files</li>
 *   <li>Pruning of old session files</li>
 *   <li>Re-indexing of memory files into the FTS5 index</li>
 * </ul>
 *
 * <p>Runs on a configurable interval (default: every 6 hours).
 */
public class MemoryMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceScheduler.class);

    /** Minimum gap between two opportunistic (post-flush) consolidations. */
    private static final Duration MIN_OPPORTUNISTIC_GAP = Duration.ofMinutes(30);

    private final WorkspaceManager workspaceManager;
    private final MemoryIndex memoryIndex;
    private final MemoryConsolidator consolidator;
    private final Duration interval;
    private final int dailyFileRetentionDays;
    private final int sessionRetentionDays;

    private ScheduledExecutorService executor;
    private final java.util.concurrent.atomic.AtomicReference<Instant> lastConsolidationAt =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);

    public MemoryMaintenanceScheduler(
            WorkspaceManager workspaceManager,
            MemoryIndex memoryIndex,
            MemoryConsolidator consolidator,
            Duration interval,
            int dailyFileRetentionDays,
            int sessionRetentionDays) {
        this.workspaceManager = workspaceManager;
        this.memoryIndex = memoryIndex;
        this.consolidator = consolidator;
        this.interval = interval;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.sessionRetentionDays = sessionRetentionDays;
    }

    public MemoryMaintenanceScheduler(
            WorkspaceManager workspaceManager, MemoryIndex memoryIndex, Model model) {
        this(
                workspaceManager,
                memoryIndex,
                model != null ? new MemoryConsolidator(workspaceManager, model) : null,
                Duration.ofHours(6),
                90,
                180);
    }

    public MemoryMaintenanceScheduler(WorkspaceManager workspaceManager, MemoryIndex memoryIndex) {
        this(
                workspaceManager,
                memoryIndex,
                (MemoryConsolidator) null,
                Duration.ofHours(6),
                90,
                180);
    }

    public void start() {
        if (executor != null) {
            return;
        }
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "memory-maintenance");
                            t.setDaemon(true);
                            return t;
                        });
        executor.scheduleAtFixedRate(
                this::runMaintenance,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Memory maintenance scheduler started (interval={})", interval);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void runMaintenance() {
        try {
            log.debug("Running memory maintenance...");
            expireDailyFiles();
            consolidateMemory();
            pruneOldSessions();
            reindex();
            log.debug("Memory maintenance completed");
        } catch (Exception e) {
            log.warn("Memory maintenance failed: {}", e.getMessage());
        }
    }

    /**
     * Requests an opportunistic consolidation, typically called right after
     * {@link io.agentscope.harness.agent.memory.MemoryFlushManager} appends to a daily ledger.
     *
     * <p>To avoid running for every flush, the request is throttled by
     * {@link #MIN_OPPORTUNISTIC_GAP} relative to the previous consolidation. The work
     * itself is dispatched onto the maintenance executor when it is running, so it never
     * blocks the agent's reasoning loop. If the executor is not running, the request is
     * silently skipped (the periodic timer will pick it up later).
     */
    public void requestConsolidation() {
        if (consolidator == null) {
            return;
        }
        ScheduledExecutorService exec = this.executor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        Instant now = Instant.now();
        Instant last = lastConsolidationAt.get();
        if (Duration.between(last, now).compareTo(MIN_OPPORTUNISTIC_GAP) < 0) {
            return;
        }
        if (!lastConsolidationAt.compareAndSet(last, now)) {
            return; // another caller raced ahead
        }
        try {
            exec.submit(this::consolidateMemory);
        } catch (Exception e) {
            log.debug("Failed to submit opportunistic consolidation: {}", e.getMessage());
        }
    }

    private void consolidateMemory() {
        if (consolidator == null) {
            return;
        }
        try {
            consolidator.consolidate().block();
            lastConsolidationAt.set(Instant.now());
        } catch (Exception e) {
            log.warn("Memory consolidation failed: {}", e.getMessage());
        }
    }

    private void expireDailyFiles() {
        Path memoryDir = workspaceManager.getMemoryDir();
        if (!Files.isDirectory(memoryDir)) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(dailyFileRetentionDays);
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .forEach(
                            p -> {
                                String name = p.getFileName().toString().replace(".md", "");
                                try {
                                    LocalDate fileDate = LocalDate.parse(name);
                                    if (fileDate.isBefore(cutoff)) {
                                        Path archiveDir = memoryDir.resolve("archive");
                                        try {
                                            Files.createDirectories(archiveDir);
                                            Files.move(p, archiveDir.resolve(p.getFileName()));
                                            log.debug("Archived expired daily file: {}", name);
                                        } catch (IOException e) {
                                            log.warn(
                                                    "Failed to archive {}: {}",
                                                    name,
                                                    e.getMessage());
                                        }
                                    }
                                } catch (Exception e) {
                                    // not a date-named file, skip
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to list memory dir: {}", e.getMessage());
        }
    }

    private void pruneOldSessions() {
        Path agentsDir = workspaceManager.getWorkspace().resolve(WorkspaceConstants.AGENTS_DIR);
        if (!Files.isDirectory(agentsDir)) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(sessionRetentionDays));
        try (Stream<Path> agents = Files.list(agentsDir)) {
            agents.filter(Files::isDirectory)
                    .forEach(
                            agentDir -> {
                                Path sessionsDir =
                                        agentDir.resolve(WorkspaceConstants.SESSIONS_DIR);
                                if (!Files.isDirectory(sessionsDir)) {
                                    return;
                                }
                                try (Stream<Path> sessions = Files.list(sessionsDir)) {
                                    sessions.filter(Files::isRegularFile)
                                            .filter(
                                                    p ->
                                                            !p.getFileName()
                                                                    .toString()
                                                                    .equals(
                                                                            WorkspaceConstants
                                                                                    .SESSIONS_STORE))
                                            .forEach(
                                                    p -> {
                                                        try {
                                                            Instant modified =
                                                                    Files.getLastModifiedTime(p)
                                                                            .toInstant();
                                                            if (modified.isBefore(cutoff)) {
                                                                Files.delete(p);
                                                                log.debug(
                                                                        "Pruned old session file:"
                                                                                + " {}",
                                                                        p);
                                                            }
                                                        } catch (IOException e) {
                                                            log.warn(
                                                                    "Failed to check/prune {}: {}",
                                                                    p,
                                                                    e.getMessage());
                                                        }
                                                    });
                                } catch (IOException e) {
                                    log.warn("Failed to list sessions: {}", e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to list agents dir: {}", e.getMessage());
        }
    }

    private void reindex() {
        if (memoryIndex == null) {
            return;
        }
        try {
            memoryIndex.indexAllFromWorkspace(workspaceManager);
        } catch (Exception e) {
            log.warn("Failed to reindex: {}", e.getMessage());
        }
    }
}
