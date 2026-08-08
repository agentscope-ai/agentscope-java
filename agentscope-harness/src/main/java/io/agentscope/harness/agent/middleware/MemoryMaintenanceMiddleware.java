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
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Middleware that performs periodic memory maintenance after each agent call.
 *
 * <p>Fires in a genuinely detached, fire-and-forget fashion: the maintenance {@code Mono} is
 * subscribed independently of the returned {@code Flux} (via {@code doOnComplete}) rather than
 * being concatenated onto it, so callers that wait for the response to complete (e.g.
 * {@code blockLast()}, {@code takeLast(1)}) are not delayed by maintenance work. It is also
 * throttled by a configurable minimum gap so it does not run on every single call.
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
public class MemoryMaintenanceMiddleware implements HarnessRuntimeMiddleware, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceMiddleware.class);

    /** Default minimum gap between two maintenance runs. */
    public static final Duration DEFAULT_MIN_GAP = Duration.ofMinutes(30);

    /**
     * Upper bound on the consolidation LLM call. Set slightly below
     * {@link #MAINTENANCE_TIMEOUT} so the error log can distinguish "consolidation itself
     * timed out" from "the entire maintenance run timed out".
     */
    static final Duration CONSOLIDATION_TIMEOUT = Duration.ofMinutes(4).plusSeconds(30);

    /** Upper bound on the entire maintenance run (file IO + LLM), consistent with flush. */
    static final Duration MAINTENANCE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Upper bound on concurrently pending detached maintenance runs, symmetric to {@code
     * MemoryFlushMiddleware#MAX_PENDING_FLUSHES}. Maintenance is throttle-gated so this is a
     * defensive bound against a pathologically slow consolidator, not an expected state.
     */
    static final int MAX_PENDING_MAINTENANCE = 4;

    private final WorkspaceManager workspaceManager;
    private final MemoryConsolidator consolidator;
    private final int dailyFileRetentionDays;
    private final int sessionRetentionDays;
    private final Duration minGap;
    private final IsolationScope isolationScope;
    private final PeriodicGate periodicGate;
    private final boolean asyncMaintenance;

    /** Upper bound {@link #close()} waits for outstanding fire-and-forget runs to drain. */
    static final Duration CLOSE_AWAIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Tracks the {@link Disposable} of every fire-and-forget maintenance subscription that has
     * been scheduled but not yet finished, so {@link #close()} can wait for/dispose them instead
     * of leaving them racing against teardown of the resources they read/write (e.g. a workspace
     * directory being deleted).
     */
    private final Set<Disposable> pending = ConcurrentHashMap.newKeySet();

    private volatile boolean closed = false;

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
                IsolationScope.USER,
                new LocalPeriodicGate());
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap,
            IsolationScope isolationScope) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                sessionRetentionDays,
                minGap,
                isolationScope,
                new LocalPeriodicGate());
    }

    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap,
            IsolationScope isolationScope,
            PeriodicGate periodicGate) {
        this(
                workspaceManager,
                consolidator,
                dailyFileRetentionDays,
                sessionRetentionDays,
                minGap,
                isolationScope,
                periodicGate,
                true);
    }

    /**
     * Full constructor.
     *
     * @param asyncMaintenance whether maintenance runs detached from the response stream
     *     (default {@code true}). {@code false} restores completion-order persistence — the
     *     response waits for the per-call maintenance check, mirroring {@code
     *     MemoryFlushMiddleware}'s synchronous mode.
     */
    public MemoryMaintenanceMiddleware(
            WorkspaceManager workspaceManager,
            MemoryConsolidator consolidator,
            int dailyFileRetentionDays,
            int sessionRetentionDays,
            Duration minGap,
            IsolationScope isolationScope,
            PeriodicGate periodicGate,
            boolean asyncMaintenance) {
        this.workspaceManager = workspaceManager;
        this.consolidator = consolidator;
        this.dailyFileRetentionDays = dailyFileRetentionDays;
        this.sessionRetentionDays = sessionRetentionDays;
        this.minGap = minGap != null ? minGap : DEFAULT_MIN_GAP;
        this.isolationScope = isolationScope != null ? isolationScope : IsolationScope.USER;
        this.periodicGate = periodicGate != null ? periodicGate : new LocalPeriodicGate();
        this.asyncMaintenance = asyncMaintenance;
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
        // Snapshot the full RuntimeContext (shallow copy of attribute maps) at completion time —
        // not eagerly here — so attributes added during the call are visible, matching the
        // original rc-at-completion semantics. The copy makes the background task immune to
        // concurrent mutations on the caller's attribute maps after the call completes, while
        // preserving all fields that filesystem operations or NamespaceFactory implementations
        // may depend on (not just userId/sessionId).
        Flux<AgentEvent> source = next.apply(input);
        if (asyncMaintenance) {
            return source.doOnComplete(
                    () -> scheduleMaintenance(RuntimeContext.builder().from(rc).build()));
        }
        // Sync mode: fromRunnable runs at subscription time — i.e. after the source completes —
        // so the snapshot below is still taken at completion time, matching the async branch.
        return source.concatWith(
                Mono.fromRunnable(
                                () ->
                                        maybeRunMaintenance(
                                                RuntimeContext.builder().from(rc).build()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(MAINTENANCE_TIMEOUT)
                        .onErrorResume(
                                e -> {
                                    log.warn("Synchronous memory maintenance failed", e);
                                    return Mono.empty();
                                })
                        .then(Mono.empty()));
    }

    /**
     * Fires the fire-and-forget maintenance {@code Mono} on {@code boundedElastic}, tracking its
     * {@link Disposable} in {@link #pending} until it terminates so {@link #close()} can wait for
     * it. No-ops once {@link #close()} has been called, so a call that races with shutdown
     * doesn't spawn new untracked work.
     *
     * <p>The whole method body (RuntimeContext copy, operator assembly, and subscribe) is
     * wrapped in try/catch so that no exception can escape into the {@code doOnComplete}
     * callback and turn an already-completed Flux into an error.
     *
     * <p>The {@code closed} flag and {@code pending} add are guarded by
     * {@code synchronized(pending)} so that maintenance scheduled concurrently with close() is
     * either fully tracked (and drained) or disposed immediately, but never lost. The
     * {@code pending} set (a ConcurrentHashMap key set) is reused as the mutex object to avoid
     * allocating a separate lock; its own concurrency features are not relied upon for the
     * closed-check/add atomicity.
     */
    private void scheduleMaintenance(RuntimeContext snapshot) {
        Disposable[] holder = new Disposable[1];
        // The whole body is wrapped in try/catch so that no exception (from the RuntimeContext
        // copy, operator assembly, or subscribe) can escape into the doOnComplete callback and
        // turn an already-completed Flux into an error.
        try {
            // Subscribe and register inside one critical section: if the subscription were
            // started outside the lock, a fast-completing run (e.g. the throttle gate rejecting
            // within its gap) could run doFinally (which removes the Disposable) before the
            // scheduling thread executed pending.add, permanently leaking a terminated
            // Disposable into pending and stalling close()'s drain until its timeout.
            // doFinally reads holder[0] and removes under the same lock, so the
            // add-before-remove ordering is also guaranteed to be VISIBLE (the holder write
            // happens inside the lock, before its release; the read happens after acquiring
            // it). subscribeOn(boundedElastic) ensures no callback ever runs on this thread, so
            // the lock cannot self-deadlock.
            synchronized (pending) {
                if (closed) {
                    return;
                }
                // Defensive bound, symmetric to the flush middleware's backlog cap.
                if (pending.size() >= MAX_PENDING_MAINTENANCE) {
                    log.warn(
                            "Memory maintenance backlog limit ({}) reached; skipping this run",
                            MAX_PENDING_MAINTENANCE);
                    return;
                }
                Disposable d =
                        Mono.fromRunnable(() -> maybeRunMaintenance(snapshot))
                                .subscribeOn(Schedulers.boundedElastic())
                                .timeout(MAINTENANCE_TIMEOUT)
                                .onErrorResume(
                                        e -> {
                                            log.warn("Memory maintenance failed", e);
                                            return Mono.empty();
                                        })
                                .doFinally(
                                        sig -> {
                                            synchronized (pending) {
                                                if (holder[0] != null) {
                                                    pending.remove(holder[0]);
                                                }
                                            }
                                        })
                                .subscribe();
                holder[0] = d;
                pending.add(d);
            }
        } catch (Exception e) {
            log.warn("Failed to schedule memory maintenance", e);
        }
    }

    /**
     * Waits (bounded by {@link #CLOSE_AWAIT_TIMEOUT}) for outstanding fire-and-forget maintenance
     * runs to finish, then disposes anything still outstanding. Intended to be called from {@code
     * HarnessAgent#close()} so short-lived callers (tests using JUnit {@code @TempDir}, CLI runs,
     * etc.) don't tear down the workspace while a detached maintenance write is still in flight.
     */
    @Override
    public void close() {
        synchronized (pending) {
            closed = true;
        }
        long deadline = System.nanoTime() + CLOSE_AWAIT_TIMEOUT.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Disposable d : pending) {
            d.dispose();
        }
        pending.clear();
    }

    /**
     * Returns whether any fire-and-forget maintenance is currently in flight. Package-private,
     * intended for tests that need to poll for quiescence instead of relying on a fixed sleep.
     */
    boolean hasPendingMaintenance() {
        return !pending.isEmpty();
    }

    private void maybeRunMaintenance(RuntimeContext rc) {
        // rc is a shallow-copy snapshot taken at doOnComplete time (see onAgent), so attribute
        // maps are independent of the caller's original context. Value objects within the maps
        // are shared, but all identity fields (userId, sessionId) and attributes needed by
        // filesystem namespace resolution are preserved.
        if (!periodicGate.tryClaim(compositeTimerKey(rc), minGap)) {
            return;
        }
        try {
            runMaintenance(rc);
        } catch (Exception e) {
            log.warn("Memory maintenance failed", e);
        }
    }

    /**
     * Builds a composite key from {@link IsolationScope} name and the per-call identity returned
     * by {@link #timerKeyFor(RuntimeContext)}. The scope prefix ensures that throttle windows
     * from different isolation dimensions are never conflated.
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
            consolidator.consolidate(rc).timeout(CONSOLIDATION_TIMEOUT).block();
        } catch (Exception e) {
            log.warn("Memory consolidation failed", e);
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
