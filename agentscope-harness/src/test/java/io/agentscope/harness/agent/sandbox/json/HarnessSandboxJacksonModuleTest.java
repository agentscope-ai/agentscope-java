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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessSandboxJacksonModuleTest {

    @Test
    void roundTripsDockerSandboxState() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

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
    void roundTripsDockerSandboxStateWithLocalSnapshot(@TempDir Path tmp) throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        String sessionId = "snap-session-1";
        String basePath = tmp.toString();
        Files.writeString(tmp.resolve(sessionId + ".tar"), "dummy");

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId(sessionId);
        original.setSnapshot(new LocalSandboxSnapshot(basePath, sessionId));

        String json = mapper.writeValueAsString(original);
        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertInstanceOf(DockerSandboxState.class, parsed);
        assertEquals(sessionId, parsed.getSessionId());

        SandboxSnapshot snapshot = parsed.getSnapshot();
        assertNotNull(snapshot);
        assertInstanceOf(LocalSandboxSnapshot.class, snapshot);
        assertEquals(sessionId, snapshot.getId());
        assertEquals("local", snapshot.getType());
        assertTrue(snapshot.isRestorable());
    }

    @Test
    void serializesRemoteSnapshotWithoutDerivedProperties() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("remote-session-1");
        original.setSnapshot(new RemoteSandboxSnapshot(new FakeRemoteSnapshotClient(), "snap-1"));

        JsonNode snapshotNode =
                mapper.readTree(mapper.writeValueAsString(original)).get("snapshot");

        assertNotNull(snapshotNode);
        assertEquals("remote", snapshotNode.get("type").asText());
        assertEquals("snap-1", snapshotNode.get("id").asText());
        assertNull(snapshotNode.get("restorable"));
        assertNull(snapshotNode.get("persistenceEnabled"));
    }

    @Test
    void deserializesDockerSandboxStateWithRemoteSnapshotViaMapper() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("remote-session-2");
        original.setSnapshot(new RemoteSandboxSnapshot(new FakeRemoteSnapshotClient(), "snap-2"));

        SandboxState parsed =
                mapper.readValue(mapper.writeValueAsString(original), SandboxState.class);

        assertInstanceOf(DockerSandboxState.class, parsed);
        SandboxSnapshot snapshot = parsed.getSnapshot();
        assertNotNull(snapshot);
        assertInstanceOf(RemoteSandboxSnapshot.class, snapshot);
        assertEquals("snap-2", snapshot.getId());
        assertEquals("remote", snapshot.getType());
    }

    @Test
    void deserializesLegacyRemoteSnapshotJsonWithUnknownDerivedProperties() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        String json =
                """
                {
                  "type":"docker",
                  "sessionId":"remote-session-legacy",
                  "snapshot":{
                    "type":"remote",
                    "id":"snap-legacy",
                    "restorable":true,
                    "persistenceEnabled":true
                  }
                }
                """;

        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertInstanceOf(DockerSandboxState.class, parsed);
        SandboxSnapshot snapshot = parsed.getSnapshot();
        assertNotNull(snapshot);
        assertInstanceOf(RemoteSandboxSnapshot.class, snapshot);
        assertEquals("snap-legacy", snapshot.getId());
        assertEquals("remote", snapshot.getType());
    }

    @Test
    void roundTripsDockerSandboxStateWithRemoteSnapshotViaDockerClient() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());
        DockerSandboxClient client = new DockerSandboxClient(mapper);

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("remote-session-3");
        original.setSnapshot(new RemoteSandboxSnapshot(new FakeRemoteSnapshotClient(), "snap-3"));

        String json = mapper.writeValueAsString(original);
        SandboxState parsed =
                client.deserializeState(
                        json, new RemoteSnapshotSpec(new FakeRemoteSnapshotClient()));

        assertInstanceOf(DockerSandboxState.class, parsed);
        SandboxSnapshot snapshot = parsed.getSnapshot();
        assertNotNull(snapshot);
        assertInstanceOf(RemoteSandboxSnapshot.class, snapshot);
        assertEquals("snap-3", snapshot.getId());
        assertEquals("remote", snapshot.getType());
        assertTrue(snapshot.isRestorable());
    }

    private static final class FakeRemoteSnapshotClient implements RemoteSnapshotClient {

        @Override
        public void upload(String id, InputStream in) {}

        @Override
        public InputStream download(String id) {
            return InputStream.nullInputStream();
        }

        @Override
        public boolean exists(String id) {
            return true;
        }
    }
}
