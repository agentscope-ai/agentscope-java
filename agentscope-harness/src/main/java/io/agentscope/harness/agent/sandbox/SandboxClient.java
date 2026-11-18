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

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * Factory for creating and resuming {@link Sandbox} instances.
 *
 * @param <O> the type of client options for this implementation
 */
public interface SandboxClient<O extends SandboxClientOptions> {

    /**
     * Creates a new sandbox with the given workspace spec and snapshot spec.
     *
     * <p>Returned in a pre-start state; call {@link Sandbox#start()} before use.
     */
    Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, O options);

    /**
     * Resumes a sandbox from previously serialized {@link SandboxState}.
     */
    Sandbox resume(SandboxState state);

    void delete(Sandbox sandbox);

    String serializeState(SandboxState state);

    SandboxState deserializeState(String json);

    /**
     * Deserializes sandbox state and rebinds a {@link
     * io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient} when the given snapshot
     * spec is a {@link RemoteSnapshotSpec}.
     *
     * <p>{@link RemoteSandboxSnapshot} only persists its {@code id} across JSON serialization; the
     * client must be re-injected from the live {@link RemoteSnapshotSpec} on resume.
     */
    default SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) {
        SandboxState state = deserializeState(json);
        rebindRemoteSnapshot(state, snapshotSpec);
        return state;
    }

    /**
     * Rebinds {@link RemoteSandboxSnapshot} with the client from {@link RemoteSnapshotSpec}.
     *
     * <p>No-op when the spec is not remote, the snapshot is missing/non-remote, or the snapshot
     * id is null.
     */
    static void rebindRemoteSnapshot(SandboxState state, SandboxSnapshotSpec snapshotSpec) {
        if (state == null || !(snapshotSpec instanceof RemoteSnapshotSpec remoteSnapshotSpec)) {
            return;
        }
        SandboxSnapshot snapshot = state.getSnapshot();
        if (!(snapshot instanceof RemoteSandboxSnapshot) || snapshot.getId() == null) {
            return;
        }
        state.setSnapshot(
                new RemoteSandboxSnapshot(remoteSnapshotSpec.getClient(), snapshot.getId()));
    }
}
