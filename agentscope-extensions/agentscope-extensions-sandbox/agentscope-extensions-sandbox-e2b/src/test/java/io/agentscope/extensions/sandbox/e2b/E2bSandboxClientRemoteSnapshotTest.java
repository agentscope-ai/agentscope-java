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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Regression test for {@link E2bSandboxClient#deserializeState(String, RemoteSnapshotSpec)}: the
 * runtime client must be rebound into a deserialized {@link RemoteSandboxSnapshot}, matching
 * {@code DockerSandboxClient}.
 *
 * <p>On upstream main the two-argument default method only calls the one-argument overload, so the
 * restored snapshot keeps {@code client == null} and fails with {@code RemoteSnapshotClient is not
 * bound to snapshot id: ...}.
 */
class E2bSandboxClientRemoteSnapshotTest {

    @Test
    void deserializeStateRebindsRemoteSnapshotClient() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxState state = new E2bSandboxState();
        state.setSessionId("session-1");
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/home/user");
        state.setWorkspaceSpec(workspaceSpec);
        state.setWorkspaceRoot("/home/user");
        state.setSnapshot(new RemoteSandboxSnapshot(null, "snap-1"));

        String json = client.serializeState(state);
        InMemoryRemoteSnapshotClient remote = new InMemoryRemoteSnapshotClient();
        SandboxState restored = client.deserializeState(json, new RemoteSnapshotSpec(remote));

        SandboxSnapshot snapshot = restored.getSnapshot();
        assertTrue(snapshot instanceof RemoteSandboxSnapshot);
        assertTrue(
                assertDoesNotThrow(
                        snapshot::isRestorable,
                        "deserialized remote snapshot must have its client rebound"),
                "the rebound client reports snapshot snap-1 as existing");
        assertTrue(remote.exists("snap-1"));
    }

    /** Minimal in-memory {@link RemoteSnapshotClient} for rebinding assertions. */
    private static final class InMemoryRemoteSnapshotClient implements RemoteSnapshotClient {

        @Override
        public void upload(String snapshotId, InputStream data) {}

        @Override
        public InputStream download(String snapshotId) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean exists(String snapshotId) {
            return true;
        }
    }
}
