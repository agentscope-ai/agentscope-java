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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KubernetesSandboxStateSerdeTest {

    @Test
    void roundTripKubernetesState() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .registerModule(new KubernetesHarnessSandboxJacksonModule());

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s1");
        state.setNamespace("ns1");
        state.setPodName("p1");
        state.setWorkspaceRoot("/workspace");
        state.setImage("ubuntu:22.04");
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/tmp/host");
        state.setWorkspaceSpec(ws);

        String json = mapper.writeValueAsString(state);
        SandboxState read = mapper.readValue(json, SandboxState.class);
        Assertions.assertInstanceOf(KubernetesSandboxState.class, read);
        KubernetesSandboxState k = (KubernetesSandboxState) read;
        Assertions.assertEquals("ns1", k.getNamespace());
        Assertions.assertEquals("p1", k.getPodName());
    }

    @Test
    void resumeReInjectsSnapshotClient() {
        // Build a state with RemoteSandboxSnapshot that has id but client=null (simulating
        // deserialization)
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("test-session");
        state.setNamespace("default");
        state.setContainerName("agent");
        state.setWorkspaceRoot("/workspace");
        state.setImage("ubuntu:24.04");
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot("/workspace");
        state.setWorkspaceSpec(spec);
        state.setWorkspaceRootReady(false);

        RemoteSandboxSnapshot snapshotWithNullClient =
                new RemoteSandboxSnapshot(null, "snap-id-123");
        state.setSnapshot(snapshotWithNullClient);

        // Client with snapshotSpec that can rebuild the client
        RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);
        SandboxSnapshotSpec snapshotSpec = id -> new RemoteSandboxSnapshot(mockClient, id);
        KubernetesSandboxClient client =
                new KubernetesSandboxClient(
                        new KubernetesSandboxClientOptions(), null, snapshotSpec);

        KubernetesSandbox sandbox = (KubernetesSandbox) client.resume(state);

        SandboxSnapshot rebuilt = sandbox.getState().getSnapshot();
        assertNotNull(rebuilt);
        assertEquals("snap-id-123", rebuilt.getId());
        assertInstanceOf(RemoteSandboxSnapshot.class, rebuilt);
    }
}
