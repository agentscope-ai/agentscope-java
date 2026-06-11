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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.InputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KubernetesSandboxStateSerdeTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new HarnessSandboxJacksonModule())
                .registerModule(new KubernetesHarnessSandboxJacksonModule());
    }

    @Test
    void roundTripKubernetesState() throws Exception {
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s1");
        state.setNamespace("ns1");
        state.setPodName("p1");
        state.setWorkspaceRoot("/workspace");
        state.setImage("ubuntu:22.04");
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/tmp/host");
        state.setWorkspaceSpec(ws);

        String json = mapper().writeValueAsString(state);
        SandboxState read = mapper().readValue(json, SandboxState.class);
        Assertions.assertInstanceOf(KubernetesSandboxState.class, read);
        KubernetesSandboxState k = (KubernetesSandboxState) read;
        Assertions.assertEquals("ns1", k.getNamespace());
        Assertions.assertEquals("p1", k.getPodName());
    }

    /**
     * Regression test for issue #1710:
     * KubernetesSandboxState + RemoteSandboxSnapshot failed to deserialize on second call
     * with UnrecognizedPropertyException on the "type" field.
     */
    @Test
    void roundTripKubernetesStateWithRemoteSnapshot() throws Exception {
        // Simulate JdbcSnapshotSpec creating a RemoteSandboxSnapshot with a live client
        RemoteSnapshotClient mockClient =
                new RemoteSnapshotClient() {
                    @Override
                    public void upload(String snapshotId, InputStream data) {}

                    @Override
                    public InputStream download(String snapshotId) {
                        return null;
                    }

                    @Override
                    public boolean exists(String snapshotId) {
                        return true;
                    }
                };
        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId("s2");
        state.setNamespace("sandbox-ns");
        state.setPodName("agent-pod-abc");
        state.setSnapshot(new RemoteSandboxSnapshot(mockClient, "jdbc-snapshot-id-xyz"));

        // First call: serialize
        String json = mapper().writeValueAsString(state);
        // Second call simulation: deserialize — this was throwing UnrecognizedPropertyException
        SandboxState read = mapper().readValue(json, SandboxState.class);

        Assertions.assertInstanceOf(KubernetesSandboxState.class, read);
        SandboxSnapshot snap = read.getSnapshot();
        Assertions.assertInstanceOf(RemoteSandboxSnapshot.class, snap);
        Assertions.assertEquals("jdbc-snapshot-id-xyz", snap.getId());
        // client is null until SandboxManager re-injects it via snapshotSpec.build()
        Assertions.assertNull(((RemoteSandboxSnapshot) snap).getClient());
        // with null client, isRestorable() returns false (safe degradation to cold start)
        Assertions.assertFalse(snap.isRestorable());
    }
}
