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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Test;

class DockerSandboxStateSerdeTest {

    @Test
    void resumeReInjectsSnapshotClient() {
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("test-session");
        state.setWorkspaceRoot("/workspace");
        state.setImage("ubuntu:22.04");
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot("/workspace");
        state.setWorkspaceSpec(spec);
        state.setWorkspaceRootReady(false);

        // Simulate deserialization: snapshot has id but client is null
        RemoteSandboxSnapshot snapshotWithNullClient =
                new RemoteSandboxSnapshot(null, "snap-id-123");
        state.setSnapshot(snapshotWithNullClient);

        // Client with snapshotSpec that can rebuild the client
        RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);
        SandboxSnapshotSpec snapshotSpec = id -> new RemoteSandboxSnapshot(mockClient, id);
        DockerSandboxClient client = new DockerSandboxClient(null, snapshotSpec);

        DockerSandbox sandbox = (DockerSandbox) client.resume(state);

        SandboxSnapshot rebuilt = sandbox.getState().getSnapshot();
        assertNotNull(rebuilt);
        assertEquals("snap-id-123", rebuilt.getId());
        assertInstanceOf(RemoteSandboxSnapshot.class, rebuilt);
    }
}
