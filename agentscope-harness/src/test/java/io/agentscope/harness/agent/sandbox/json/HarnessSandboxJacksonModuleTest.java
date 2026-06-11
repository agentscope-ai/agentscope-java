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
package io.agentscope.harness.agent.sandbox.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class HarnessSandboxJacksonModuleTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new HarnessSandboxJacksonModule());
    }

    @Test
    void roundTripsDockerSandboxState() throws Exception {
        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("sess-1");
        original.setWorkspaceRootReady(true);

        String json = mapper().writeValueAsString(original);
        SandboxState parsed = mapper().readValue(json, SandboxState.class);

        assertInstanceOf(DockerSandboxState.class, parsed);
        assertEquals("sess-1", parsed.getSessionId());
        assertEquals(true, parsed.isWorkspaceRootReady());
    }

    @Test
    void roundTripsNoopSnapshot() throws Exception {
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("s-noop");
        state.setSnapshot(new NoopSandboxSnapshot());

        String json = mapper().writeValueAsString(state);
        SandboxState parsed = mapper().readValue(json, SandboxState.class);

        assertInstanceOf(NoopSandboxSnapshot.class, parsed.getSnapshot());
    }

    @Test
    void roundTripsLocalSnapshot() throws Exception {
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("s-local");
        state.setSnapshot(new LocalSandboxSnapshot("/tmp/snapshots", "snap-123"));

        String json = mapper().writeValueAsString(state);
        SandboxState parsed = mapper().readValue(json, SandboxState.class);

        SandboxSnapshot snap = parsed.getSnapshot();
        assertInstanceOf(LocalSandboxSnapshot.class, snap);
        LocalSandboxSnapshot local = (LocalSandboxSnapshot) snap;
        assertEquals("/tmp/snapshots", local.getBasePath());
        assertEquals("snap-123", local.getId());
    }

    /** Regression test for: RemoteSandboxSnapshot deserializes without UnrecognizedPropertyException. */
    @Test
    void roundTripsRemoteSnapshot() throws Exception {
        // Simulate the real case: RemoteSnapshotSpec creates a snapshot with a live client
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
                        return false;
                    }
                };
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("s-remote");
        state.setSnapshot(new RemoteSandboxSnapshot(mockClient, "snap-abc-456"));

        String json = mapper().writeValueAsString(state);
        SandboxState parsed = mapper().readValue(json, SandboxState.class);

        SandboxSnapshot snap = parsed.getSnapshot();
        assertInstanceOf(RemoteSandboxSnapshot.class, snap);
        assertEquals("snap-abc-456", snap.getId());
        // client is null after deserialization — re-injected by RemoteSnapshotSpec at resume time
        assertNull(((RemoteSandboxSnapshot) snap).getClient());
    }
}
