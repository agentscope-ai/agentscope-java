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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/**
 * Keeps a self-managed {@link Sandbox} alive while fire-and-forget writes that target it are still
 * in flight.
 *
 * <p>Several harness writes finish after the agent call that produced them: the session mirror
 * uploads session files on a background executor, and memory flush / maintenance write {@code
 * memory/*.md} on {@code boundedElastic}. The call's sandbox, however, is released as soon as the
 * call completes, and for a self-managed sandbox release means {@link Sandbox#stop()} (snapshot the
 * workspace) followed by {@link Sandbox#shutdown()} (destroy the container). A write that lands
 * after that fails ("container is not running", "No active sandbox") and would miss the snapshot
 * the next call resumes from anyway (issue #3415).
 *
 * <p>Background writers take a {@link Hold} on the sandbox while the call is still running. {@link
 * #releaseWhenIdle} runs the call's teardown immediately when no hold is outstanding — the
 * behaviour before holds existed — and otherwise defers it until the last hold is closed. A
 * deferred teardown is forced after {@link #maxDeferMillis()}, so a hung writer cannot keep a
 * container alive indefinitely; writes still running at that point fail as they did before.
 *
 * <p>State is process-wide and keyed by sandbox identity, like the session mirror executor and
 * {@link io.agentscope.harness.agent.memory.MemoryBackgroundTasks}. An entry only exists while a
 * hold is outstanding or a teardown is pending, so the registry does not grow with the number of
 * calls.
 */
public final class SandboxBackgroundWrites {

    /**
     * System property: the longest a sandbox release may be deferred for background writes, in
     * milliseconds. {@code 0} disables deferral and releases immediately, as before.
     */
    public static final String MAX_DEFER_MILLIS_PROPERTY =
            "agentscope.sandbox.release.maxDeferMillis";

    /** Default for {@link #MAX_DEFER_MILLIS_PROPERTY}. */
    public static final long DEFAULT_MAX_DEFER_MILLIS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(SandboxBackgroundWrites.class);

    private static final Object LOCK = new Object();

    /** Guarded by {@link #LOCK}. Keyed by identity: a resumed sandbox is a distinct instance. */
    private static final Map<Sandbox, Entry> ENTRIES = new IdentityHashMap<>();

    private SandboxBackgroundWrites() {}

    /**
     * Holds {@code sandbox} open for one background write. Take the hold while the call that owns
     * the sandbox is still running and close it when the write finishes, on every path.
     *
     * @param sandbox the sandbox the write targets
     * @return the hold, or {@code null} when the sandbox is already being released and the write
     *     should be skipped
     */
    public static Hold tryHold(Sandbox sandbox) {
        Objects.requireNonNull(sandbox, "sandbox");
        synchronized (LOCK) {
            Entry entry = ENTRIES.computeIfAbsent(sandbox, s -> new Entry());
            if (entry.releasing) {
                return null;
            }
            entry.holds++;
            return new Hold(sandbox, entry);
        }
    }

    /**
     * Pins the sandbox bound to a call's {@link RuntimeContext} for a background task dispatched
     * from inside that call.
     *
     * <p>The call's release clears its {@link SandboxAcquireResult} from {@code rc}, so the
     * returned pin carries a copy of the context that keeps the binding; the task must use {@link
     * Pin#context()} for its sandbox writes. Without a bound sandbox the pin is a no-op whose
     * context is {@code rc} itself.
     *
     * @param rc the per-call context, read while the call is still running
     * @return the pin; close it when the task finishes
     */
    public static Pin pinCallSandbox(RuntimeContext rc) {
        SandboxAcquireResult bound = rc != null ? rc.get(SandboxAcquireResult.class) : null;
        Sandbox sandbox = bound != null ? bound.getSandbox() : null;
        if (sandbox == null) {
            return new Pin(rc, null);
        }
        Hold hold = tryHold(sandbox);
        if (hold == null) {
            return new Pin(rc, null);
        }
        return new Pin(RuntimeContext.builder(rc).build(), hold);
    }

