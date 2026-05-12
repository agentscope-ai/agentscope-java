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

/**
 * Pluggable concurrency guard for sandbox execution slots.
 *
 * <p>A guard controls how many concurrent executions are allowed for a given
 * {@link SandboxIsolationKey}. The default {@link #noop()} imposes no restriction, preserving
 * existing behaviour.
 *
 * <p>This extension point is primarily useful for {@link IsolationScope#AGENT} and
 * {@link IsolationScope#GLOBAL} scopes, where multiple concurrent callers could otherwise
 * race on the same persistent state slot (last write wins). Providing a guard serialises
 * such callers without requiring changes to the surrounding infrastructure.
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
     * Returns the default no-op guard: execution is always allowed immediately and the returned
     * {@link SandboxLease} is a no-op. This is the built-in default — no configuration required.
     */
    static SandboxExecutionGuard noop() {
        return NoopSandboxExecutionGuard.INSTANCE;
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
