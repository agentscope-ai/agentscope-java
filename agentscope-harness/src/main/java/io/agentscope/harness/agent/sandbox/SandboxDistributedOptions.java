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

import io.agentscope.core.session.Session;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.snapshot.OssSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RedisSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * High-level distributed sandbox configuration used by
 * {@link io.agentscope.harness.agent.HarnessAgent.Builder#sandboxDistributed}.
 *
 * <p>This options object intentionally bundles the three pieces required for
 * distributed sandbox restore/sharing:
 * <ul>
 *   <li>distributed {@link Session} (for state-store slots)</li>
 *   <li>{@link SandboxSnapshotSpec} (for workspace archive persistence)</li>
 *   <li>{@link IsolationScope} (for sharing granularity)</li>
 * </ul>
 */
public final class SandboxDistributedOptions {

    private final Session session;
    private final SandboxSnapshotSpec snapshotSpec;
    private final IsolationScope isolationScope;
    private final boolean requireDistributed;

    private SandboxDistributedOptions(Builder builder) {
        this.session = builder.session;
        this.snapshotSpec = builder.snapshotSpec;
        this.isolationScope = builder.isolationScope;
        this.requireDistributed = builder.requireDistributed;
    }

    /**
     * Creates a builder with safe distributed defaults.
     *
     * <p>Defaults:
     * <ul>
     *   <li>{@code isolationScope = USER}</li>
     *   <li>{@code requireDistributed = true}</li>
     * </ul>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates options with OSS snapshot backend and distributed-safe defaults.
     */
    public static SandboxDistributedOptions oss(Session session, OssSnapshotSpec snapshotSpec) {
        return builder().session(session).snapshotSpec(snapshotSpec).build();
    }

    /**
     * Creates options with Redis snapshot backend and distributed-safe defaults.
     */
    public static SandboxDistributedOptions redis(Session session, RedisSnapshotSpec snapshotSpec) {
        return builder().session(session).snapshotSpec(snapshotSpec).build();
    }

    /**
     * Returns the distributed session backend used by {@link SessionSandboxStateStore}.
     */
    public Session getSession() {
        return session;
    }

    /**
     * Returns the snapshot spec used for workspace archive persistence.
     */
    public SandboxSnapshotSpec getSnapshotSpec() {
        return snapshotSpec;
    }

    /**
     * Returns the isolation scope for sandbox state sharing.
     */
    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * Whether builder should fail-fast when distributed prerequisites are not met.
     */
    public boolean isRequireDistributed() {
        return requireDistributed;
    }

    public static final class Builder {

        private Session session;
        private SandboxSnapshotSpec snapshotSpec;
        private IsolationScope isolationScope = IsolationScope.USER;
        private boolean requireDistributed = true;

        private Builder() {}

        /**
         * Sets distributed session backend (for state slot persistence).
         */
        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        /**
         * Sets snapshot strategy used for workspace persistence.
         */
        public Builder snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
            this.snapshotSpec = snapshotSpec;
            return this;
        }

        /**
         * Sets sandbox isolation scope. Default is {@link IsolationScope#USER}.
         */
        public Builder isolationScope(IsolationScope isolationScope) {
            this.isolationScope = Objects.requireNonNull(isolationScope, "isolationScope");
            return this;
        }

        /**
         * Enables/disables fail-fast checks for distributed prerequisites.
         *
         * <p>When {@code true} (default), builder throws if effective session remains local
         * ({@code WorkspaceSession}) or snapshot spec is absent/no-op.
         */
        public Builder requireDistributed(boolean requireDistributed) {
            this.requireDistributed = requireDistributed;
            return this;
        }

        public SandboxDistributedOptions build() {
            return new SandboxDistributedOptions(this);
        }
    }
}
