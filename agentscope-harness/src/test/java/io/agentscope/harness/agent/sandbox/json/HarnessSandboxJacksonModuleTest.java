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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import org.junit.jupiter.api.Test;

class HarnessSandboxJacksonModuleTest {

    private ObjectMapper mapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new HarnessSandboxJacksonModule());
    }

    @Test
    void roundTripsDockerSandboxState() throws Exception {
        ObjectMapper mapper = mapper();

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("sess-1");
        original.setWorkspaceRootReady(true);

        String json = mapper.writeValueAsString(original);
        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertInstanceOf(DockerSandboxState.class, parsed);
        assertEquals("sess-1", parsed.getSessionId());
        assertEquals(true, parsed.isWorkspaceRootReady());
    }

    @Test
    void roundTripsNoopSnapshot() throws Exception {
        ObjectMapper mapper = mapper();
        SandboxSnapshot snap = new NoopSandboxSnapshot();

        String json = mapper.writeValueAsString(snap);
        SandboxSnapshot parsed = mapper.readValue(json, SandboxSnapshot.class);

        assertInstanceOf(NoopSandboxSnapshot.class, parsed);
        assertFalse(parsed.isRestorable());
    }

    @Test
    void roundTripsLocalSnapshot() throws Exception {
        ObjectMapper mapper = mapper();
        SandboxSnapshot snap = new LocalSandboxSnapshot("/tmp/snapshots", "snap-local-1");

        String json = mapper.writeValueAsString(snap);
        SandboxSnapshot parsed = mapper.readValue(json, SandboxSnapshot.class);

        assertInstanceOf(LocalSandboxSnapshot.class, parsed);
        assertEquals("snap-local-1", parsed.getId());
    }

    // RemoteSandboxSnapshot: client is null after deserialization, id preserved.
    @Test
    void roundTripsRemoteSnapshot_clientNullAfterDeserialize() throws Exception {
        ObjectMapper mapper = mapper();
        RemoteSnapshotClient mockClient = mock(RemoteSnapshotClient.class);
        SandboxSnapshot snap = new RemoteSandboxSnapshot(mockClient, "snap-remote-1");

        String json = mapper.writeValueAsString(snap);
        SandboxSnapshot parsed = mapper.readValue(json, SandboxSnapshot.class);

        assertInstanceOf(RemoteSandboxSnapshot.class, parsed);
        assertEquals("snap-remote-1", parsed.getId());
        assertNull(((RemoteSandboxSnapshot) parsed).getClient());
        assertFalse(parsed.isRestorable());
    }
}
