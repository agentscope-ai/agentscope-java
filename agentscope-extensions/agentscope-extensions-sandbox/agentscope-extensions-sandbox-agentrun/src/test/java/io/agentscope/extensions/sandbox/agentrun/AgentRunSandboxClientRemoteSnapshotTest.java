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
package io.agentscope.extensions.sandbox.agentrun;

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

class AgentRunSandboxClientRemoteSnapshotTest {

    @Test
    void deserializeStateRebindsRemoteSnapshotClient() {
        AgentRunSandboxClient client = new AgentRunSandboxClient();
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);
        state.setWorkspaceSpec(workspaceSpec);
        state.setWorkspaceRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);
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
