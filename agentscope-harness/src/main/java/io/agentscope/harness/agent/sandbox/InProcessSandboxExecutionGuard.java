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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM-local {@link SandboxExecutionGuard} that serialises concurrent executions sharing the same
 * {@link SandboxIsolationKey} within a single process.
 *
 * <p>This is the built-in default guard. It closes the same-slot concurrency window that the
 * per-call binding fix (issue #2490) leaves open: {@code AgentBase.serializeOnKey} only serialises
 * the <em>delegate execution</em> of same-session calls, not the sandbox
 * <em>acquire → resume → persist → release</em> window run by
 * {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}. Without a guard, two
 * concurrent calls resolving to the same slot each resume a separate sandbox from the same
 * persisted state, run in parallel, and race on the final {@code persistState} (last write wins) —
 * losing one call's workspace changes and briefly leaking a second container (issue #2800).
 *
 * <p>By holding a per-key permit for the whole call window, this guard makes same-slot calls run
 * strictly one at a time, so each call resumes the state the previous one persisted. Calls with
 * <em>different</em> keys never block each other.
 *
 * <p>Scope: this guard only coordinates within one JVM. Deployments that run multiple instances
 * against a shared state store must supply a distributed guard (e.g. Redis {@code SET NX}) via
 * {@link io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec#executionGuard} or a
 * {@link io.agentscope.harness.agent.DistributedStore}.
 *
 * <p>Thread-safety: permits are held via {@link Semaphore}, which — unlike {@link
 * java.util.concurrent.locks.ReentrantLock} — is not thread-ownership bound, so a lease acquired on
 * one thread may be closed on another. This matters because the harness acquires in a reactive
 * resource supplier and releases in the corresponding cleanup, which may run on a different thread.
 *
 * <p>Wait timeout: by default {@link #tryEnter} waits indefinitely for a busy slot, since a healthy
 * holder legitimately keeps it for a full (potentially many-minute) agent call and a shorter wait
 * would spuriously fail calls queued behind it. Supply a positive {@code waitTimeout} only as a
 * backstop against a wedged holder; on expiry {@link #tryEnter} throws
 * {@link SandboxExecutionTimeoutException}. Set it well above the maximum realistic call duration.
 */
public final class InProcessSandboxExecutionGuard implements SandboxExecutionGuard {

    private final ConcurrentHashMap<SandboxIsolationKey, Slot> slots = new ConcurrentHashMap<>();

    /** Maximum time to wait for a busy slot, or {@code null} to wait indefinitely. */
    private final Duration waitTimeout;

    /** Creates a guard that waits indefinitely for a busy slot. */
    public InProcessSandboxExecutionGuard() {
        this(null);
    }

    /**
     * Creates a guard that gives up after {@code waitTimeout} when a slot stays busy.
     *
     * @param waitTimeout the maximum time to wait for a busy slot, or {@code null} to wait
     *     indefinitely; must be strictly positive when non-null
     */
    public InProcessSandboxExecutionGuard(Duration waitTimeout) {
        if (waitTimeout != null && (waitTimeout.isNegative() || waitTimeout.isZero())) {
            throw new IllegalArgumentException(
                    "waitTimeout must be > 0 or null (infinite), got " + waitTimeout);
        }
        this.waitTimeout = waitTimeout;
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        Objects.requireNonNull(key, "key must not be null");
        // Register interest atomically so the slot is not recycled while we wait for the permit.
        Slot slot =
                slots.compute(
                        key,
                        (k, existing) -> {
                            Slot s = existing != null ? existing : new Slot();
                            s.refs++;
                            return s;
                        });
        try {
            if (waitTimeout == null) {
                slot.semaphore.acquire();
            } else if (!slot.semaphore.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                // Timed out — drop the reference we just registered before surfacing the failure.
                decrementRef(key);
                throw new SandboxExecutionTimeoutException(key, waitTimeout);
            }
        } catch (InterruptedException e) {
            // We never obtained the permit — drop the reference we just registered.
            decrementRef(key);
            throw e;
        }
        return new SlotLease(key, slot);
    }

    private void decrementRef(SandboxIsolationKey key) {
        // Recycle the slot only when no call holds or waits on it. A caller between compute() and
        // semaphore.acquire() still holds a ref, so an in-use slot is never removed and every
        // outstanding lease keeps referring to the same object it was created with.
        slots.compute(
                key,
                (k, existing) -> {
                    if (existing == null) {
                        return null;
                    }
                    existing.refs--;
                    return existing.refs <= 0 ? null : existing;
                });
    }

    /** Number of slots currently tracked; exposed for tests to assert no leak after release. */
    int trackedSlots() {
        return slots.size();
    }

    /** Per-key mutual-exclusion permit plus a reference count guarding slot recycling. */
    private static final class Slot {
        // Fair, so a steady stream of same-key calls cannot starve a waiter indefinitely.
        final Semaphore semaphore = new Semaphore(1, true);
        int refs;
    }

    /** Idempotent lease: releasing the permit and dropping the ref happen at most once. */
    private final class SlotLease implements SandboxLease {

        private final SandboxIsolationKey key;
        private final Slot slot;
        private final AtomicBoolean released = new AtomicBoolean(false);

        SlotLease(SandboxIsolationKey key, Slot slot) {
            this.key = key;
            this.slot = slot;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                slot.semaphore.release();
                decrementRef(key);
            }
        }
    }
}
