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

/**
 * A handle that represents a held execution right on a sandbox isolation slot.
 *
 * <p>Returned by {@link SandboxExecutionGuard#tryEnter}. The harness closes the lease
 * automatically after {@link SandboxManager#release} completes (whether the call succeeded or
 * failed), so implementations do not need to worry about cleanup ordering.
 *
 * <p>Implementations must be idempotent: calling {@link #close()} more than once must be safe.
 */
public interface SandboxLease extends AutoCloseable {

    /**
     * Releases the execution right held by this lease.
     *
     * <p>Must not throw. Any release-side error should be logged internally by the implementation.
     */
    @Override
    void close();

    /**
     * Returns a no-op lease whose {@link #close()} is a no-op. Used by the default
     * {@link SandboxExecutionGuard#noop()} implementation.
     */
    static SandboxLease noop() {
        return NoopSandboxLease.INSTANCE;
    }

    /** Singleton no-op implementation. */
    final class NoopSandboxLease implements SandboxLease {

        static final NoopSandboxLease INSTANCE = new NoopSandboxLease();

        private NoopSandboxLease() {}

        @Override
        public void close() {}
    }
}
