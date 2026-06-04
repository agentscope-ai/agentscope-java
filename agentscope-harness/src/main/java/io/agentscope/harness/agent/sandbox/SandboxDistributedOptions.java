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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.OssSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RedisSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * High-level distributed sandbox configuration used by
 * {@link io.agentscope.harness.agent.HarnessAgent.Builder#sandboxDistributed}.
 *
 * <p>Bundles the pieces required for distributed sandbox restore/sharing that are not already on
 * {@link SandboxFilesystemSpec}:
 *
 * <ul>
 *   <li>distributed {@link AgentStateStore} (for state-store slots)
 *   <li>optional {@link SandboxSnapshotSpec} override (workspace archive persistence)
 *   <li>{@code requireDistributed} — fail-fast when distributed prerequisites are not met
 * </ul>
 *
 * <p>Configure {@link io.agentscope.harness.agent.IsolationScope} on {@code SandboxFilesystemSpec}
 * only; it is not duplicated here.
 */
public final class SandboxDistributedOptions {

    private final AgentStateStore stateStore;
    private final SandboxSnapshotSpec snapshotSpec;
    private final boolean requireDistributed;

    private SandboxDistributedOptions(Builder builder) {
        this.stateStore = builder.stateStore;
        this.snapshotSpec = builder.snapshotSpec;
        this.requireDistributed = builder.requireDistributed;
    }

    /**
     * Creates a builder with safe distributed defaults.
     *
     * <p>Defaults:
     *
     * <ul>
     *   <li>{@code requireDistributed = true}
     * </ul>
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates options with OSS snapshot backend and distributed-safe defaults.
     */
    public static SandboxDistributedOptions oss(
            AgentStateStore stateStore, OssSnapshotSpec snapshotSpec) {
        return builder().stateStore(stateStore).snapshotSpec(snapshotSpec).build();
    }

    /**
     * Creates options with Redis snapshot backend and distributed-safe defaults.
     */
    public static SandboxDistributedOptions redis(
            AgentStateStore stateStore, RedisSnapshotSpec snapshotSpec) {
        return builder().stateStore(stateStore).snapshotSpec(snapshotSpec).build();
    }

    /**
     * Returns the distributed state store backend used by {@link SessionSandboxStateStore}.
     */
    public AgentStateStore getStateStore() {
        return stateStore;
    }

    /**
     * Returns the snapshot spec used for workspace archive persistence.
     */
    public SandboxSnapshotSpec getSnapshotSpec() {
        return snapshotSpec;
    }

    /**
     * Whether builder should fail-fast when distributed prerequisites are not met.
     */
    public boolean isRequireDistributed() {
        return requireDistributed;
    }

    public static final class Builder {

        private AgentStateStore stateStore;
        private SandboxSnapshotSpec snapshotSpec;
        private boolean requireDistributed = true;

        private Builder() {}

        /**
         * Sets distributed state store backend (for state slot persistence).
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
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
         * Enables/disables fail-fast checks for distributed prerequisites.
         *
         * <p>When {@code true} (default), builder throws if effective session is a local
         * in-process implementation ({@code JsonFileAgentStateStore} / {@code InMemoryAgentStateStore}) or snapshot
         * spec is absent/no-op.
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
