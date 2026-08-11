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
package io.agentscope.extensions.sandbox.dockerdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DockerDaemonSandboxClientTest {

    @Test
    void createFillsStateDefaults() {
        Assumptions.assumeTrue(
                System.getenv("DOCKER_HOST") == null, "DOCKER_HOST not set in environment");

        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        WorkspaceSpec spec = new WorkspaceSpec();

        Sandbox sandbox = client.create(spec, null, null);

        assertInstanceOf(DockerDaemonSandbox.class, sandbox);
        DockerDaemonSandboxState state = (DockerDaemonSandboxState) sandbox.getState();
        assertNotNull(state.getSessionId());
        assertEquals("ubuntu:22.04", state.getImage());
        assertEquals("/workspace", state.getWorkspaceRoot());
        assertEquals(true, state.isContainerOwned());
        assertEquals(false, state.isWorkspaceRootReady());
        assertNull(state.getDaemonUrl());
        assertNull(state.getSnapshot());
    }

    @Test
    void createAppliesOptionsAndFreezesDaemonUrl() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        WorkspaceSpec spec = new WorkspaceSpec();

        DockerDaemonSandboxClientOptions options =
                new DockerDaemonSandboxClientOptions()
                        .image("python:3.12-slim")
                        .workspaceRoot("/data")
                        .memorySizeBytes(1024L * 1024 * 1024)
                        .cpuCount(2L)
                        .exposedPorts(8080)
                        .network("bridge")
                        .daemonUrl("tcp://192.168.1.10:2375");

        DockerDaemonSandboxState state =
                (DockerDaemonSandboxState) client.create(spec, null, options).getState();

        assertEquals("python:3.12-slim", state.getImage());
        assertEquals("/data", state.getWorkspaceRoot());
        assertEquals(1024L * 1024 * 1024, state.getMemorySizeBytes());
        assertEquals(2L, state.getCpuCount());
        assertEquals("bridge", state.getNetwork());
        assertEquals("tcp://192.168.1.10:2375", state.getDaemonUrl());
    }

    @Test
    void createMergesOptionEnvironmentIntoWorkspaceSpec() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setEnvironment(Map.of("EXISTING", "keep", "SHARED", "from-spec"));

        DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SHARED", "from-options");
        env.put("NEW", "added");
        options.setEnvironment(env);

        DockerDaemonSandboxState state =
                (DockerDaemonSandboxState) client.create(spec, null, options).getState();

        // options env wins on conflicts; spec is the copy passed by the caller
        assertEquals("keep", state.getWorkspaceSpec().getEnvironment().get("EXISTING"));
        assertEquals("from-options", state.getWorkspaceSpec().getEnvironment().get("SHARED"));
        assertEquals("added", state.getWorkspaceSpec().getEnvironment().get("NEW"));
    }

    @Test
    void createBuildsSnapshotFromSpec() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        WorkspaceSpec spec = new WorkspaceSpec();
        SandboxSnapshotSpec snapshotSpec = id -> new RemoteSandboxSnapshot(null, id);

        DockerDaemonSandboxState state =
                (DockerDaemonSandboxState) client.create(spec, snapshotSpec, null).getState();

        assertNotNull(state.getSnapshot());
        assertEquals(state.getSessionId(), state.getSnapshot().getId());
    }

    @Test
    void createCopiesMountsIntoState() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        WorkspaceSpec spec = new WorkspaceSpec();
        DockerDaemonSandboxClientOptions options =
                new DockerDaemonSandboxClientOptions()
                        .mount("bind", "/host/artifacts", "/app/artifacts")
                        .mount("tmpfs", null, "/tmp/shm");

        DockerDaemonSandboxState state =
                (DockerDaemonSandboxState) client.create(spec, null, options).getState();

        assertEquals(2, state.getMounts().size());
        assertEquals(
                new MountSpec(
                        "bind",
                        WorkspaceMountSupport.normalizedHostPath("/host/artifacts"),
                        "/app/artifacts",
                        false),
                state.getMounts().get(0));
        assertEquals(new MountSpec("tmpfs", null, "/tmp/shm", false), state.getMounts().get(1));
    }

    @Test
    void resumeAcceptsDockerDaemonState() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setSessionId("s1");
        state.setContainerId("c1");

        Sandbox sandbox = client.resume(state);

        assertInstanceOf(DockerDaemonSandbox.class, sandbox);
        assertEquals("c1", ((DockerDaemonSandboxState) sandbox.getState()).getContainerId());
    }

    @Test
    void resumeRejectsForeignState() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();

        assertThrows(IllegalArgumentException.class, () -> client.resume(new SandboxState() {}));
    }

    @Test
    void serializeDeserializeRoundTripsDaemonUrl() {
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient();
        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setSessionId("sess-daemon");
        state.setDaemonUrl("tcp://127.0.0.1:2375");

        SandboxState parsed = client.deserializeState(client.serializeState(state));

        assertInstanceOf(DockerDaemonSandboxState.class, parsed);
        assertEquals("tcp://127.0.0.1:2375", ((DockerDaemonSandboxState) parsed).getDaemonUrl());
    }

    @Test
    void deserializeRebindsRemoteSnapshot() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new DockerDaemonHarnessSandboxJacksonModule());
        DockerDaemonSandboxClient client = new DockerDaemonSandboxClient(mapper);
        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setSessionId("remote-session");
        state.setSnapshot(new RemoteSandboxSnapshot(new FakeRemoteSnapshotClient(), "snap-1"));

        SandboxState parsed =
                client.deserializeState(
                        client.serializeState(state),
                        new RemoteSnapshotSpec(new FakeRemoteSnapshotClient()));

        SandboxSnapshot snapshot = parsed.getSnapshot();
        assertNotNull(snapshot);
        assertInstanceOf(RemoteSandboxSnapshot.class, snapshot);
        assertEquals("snap-1", snapshot.getId());
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
