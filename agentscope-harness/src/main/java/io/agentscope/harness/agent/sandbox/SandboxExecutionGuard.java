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

import io.agentscope.harness.agent.IsolationScope;
import java.time.Duration;

/**
 * Pluggable concurrency guard for sandbox execution slots.
 *
 * <p>A guard controls how many concurrent executions are allowed for a given
 * {@link SandboxIsolationKey}. The built-in default is {@link #inProcess()}, which serialises
 * same-slot concurrent calls within one JVM; {@link #noop()} disables serialisation entirely.
 *
 * <p>This extension point matters for every scope where two concurrent calls can resolve to the
 * same persistent state slot — {@link IsolationScope#SESSION} (same-session concurrent calls),
 * {@link IsolationScope#USER}, {@link IsolationScope#AGENT} and {@link IsolationScope#GLOBAL}. The
 * per-call binding fix (issue #2490) stops such calls from corrupting each other's <em>live</em>
 * binding, but not from racing on the persisted state (last write wins, issue #2800); a guard
 * closes that window. Supply a distributed implementation when the same slot can be contended
 * across JVMs; the {@link #inProcess()} default only coordinates within one process.
 *
 * <p>Implementations may use any backend — JVM semaphores, Redis {@code SET NX} leases,
 * ZooKeeper, database advisory locks, etc. — and must be thread-safe.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * SandboxExecutionGuard guard = key -> {
 *     redisClient.set(key.toString(), token, SetArgs.Builder.nx().px(30_000));
 *     return () -> redisClient.eval(LUA_RELEASE_SCRIPT, key.toString(), token);
 * };
 *
 * HarnessAgent.builder()
 *     .filesystem(new DockerFilesystemSpec()
 *         .isolationScope(IsolationScope.AGENT)
 *         .executionGuard(guard))
 *     ...
 *     .build();
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The harness calls {@link #tryEnter} before sandbox acquire/resume and closes the returned
 * {@link SandboxLease} after {@link SandboxManager#release} completes, so the guard covers the
 * full call window: {@code acquire → start → (call) → stop → release → lease.close()}.
 */
@FunctionalInterface
public interface SandboxExecutionGuard {

    /**
     * Acquires the execution right for the given isolation key, blocking until the slot becomes
     * available or the calling thread is interrupted.
     *
     * <p>The returned {@link SandboxLease} must be closed to release the slot. The harness handles
     * this automatically; callers do not need to close the lease explicitly.
     *
     * @param key the isolation key that identifies the sandbox slot to protect
     * @return a lease that releases the execution right when closed
     * @throws InterruptedException if interrupted while waiting for the slot
     */
    SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException;

    /**
     * Returns the no-op guard: execution is always allowed immediately and the returned
     * {@link SandboxLease} is a no-op. Same-slot concurrent calls are <em>not</em> serialised, so
     * they race on the persisted state (last write wins). Use only when an external mechanism
     * already guarantees no two calls share a slot concurrently.
     */
    static SandboxExecutionGuard noop() {
        return NoopSandboxExecutionGuard.INSTANCE;
    }

    /**
     * Returns a fresh JVM-local guard that serialises same-slot concurrent calls within one
     * process. This is the built-in default the harness applies when no guard is configured; it
     * closes the same-session acquire/persist race (issue #2800) for single-instance deployments.
     * Multi-instance deployments should supply a distributed guard instead.
     *
     * <p>Each call returns an independent guard holding its own per-key state, so callers that need
     * isolated coordination (e.g. one guard per agent) get it without sharing across agents.
     *
     * <p>This variant waits indefinitely for a busy slot. Use {@link #inProcess(Duration)} to add a
     * backstop timeout against a wedged holder.
     */
    static SandboxExecutionGuard inProcess() {
        return new InProcessSandboxExecutionGuard();
    }

    /**
     * Like {@link #inProcess()}, but gives up after {@code waitTimeout} when a slot stays busy,
     * throwing {@link SandboxExecutionTimeoutException}. This is a backstop against a wedged holder,
     * not a contention timeout: a healthy holder legitimately keeps the slot for a full agent call,
     * so set {@code waitTimeout} well above the maximum realistic call duration to avoid failing
     * calls that are merely queued behind a long-running peer.
     *
     * @param waitTimeout the maximum time to wait for a busy slot; must be strictly positive
     */
    static SandboxExecutionGuard inProcess(Duration waitTimeout) {
        return new InProcessSandboxExecutionGuard(waitTimeout);
    }

    /** Singleton no-op implementation. */
    final class NoopSandboxExecutionGuard implements SandboxExecutionGuard {

        static final NoopSandboxExecutionGuard INSTANCE = new NoopSandboxExecutionGuard();

        private NoopSandboxExecutionGuard() {}

        @Override
        public SandboxLease tryEnter(SandboxIsolationKey key) {
            return SandboxLease.noop();
        }
    }
}