    /**
     * Runs {@code teardown} for {@code sandbox} now if no hold is outstanding, on the calling
     * thread; otherwise runs it on {@code boundedElastic} once the last hold closes, or when
     * {@link #maxDeferMillis()} elapses, whichever comes first. The teardown runs exactly once.
     *
     * @param sandbox the sandbox being released
     * @param teardown stops the sandbox and persists its state; must not throw for expected
     *     failures
     * @return completes after the teardown has run (already complete when it ran inline)
     */
    public static CompletableFuture<Void> releaseWhenIdle(Sandbox sandbox, Runnable teardown) {
        Objects.requireNonNull(sandbox, "sandbox");
        Objects.requireNonNull(teardown, "teardown");
        long maxDefer = maxDeferMillis();
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(sandbox);
            if (entry != null && entry.teardown != null) {
                // Each acquire yields its own sandbox instance, so a second release is a caller
                // bug; keep the old behaviour of releasing again right away.
                entry = null;
            } else {
                if (entry == null) {
                    entry = new Entry();
                    ENTRIES.put(sandbox, entry);
                }
                entry.teardown = teardown;
                if (entry.holds > 0 && maxDefer > 0) {
                    Entry deferred = entry;
                    entry.deadline =
                            Schedulers.boundedElastic()
                                    .schedule(
                                            () -> forceRelease(sandbox, deferred, maxDefer),
                                            maxDefer,
                                            TimeUnit.MILLISECONDS);
                    log.debug(
                            "[sandbox] Deferring release of sandbox {} until {} background"
                                    + " write(s) finish",
                            sessionIdOf(sandbox),
                            entry.holds);
                    return entry.done;
                }
                entry.releasing = true;
            }
        }
        if (entry == null) {
            teardown.run();
            return CompletableFuture.completedFuture(null);
        }
        runTeardown(sandbox, entry);
        return entry.done;
    }

    /**
     * Blocks until every deferred release pending when this method is called has run, or the
     * timeout elapses. Intended for graceful shutdown ({@code HarnessAgent.close()}), after the
     * background writers themselves have been drained.
     *
     * @param timeout maximum time to wait
     * @param unit time unit of {@code timeout}
     * @return {@code true} if no deferred release is still pending
     */
    public static boolean awaitPendingReleases(long timeout, TimeUnit unit) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        synchronized (LOCK) {
            for (Entry entry : ENTRIES.values()) {
                if (entry.teardown != null && !entry.done.isDone()) {
                    pending.add(entry.done);
                }
            }
        }
        if (pending.isEmpty()) {
            return true;
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
                    .get(timeout, unit);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            log.debug("[sandbox] {} deferred sandbox release(s) still pending", pending.size());
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Returns the longest a release may be deferred, from {@link #MAX_DEFER_MILLIS_PROPERTY}.
     *
     * @return milliseconds; {@code 0} means releases are never deferred
     */
    public static long maxDeferMillis() {
        Long configured = Long.getLong(MAX_DEFER_MILLIS_PROPERTY);
        if (configured == null) {
            return DEFAULT_MAX_DEFER_MILLIS;
        }
        return Math.max(0L, configured);
    }

    private static void forceRelease(Sandbox sandbox, Entry entry, long maxDefer) {
        int inFlight;
        synchronized (LOCK) {
            if (entry.releasing) {
                return;
            }
            entry.releasing = true;
            inFlight = entry.holds;
        }
        log.warn(
                "[sandbox] Releasing sandbox {} with {} background write(s) still in flight after"
                        + " {} ms; those writes will not reach the sandbox",
                sessionIdOf(sandbox),
                inFlight,
                maxDefer);
        runTeardown(sandbox, entry);
    }

    private static void onHoldClosed(Sandbox sandbox, Entry entry) {
        boolean dispatch = false;
        synchronized (LOCK) {
            if (entry.holds > 0) {
                entry.holds--;
            }
            if (entry.holds > 0) {
                return;
            }
            if (entry.teardown != null && !entry.releasing) {
                entry.releasing = true;
                dispatch = true;
                if (entry.deadline != null) {
                    entry.deadline.dispose();
                }
            } else {
                removeIfIdle(sandbox, entry);
            }
        }
        if (dispatch) {
            // Never run the teardown on the writer's thread: the session mirror executor is a
            // single process-wide thread, and a slow stop() there would stall every session.
            Schedulers.boundedElastic().schedule(() -> runTeardown(sandbox, entry));
        }
    }

    private static void runTeardown(Sandbox sandbox, Entry entry) {
        try {
            entry.teardown.run();
        } catch (RuntimeException e) {
            log.warn("[sandbox] Sandbox release failed: {}", e.getMessage(), e);
        } finally {
            synchronized (LOCK) {
                entry.finished = true;
                removeIfIdle(sandbox, entry);
            }
            entry.done.complete(null);
        }
    }

    /** Must hold {@link #LOCK}. */
    private static void removeIfIdle(Sandbox sandbox, Entry entry) {
        if (entry.holds == 0 && (entry.teardown == null || entry.finished)) {
            ENTRIES.remove(sandbox, entry);
        }
    }

    private static String sessionIdOf(Sandbox sandbox) {
        SandboxState state = sandbox.getState();
        return state != null ? state.getSessionId() : "?";
    }

    /** Per-sandbox bookkeeping. Fields are guarded by {@link #LOCK}. */
    private static final class Entry {
        int holds;
        Runnable teardown;
        boolean releasing;
        boolean finished;
        Disposable deadline;
        final CompletableFuture<Void> done = new CompletableFuture<>();
    }

    /** One background write's claim on a sandbox. Closing is idempotent. */
    public static final class Hold implements AutoCloseable {

        private final Sandbox sandbox;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Hold(Sandbox sandbox, Entry entry) {
            this.sandbox = sandbox;
            this.entry = entry;
        }

        /**
         * Returns whether the sandbox release has started despite this hold, i.e. the deferral
         * budget ran out. A write that has not started yet should be skipped.
         *
         * @return {@code true} once the sandbox is being, or has been, released
         */
        public boolean isReleased() {
            synchronized (LOCK) {
                return entry.releasing;
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                onHoldClosed(sandbox, entry);
            }
        }
    }

    /**
     * A background task's pin on its call's sandbox; see {@link #pinCallSandbox(RuntimeContext)}.
     * Closing is idempotent.
     */
    public static final class Pin implements AutoCloseable {

        private final RuntimeContext context;
        private final Hold hold;

        private Pin(RuntimeContext context, Hold hold) {
            this.context = context;
            this.hold = hold;
        }

        /**
         * Returns the context the background task must use: it keeps the call's sandbox binding
         * after the call has been released.
         *
         * @return the task's runtime context
         */
        public RuntimeContext context() {
            return context;
        }

        @Override
        public void close() {
            if (hold != null) {
                hold.close();
            }
        }
    }
}
