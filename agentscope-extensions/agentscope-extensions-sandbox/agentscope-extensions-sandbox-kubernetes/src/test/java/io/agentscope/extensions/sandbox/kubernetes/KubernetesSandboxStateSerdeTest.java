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

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KubernetesSandboxStateSerdeTest {

    private ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new HarnessSandboxJacksonModule())
                .registerModule(new KubernetesHarnessSandboxJacksonModule());
    }

    @Test
    void roundTripKubernetesState() throws Exception {
        ObjectMapper mapper = mapper();

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

    // Verifies RemoteSandboxSnapshot survives a JSON round-trip:
    // id is preserved, client is null after deserialization (re-injected via reconnectSnapshot).
    @Test
    void roundTripKubernetesStateWithRemoteSnapshot() throws Exception {
        ObjectMapper mapper = mapper();

        RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);
        String snapshotId = "snap-abc";

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s2");
        state.setNamespace("ns2");
        state.setSnapshot(new RemoteSandboxSnapshot(mockClient, snapshotId));

        String json = mapper.writeValueAsString(state);
        SandboxState read = mapper.readValue(json, SandboxState.class);

        Assertions.assertInstanceOf(RemoteSandboxSnapshot.class, read.getSnapshot());
        RemoteSandboxSnapshot restored = (RemoteSandboxSnapshot) read.getSnapshot();
        Assertions.assertEquals(snapshotId, restored.getId());
        Assertions.assertNull(restored.getClient(), "client must be null before reconnect");
        Assertions.assertFalse(restored.isRestorable(), "not restorable without client");
    }

    // Verifies reconnectSnapshot re-injects the client so the snapshot becomes usable.
    @Test
    void reconnectSnapshotRestoresClient() throws Exception {
        ObjectMapper mapper = mapper();

        RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);
        String snapshotId = "snap-xyz";

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s3");
        state.setSnapshot(new RemoteSandboxSnapshot(mockClient, snapshotId));

        String json = mapper.writeValueAsString(state);
        SandboxState read = mapper.readValue(json, SandboxState.class);

        RemoteSnapshotSpec spec = new RemoteSnapshotSpec(mockClient);
        read.reconnectSnapshot(spec);

        RemoteSandboxSnapshot reconnected = (RemoteSandboxSnapshot) read.getSnapshot();
        Assertions.assertNotNull(reconnected.getClient(), "client must be present after reconnect");
        Assertions.assertEquals(snapshotId, reconnected.getId());
    }

    // Verifies reconnectSnapshot is a no-op when snapshotSpec is null.
    @Test
    void reconnectSnapshot_nullSpec_isNoop() throws Exception {
        ObjectMapper mapper = mapper();

        RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s4");
        state.setSnapshot(new RemoteSandboxSnapshot(mockClient, "snap-noop"));

        String json = mapper.writeValueAsString(state);
        SandboxState read = mapper.readValue(json, SandboxState.class);

        read.reconnectSnapshot(null); // must not throw

        RemoteSandboxSnapshot snapshot = (RemoteSandboxSnapshot) read.getSnapshot();
        Assertions.assertNull(snapshot.getClient(), "client remains null when spec is null");
    }
}
