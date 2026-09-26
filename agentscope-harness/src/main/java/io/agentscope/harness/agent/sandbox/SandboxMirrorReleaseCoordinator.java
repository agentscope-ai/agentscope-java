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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defers self-managed sandbox {@code stop}/{@code shutdown} until asynchronous session mirrors
 * that still need the live connection have finished.
 *
 * <p>Call-scoped binding is still cleared immediately by the lifecycle middleware. Only the
 * destructive release (and its {@link SandboxLease}) is handed off when mirror tasks remain.
 *
 * <p>Deferred {@code stop}/{@code shutdown} runs on a dedicated daemon executor so slow sandbox
 * teardown does not block the session-tree mirror thread.
 *
 * <p>Actual teardown order is always {@link SandboxManager#release} (stop/shutdown) → optional
 * {@link SandboxManager#persistState} → lease close. Persist runs after stop so mutations made
 * during {@code stop()} (e.g. E2B per-session snapshot records from issue #2555) are written.
 *
 * <p>When release+persist is deferred for an isolation key, {@link #awaitPendingScopeRelease}
 * blocks a later {@link SandboxManager#acquire} on that same key until the deferred work finishes,
 * so SESSION/USER resume does not race ahead of post-stop persist.
 *
 * <p>{@link #requestRelease} always closes {@link SandboxAcquireResult#getLease()}: the acquire
 * result guarantees a non-null lease ({@link SandboxLease#noop()} when no guard is configured),
 * including {@link SandboxAcquireResult#userManaged} paths where {@link SandboxManager#release}
 * is a no-op.
 *
 * <p>When multiple concurrent calls share one self-managed sandbox instance, each call's {@link
 * #requestRelease} is queued separately and flushed when outstanding mirrors drain — one entry
 * per call, not last-writer-wins.
 *
 * <p>Usage:
 *
 * <ol>
 *   <li>{@link #retain(Sandbox)} once per scheduled mirror task that pins the sandbox
 *   <li>{@link #requestRelease(SandboxManager, SandboxAcquireResult, SandboxContext,
 *       RuntimeContext)} from call teardown (or the two-arg overload when persist is not needed)
 *   <li>{@link #releaseMirror(Sandbox)} in the mirror task {@code finally} block
 * </ol>
 */
public final class SandboxMirrorReleaseCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(SandboxMirrorReleaseCoordinator.class);

    private static final SandboxMirrorReleaseCoordinator INSTANCE =
            new SandboxMirrorReleaseCoordinator();

    /**
     * Serialises deferred sandbox destruction off the mirror upload thread so a slow
     * {@code stop}/{@code shutdown} cannot head-of-line block other session mirrors.
     */
    private static final ExecutorService RELEASE_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "sandbox-mirror-release");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * How long {@link #awaitPendingScopeRelease(SandboxIsolationKey)} waits for a deferred
     * stop+persist on the same isolation key before giving up and letting acquire proceed.
     */
    private static final long DEFAULT_SCOPE_RELEASE_WAIT_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Object lock = new Object();
    private final IdentityHashMap<Sandbox, State> states = new IdentityHashMap<>();

    /** Outstanding deferred release+persist counts keyed by isolation slot. */
    private final Map<SandboxIsolationKey, Integer> pendingScopeReleases = new HashMap<>();

    private SandboxMirrorReleaseCoordinator() {}

    /**
     * Increments the outstanding-mirror count for {@code sandbox}. Call once for each async
     * mirror task that will use the pinned sandbox connection.
     *
     * @param sandbox sandbox retained for a mirror task; ignored when {@code null}
     */
    public static void retain(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        INSTANCE.doRetain(sandbox);
    }

    /**
     * Decrements the outstanding-mirror count. When the count reaches zero and call teardown
     * already requested release, schedules stop/persist/lease-close on the dedicated release
     * executor for every deferred call result.
     *
     * @param sandbox sandbox previously passed to {@link #retain(Sandbox)}
     */
    public static void releaseMirror(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        INSTANCE.doReleaseMirror(sandbox);
    }

    /**
     * Requests destruction without post-stop state persist (e.g. acquire/start failure before any
     * stop-time mutation exists). Equivalent to {@link #requestRelease(SandboxManager,
     * SandboxAcquireResult, SandboxContext, RuntimeContext)} with null persist context.
     *
     * @param manager sandbox manager that owns stop/shutdown
     * @param result acquire result from the finishing call
     */
    public static void requestRelease(SandboxManager manager, SandboxAcquireResult result) {
        requestRelease(manager, result, null, null);
    }

    /**
     * Requests destruction of a call-acquired sandbox. Self-managed sandboxes with outstanding
     * mirrors defer {@code stop}/{@code shutdown}, post-stop persist, and lease close until
     * {@link #releaseMirror} drains the count. User-managed sandboxes always tear down immediately
     * (manager no-ops shutdown/persist).
     *
     * <p>When there are no outstanding mirrors, teardown runs synchronously on the caller thread.
     * A tombstone marks the sandbox as already released so a late {@link #retain} cannot leave
     * {@code pendingMirrors > 0} with an empty deferred list and silently skip destruction.
     *
     * <p>When mirrors are pending, each call's acquire result (and persist context) is appended to
     * the deferred list so shared-sandbox concurrent calls each get release + persist + lease
     * close.
     *
     * @param manager sandbox manager that owns stop/shutdown/persist
     * @param result acquire result from the finishing call
     * @param sandboxContext isolation context for {@link SandboxManager#persistState}; may be
     *     {@code null} to skip persist
     * @param runtimeContext runtime context for persist scope resolution; may be {@code null}
     */
    public static void requestRelease(
            SandboxManager manager,
            SandboxAcquireResult result,
            SandboxContext sandboxContext,
            RuntimeContext runtimeContext) {
        if (manager == null || result == null || result.getSandbox() == null) {
            return;
        }
        INSTANCE.doRequestRelease(manager, result, sandboxContext, runtimeContext);
    }

    /**
     * Blocks until deferred release tasks submitted before this call have finished.
     *
     * <p>Intended to pair with mirror quiescence during graceful shutdown: mirror drain schedules
     * deferred releases, then this barrier waits for those destructions to complete.
     *
     * @param timeout maximum time to wait
     * @param unit time unit of {@code timeout}
     * @return {@code true} if deferred releases quiesced within the timeout
     */
    public static boolean awaitReleaseQuiescence(long timeout, TimeUnit unit) {
        try {
            RELEASE_EXECUTOR.submit(() -> {}).get(timeout, unit);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("awaitReleaseQuiescence did not complete cleanly: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Blocks while a deferred stop+persist is still outstanding for {@code key}.
     *
     * <p>Called by {@link SandboxManager#acquire} before loading persisted state so a same-scope
     * follow-up call cannot {@code create} ahead of the previous call's post-stop persist.
     *
     * @param key isolation key for the sandbox state slot; ignored when {@code null}
     * @return {@code true} if no pending work remained (or it drained in time); {@code false} on
     *     timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean awaitPendingScopeRelease(SandboxIsolationKey key)
            throws InterruptedException {
        return awaitPendingScopeRelease(
                key, DEFAULT_SCOPE_RELEASE_WAIT_NANOS, TimeUnit.NANOSECONDS);
    }

    /**
     * Like {@link #awaitPendingScopeRelease(SandboxIsolationKey)} with an explicit timeout.
     *
     * @param key isolation key; ignored when {@code null}
     * @param timeout maximum time to wait
     * @param unit time unit of {@code timeout}
     * @return {@code true} if drained (or nothing pending); {@code false} on timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean awaitPendingScopeRelease(
            SandboxIsolationKey key, long timeout, TimeUnit unit) throws InterruptedException {
        if (key == null) {
            return true;
        }
        return INSTANCE.doAwaitPendingScopeRelease(key, timeout, unit);
    }

    /**
     * Drops all retained / deferred state.
     *
     * <p>Intended for unit tests that share the process-wide coordinator.
     */
    public static void resetForTests() {
        synchronized (INSTANCE.lock) {
            INSTANCE.states.clear();
            INSTANCE.pendingScopeReleases.clear();
            INSTANCE.lock.notifyAll();
        }
    }

    /**
     * Test-only: forces {@code pendingMirrors == 0} while keeping any existing registration (and
     * deferred release if present), so the next {@link #releaseMirror} hits the underflow path.
     */
    static void forcePendingZeroForUnderflowTests(Sandbox sandbox) {
        synchronized (INSTANCE.lock) {
            State state = INSTANCE.states.get(sandbox);
            if (state == null) {
                throw new IllegalStateException("expected state for " + sandbox);
            }
            state.pendingMirrors = 0;
        }
    }

    private void doRetain(Sandbox sandbox) {
        synchronized (lock) {
            State state = states.computeIfAbsent(sandbox, ignored -> new State());
            if (state.released) {
                log.error(
                        "[sandbox-mirror-release] late retain after sandbox already released;"
                                + " pairing only — connection will not be kept alive");
            }
            state.pendingMirrors++;
        }
    }

    private void doReleaseMirror(Sandbox sandbox) {
        List<DeferredRelease> toFlush = null;
        boolean clearTombstone = false;
        synchronized (lock) {
            State state = states.get(sandbox);
            if (state == null) {
                return;
            }
            state.pendingMirrors--;
            if (state.pendingMirrors < 0) {
                // Pairing bug: clamp and take over any deferred releases so leases are not
                // silently orphaned.
                log.error(
                        "[sandbox-mirror-release] pending mirror count went negative for sandbox;"
                                + " taking over deferred releases if present");
                state.pendingMirrors = 0;
                if (!state.deferred.isEmpty()) {
                    toFlush = takeDeferred(state);
                    state.released = true;
                    states.remove(sandbox);
                } else {
                    states.remove(sandbox);
                }
            } else if (state.pendingMirrors == 0) {
                if (!state.deferred.isEmpty()) {
                    toFlush = takeDeferred(state);
                    state.released = true;
                    states.remove(sandbox);
                } else if (state.released) {
                    clearTombstone = true;
                    states.remove(sandbox);
                } else {
                    // Mirrors finished with no deferred release request (drained before teardown,
                    // or user-managed path). Drop the retain registration only.
                    states.remove(sandbox);
                }
            }
        }
        if (toFlush != null) {
            for (DeferredRelease deferred : toFlush) {
                scheduleDeferredRelease(deferred);
            }
        } else if (clearTombstone) {
            log.debug(
                    "[sandbox-mirror-release] cleared released tombstone after late retain"
                            + " pairing");
        }
    }

    private void doRequestRelease(
            SandboxManager manager,
            SandboxAcquireResult result,
            SandboxContext sandboxContext,
            RuntimeContext runtimeContext) {
        Optional<SandboxIsolationKey> scopeKey =
                resolveScopeKey(manager, sandboxContext, runtimeContext);
        DeferredRelease work =
                new DeferredRelease(
                        manager, result, sandboxContext, runtimeContext, scopeKey.orElse(null));
        if (!result.isSelfManaged()) {
            performRelease(work);
            return;
        }

        Sandbox sandbox = result.getSandbox();
        boolean releaseNow;
        synchronized (lock) {
            State state = states.get(sandbox);
            if (state != null && state.pendingMirrors > 0) {
                if (state.released) {
                    // Already destructively released (e.g. prior releaseNow); still close this
                    // call's lease so it cannot leak, but do not queue another stop/shutdown.
                    log.warn(
                            "[sandbox-mirror-release] release requested after sandbox already"
                                    + " released; closing lease only");
                    try {
                        result.getLease().close();
                    } catch (Exception e) {
                        log.warn(
                                "[sandbox-mirror-release] Extra lease close failed: {}",
                                e.getMessage(),
                                e);
                    }
                    return;
                }
                state.deferred.add(work);
                if (work.scopeKey != null) {
                    beginPendingScopeReleaseLocked(work.scopeKey);
                }
                log.debug(
                        "[sandbox-mirror-release] deferring release until {} pending mirror(s)"
                                + " finish{}",
                        state.pendingMirrors,
                        work.scopeKey != null ? " (scope=" + work.scopeKey + ")" : "");
                releaseNow = false;
            } else {
                // No outstanding mirrors: sync teardown, then leave a released tombstone so a
                // late retain cannot re-open an "unreleased" registration.
                if (state == null) {
                    state = new State();
                    states.put(sandbox, state);
                }
                state.deferred.clear();
                state.released = true;
                releaseNow = true;
            }
        }
        if (releaseNow) {
            // No outstanding mirrors: keep historical sync teardown on the call thread.
            performRelease(work);
            synchronized (lock) {
                State state = states.get(sandbox);
                if (state != null && state.pendingMirrors == 0 && state.released) {
                    // No late retain raced in: drop the tombstone.
                    states.remove(sandbox);
                }
            }
        }
    }

    private boolean doAwaitPendingScopeRelease(SandboxIsolationKey key, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lock) {
            while (pendingCountLocked(key) > 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    log.error(
                            "[sandbox-mirror-release] timed out waiting for deferred"
                                    + " stop+persist on scope {}; acquire will proceed and may"
                                    + " create a fresh sandbox",
                            key);
                    return false;
                }
                log.debug(
                        "[sandbox-mirror-release] acquire waiting for {} deferred stop+persist"
                                + " on scope {}",
                        pendingCountLocked(key),
                        key);
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return true;
        }
    }

    private void beginPendingScopeReleaseLocked(SandboxIsolationKey key) {
        pendingScopeReleases.merge(key, 1, Integer::sum);
    }

    private void endPendingScopeRelease(SandboxIsolationKey key) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            Integer remaining = pendingScopeReleases.get(key);
            if (remaining == null) {
                return;
            }
            if (remaining <= 1) {
                pendingScopeReleases.remove(key);
            } else {
                pendingScopeReleases.put(key, remaining - 1);
            }
            lock.notifyAll();
        }
    }

    private int pendingCountLocked(SandboxIsolationKey key) {
        Integer count = pendingScopeReleases.get(key);
        return count == null ? 0 : count;
    }

    private static Optional<SandboxIsolationKey> resolveScopeKey(
            SandboxManager manager, SandboxContext sandboxContext, RuntimeContext runtimeContext) {
        if (manager == null) {
            return Optional.empty();
        }
        return SandboxIsolationKey.resolve(
                sandboxContext != null ? sandboxContext.getIsolationScope() : null,
                runtimeContext,
                manager.getAgentId());
    }

    private static List<DeferredRelease> takeDeferred(State state) {
        List<DeferredRelease> copy = new ArrayList<>(state.deferred);
        state.deferred.clear();
        return copy;
    }

    private static void scheduleDeferredRelease(DeferredRelease work) {
        try {
            RELEASE_EXECUTOR.execute(() -> performRelease(work));
        } catch (RuntimeException e) {
            log.warn(
                    "[sandbox-mirror-release] Failed to schedule deferred release; running"
                            + " inline: {}",
                    e.getMessage(),
                    e);
            performRelease(work);
        }
    }

    /**
     * stop/shutdown → persist (when context present) → lease close. Persist after stop so
     * stop-time state mutations reach the store (issue #2555).
     */
    private static void performRelease(DeferredRelease work) {
        try {
            try {
                work.manager.release(work.result);
            } catch (Exception e) {
                log.warn("[sandbox-mirror-release] Sandbox release failed: {}", e.getMessage(), e);
            }
            if (work.sandboxContext != null || work.runtimeContext != null) {
                try {
                    work.manager.persistState(
                            work.result, work.sandboxContext, work.runtimeContext);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-mirror-release] Sandbox persist after release failed: {}",
                            e.getMessage(),
                            e);
                }
            }
            try {
                work.result.getLease().close();
            } catch (Exception e) {
                log.warn(
                        "[sandbox-mirror-release] Sandbox lease close failed: {}",
                        e.getMessage(),
                        e);
            }
        } finally {
            // Always clear the scope gate, including sync releaseNow paths where begin was never
            // called (end is then a no-op) and deferred paths that registered the key.
            INSTANCE.endPendingScopeRelease(work.scopeKey);
        }
    }

    private static final class DeferredRelease {
        private final SandboxManager manager;
        private final SandboxAcquireResult result;
        private final SandboxContext sandboxContext;
        private final RuntimeContext runtimeContext;
        private final SandboxIsolationKey scopeKey;

        private DeferredRelease(
                SandboxManager manager,
                SandboxAcquireResult result,
                SandboxContext sandboxContext,
                RuntimeContext runtimeContext,
                SandboxIsolationKey scopeKey) {
            this.manager = manager;
            this.result = result;
            this.sandboxContext = sandboxContext;
            this.runtimeContext = runtimeContext;
            this.scopeKey = scopeKey;
        }
    }

    private static final class State {
        private int pendingMirrors;
        private boolean released;
        private final List<DeferredRelease> deferred = new ArrayList<>();
    }
}
