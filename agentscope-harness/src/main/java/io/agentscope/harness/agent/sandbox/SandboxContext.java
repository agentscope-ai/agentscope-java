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
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * Immutable configuration for sandbox behavior.
 *
 * <p>Built with the agent and attached to {@link io.agentscope.core.agent.RuntimeContext} for
 * each call.
 */
public final class SandboxContext {

    private final SandboxClient<?> client;
    private final SandboxClientOptions clientOptions;
    private final WorkspaceSpec workspaceSpec;
    private final SandboxSnapshotSpec snapshotSpec;
    private final Sandbox externalSandbox;
    private final SandboxState externalSandboxState;
    private final IsolationScope isolationScope;
    private final SandboxReleasePolicy releasePolicy;

    private SandboxContext(Builder builder) {
        this.client = builder.client;
        this.clientOptions = builder.clientOptions;
        this.workspaceSpec = builder.workspaceSpec;
        this.snapshotSpec = builder.snapshotSpec;
        this.externalSandbox = builder.externalSandbox;
        this.externalSandboxState = builder.externalSandboxState;
        this.isolationScope = builder.isolationScope;
        this.releasePolicy = builder.releasePolicy;
    }

    public SandboxClient<?> getClient() {
        return client;
    }

    public SandboxClientOptions getClientOptions() {
        return clientOptions;
    }

    public WorkspaceSpec getWorkspaceSpec() {
        return workspaceSpec;
    }

    public SandboxSnapshotSpec getSnapshotSpec() {
        return snapshotSpec;
    }

    public Sandbox getExternalSandbox() {
        return externalSandbox;
    }

    public SandboxState getExternalSandboxState() {
        return externalSandboxState;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /** Returns the release policy for SDK-managed sandboxes. */
    public SandboxReleasePolicy getReleasePolicy() {
        return releasePolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private SandboxClient<?> client;
        private SandboxClientOptions clientOptions;
        private WorkspaceSpec workspaceSpec;
        private SandboxSnapshotSpec snapshotSpec;
        private Sandbox externalSandbox;
        private SandboxState externalSandboxState;
        private IsolationScope isolationScope;
        private SandboxReleasePolicy releasePolicy = SandboxReleasePolicy.DELETE;

        private Builder() {}

        public Builder client(SandboxClient<?> client) {
            this.client = client;
            return this;
        }

        public Builder clientOptions(SandboxClientOptions clientOptions) {
            this.clientOptions = clientOptions;
            return this;
        }

        public Builder workspaceSpec(WorkspaceSpec workspaceSpec) {
            this.workspaceSpec = workspaceSpec;
            return this;
        }

        public Builder snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
            this.snapshotSpec = snapshotSpec;
            return this;
        }

        public Builder externalSandbox(Sandbox externalSandbox) {
            this.externalSandbox = externalSandbox;
            return this;
        }

        /**
         * Supplies state to resume directly, bypassing the harness execution guard. The caller
         * must serialize access. With RETAIN and no resolvable isolation key, the harness does not
         * save state: the caller must durably persist the updated sandbox state after each call,
         * including its current instance id, and retain it for later resume or explicit deletion.
         * Losing that record can leave a billed instance running until provider expiry.
         */
        public Builder externalSandboxState(SandboxState externalSandboxState) {
            this.externalSandboxState = externalSandboxState;
            return this;
        }

        public Builder isolationScope(IsolationScope isolationScope) {
            this.isolationScope = isolationScope;
            return this;
        }

        /** Sets the release policy; defaults to DELETE and must not be null. */
        public Builder releasePolicy(SandboxReleasePolicy policy) {
            this.releasePolicy = Objects.requireNonNull(policy, "releasePolicy must not be null");
            return this;
        }

        public SandboxContext build() {
            return new SandboxContext(this);
        }
    }
}
