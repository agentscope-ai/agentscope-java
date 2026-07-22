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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that performs periodic memory maintenance after each agent call.
 *
 * <p>Fires on the agent invocation completion (via {@code onAgent concatWith}, after
 * {@link MemoryFlushMiddleware}) and is throttled by a configurable minimum gap so it
 * does not run on every single call.
 *
 * <p>Maintenance steps executed in order:
 * <ol>
 *   <li>Expire daily memory files older than {@code dailyFileRetentionDays} by moving
 *       them to {@code memory/archive/}.</li>
 *   <li>Run LLM-based consolidation ({@link MemoryConsolidator#consolidate}) if a
 *       consolidator is configured.</li>
 *   <li>Prune session log files older than {@code sessionRetentionDays}.</li>
 * </ol>
 *
 * <p>The throttle window is tracked per <em>isolation key</em>, which matches the memory data
 * isolation in use:
 * <ul>
 *   <li>{@link IsolationScope#USER} (default) — one window per {@code userId}.</li>
 *   <li>{@link IsolationScope#SESSION} — one window per {@code sessionId}.</li>
 *   <li>{@link IsolationScope#AGENT} / {@link IsolationScope#GLOBAL} — one shared window for
 *       the whole agent instance (prevents concurrent maintenance races on shared memory files).</li>
 * </ul>
 */
public class MemoryMaintenanceMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceMiddleware.class);

    /** Default minimum gap between two maintenance runs. */
    public static final Duration DEFAULT_MIN_GAP = Duration.ofMinutes(30);

    private final WorkspaceManager workspaceManager;
    private final MemoryConsolidator consolidator;
    private final int dailyFileRetentionDays;
    private final int sessionRetentionDays;
    private final Duration minGap;
    private final IsolationScope isolationScope;

    /**
     * Process-wide per-isolation-key maintenance timestamps. Static so that the throttle window
     * survives across {@code HarnessAgent.Builder.build()} calls — each rebuild creates a new
     * middleware instance, and an instance-level map would reset to {@link Instant#EPOCH} on
     * every request, defeating the gap-based throttle.
     *
     * <p>The key is a composite of {@link IsolationScope} name and the per-call identity
     * (see {@link #timerKeyFor(RuntimeContext)}) so the shared map correctly isolates throttle
     * windows across scope dimensions.
     */
    static final ConcurrentHashMap<String, AtomicReference<Instant>> SHARED_LAST_RUN_AT =
            new ConcurrentHashMap<>();

    /**
     * Entries in {@link #SHARED_LAST_RUN_AT} whose timestamp is older than this threshold
     * are considered stale and removed on the next cleanup sweep. This bounds the map size
     * in long-running services with high user/session churn.
     */
    static final Duration STALE_ENTRY_MAX_AGE = Duration.ofMinutes(60);

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                sessionRetentionDays,
                minGap,
                IsolationScope.USER);
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap,
            IsolationScope isolationScope) {
        this.workspaceManager = workspaceManager;
        this.consolidator = consolidator;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.sessionRetentionDays = sessionRetentionDays;
        this.minGap = minGap != null ? minGap : DEFAULT_MIN_GAP;
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager, MemoryConsolidator consolidator) {
        this(workspaceManager, consolidator, 90, 180, DEFAULT_MIN_GAP);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        return next.apply(input)
                .concatWith(
                        Mono.<AgentEvent>fromRunnable(() -> maybeRunMaintenance(rc))
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(
                                        e -> {
                                            log.warn(
                                                    "Memory maintenance failed: {}",
                                                    e.getMessage());
                                            return Mono.empty();
                                        }));
    }

    private void maybeRunMaintenance(RuntimeContext rc) {
        Instant now = Instant.now();
        AtomicReference<Instant> ref = lastRunAtFor(rc);
        Instant last = ref.get();
        if (Duration.between(last, now).compareTo(minGap) < 0) {
            return;
        }
        if (!ref.compareAndSet(last, now)) {
            return;
        }
        try {
            runMaintenance(rc);
        } catch (Exception e) {
            log.warn("Memory maintenance failed: {}", e.getMessage());
        }
    }

    private AtomicReference<Instant> lastRunAtFor(RuntimeContext rc) {
        return SHARED_LAST_RUN_AT.computeIfAbsent(
                compositeTimerKey(rc), k -> new AtomicReference<>(Instant.EPOCH));
    }

    /**
     * Removes entries whose timestamp is older than {@link #STALE_ENTRY_MAX_AGE} from
     * {@link #SHARED_LAST_RUN_AT}. This bounds the map size in long-running services with
     * high user/session churn — stale entries represent keys that have not run maintenance
     * recently and are safe to re-create on demand.
     *
     * <p>Package-private for unit testing.
     */
    static void cleanupStaleEntries() {
        Instant cutoff = Instant.now().minus(STALE_ENTRY_MAX_AGE);
        SHARED_LAST_RUN_AT.entrySet().removeIf(e -> e.getValue().get().isBefore(cutoff));
    }

    /**
     * Builds a composite key from {@link IsolationScope} name and the per-call identity returned
     * by {@link #timerKeyFor(RuntimeContext)}. The scope prefix ensures that the shared
     * {@link #SHARED_LAST_RUN_AT} map never conflates throttle windows from different
     * isolation dimensions.
     */
    private String compositeTimerKey(RuntimeContext rc) {
        return isolationScope.name() + ":" + timerKeyFor(rc);
    }

    /**
     * Derives the per-call identity portion of the composite timer key from the configured
     * {@link IsolationScope} and the {@link RuntimeContext}, mirroring the memory data
     * namespace. See {@link MemoryFlushMiddleware#timerKeyFor(RuntimeContext)} for the
     * identical logic.
     */
    String timerKeyFor(RuntimeContext rc) {
        return switch (isolationScope) {
            case USER -> {
                String uid = rc != null ? rc.getUserId() : null;
                yield (uid != null && !uid.isBlank()) ? uid : "";
            }
            case SESSION -> {
                String sid = rc != null ? rc.getSessionId() : null;
                yield (sid != null && !sid.isBlank()) ? sid : "";
            }
            case AGENT, GLOBAL -> "";
        };
    }

    private void runMaintenance(RuntimeContext rc) {
        log.debug("Running memory maintenance...");
        expireDailyFiles(rc);
        consolidateMemory(rc);
        pruneOldSessions(rc);
        log.debug("Memory maintenance completed");
    }

    private void expireDailyFiles(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.md", WorkspaceConstants.MEMORY_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        LocalDate cutoff = LocalDate.now().minusDays(dailyFileRetentionDays);
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String fileName = fileName(fi.path());
            if (fileName.startsWith(".")) {
                continue;
            }
            String baseName =
                    fileName.endsWith(".md")
                            ? fileName.substring(0, fileName.length() - 3)
                            : fileName;
            try {
                LocalDate fileDate = LocalDate.parse(baseName);
                if (fileDate.isBefore(cutoff)) {
                    String fromPath = WorkspaceConstants.MEMORY_DIR + "/" + fileName;
                    String toPath = WorkspaceConstants.MEMORY_DIR + "/archive/" + fileName;
                    fs.move(rc, fromPath, toPath);
                    log.debug("Archived expired daily file: {}", fileName);
                }
            } catch (Exception e) {
                // not a date-named file, skip
            }
        }
    }

    private void consolidateMemory(RuntimeContext rc) {
        if (consolidator == null) {
            return;
        }
        try {
            consolidator.consolidate(rc).block();
        } catch (Exception e) {
            log.warn("Memory consolidation failed: {}", e.getMessage());
        }
    }

    private void pruneOldSessions(RuntimeContext rc) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (fs == null) {
            return;
        }
        GlobResult glob = fs.glob(rc, "*.log.jsonl", WorkspaceConstants.AGENTS_DIR);
        if (glob == null || glob.matches() == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(sessionRetentionDays));
        for (FileInfo fi : glob.matches()) {
            if (fi.isDirectory()) {
                continue;
            }
            String modifiedAt = fi.modifiedAt();
            if (modifiedAt == null || modifiedAt.isEmpty()) {
                continue;
            }
            try {
                Instant modified = Instant.parse(modifiedAt);
                if (modified.isBefore(cutoff)) {
                    fs.delete(rc, fi.path());
                    log.debug("Pruned old session file: {}", fi.path());
                }
            } catch (Exception e) {
                log.warn("Failed to check/prune {}: {}", fi.path(), e.getMessage());
            }
        }
    }

    private static String fileName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
