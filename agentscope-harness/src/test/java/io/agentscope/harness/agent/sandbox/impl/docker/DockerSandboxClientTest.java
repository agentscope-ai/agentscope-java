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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxClientTest {

    @Test
    void create_generatesNewSessionIdWhenNoSnapshotExists(@TempDir Path tempDir) {
        DockerSandboxClient client = new DockerSandboxClient();
        LocalSnapshotSpec snapshotSpec = new LocalSnapshotSpec(tempDir.toString());

        Sandbox sandbox =
                client.create(new WorkspaceSpec(), snapshotSpec, new DockerSandboxClientOptions());

        assertNotNull(sandbox);
        assertNotNull(sandbox.getState().getSessionId());
        // Session ID should be a UUID (36 chars with dashes)
        assertEquals(36, sandbox.getState().getSessionId().length());
    }

    @Test
    void create_reusesExistingSnapshotSessionId(@TempDir Path tempDir) throws Exception {
        // Pre-create a snapshot tar file
        String existingSessionId = "existing-session-id";
        Path tarFile = tempDir.resolve(existingSessionId + ".tar");
        try (OutputStream out = Files.newOutputStream(tarFile)) {
            out.write("dummy tar content".getBytes());
        }

        DockerSandboxClient client = new DockerSandboxClient();
        LocalSnapshotSpec snapshotSpec = new LocalSnapshotSpec(tempDir.toString());

        Sandbox sandbox =
                client.create(new WorkspaceSpec(), snapshotSpec, new DockerSandboxClientOptions());

        assertNotNull(sandbox);
        assertEquals(
                existingSessionId,
                sandbox.getState().getSessionId(),
                "Should reuse the existing snapshot's session ID");
        SandboxSnapshot snapshot = sandbox.getState().getSnapshot();
        assertNotNull(snapshot);
        assertEquals(existingSessionId, snapshot.getId());
    }

    @Test
    void create_withNoopSnapshotSpec_generatesNewSessionId() {
        DockerSandboxClient client = new DockerSandboxClient();
        NoopSnapshotSpec snapshotSpec = new NoopSnapshotSpec();

        Sandbox sandbox =
                client.create(new WorkspaceSpec(), snapshotSpec, new DockerSandboxClientOptions());

        assertNotNull(sandbox);
        assertNotNull(sandbox.getState().getSessionId());
        assertEquals(36, sandbox.getState().getSessionId().length());
    }

    @Test
    void create_withNullSnapshotSpec_generatesNewSessionId() {
        DockerSandboxClient client = new DockerSandboxClient();

        Sandbox sandbox =
                client.create(new WorkspaceSpec(), null, new DockerSandboxClientOptions());

        assertNotNull(sandbox);
        assertNotNull(sandbox.getState().getSessionId());
        assertEquals(36, sandbox.getState().getSessionId().length());
    }

    @Test
    void create_withExistingSnapshot_workspaceRootReadyIsFalse(@TempDir Path tempDir)
            throws Exception {
        String existingSessionId = "existing-session-id";
        Path tarFile = tempDir.resolve(existingSessionId + ".tar");
        try (OutputStream out = Files.newOutputStream(tarFile)) {
            out.write("dummy tar content".getBytes());
        }

        DockerSandboxClient client = new DockerSandboxClient();
        LocalSnapshotSpec snapshotSpec = new LocalSnapshotSpec(tempDir.toString());

        Sandbox sandbox =
                client.create(new WorkspaceSpec(), snapshotSpec, new DockerSandboxClientOptions());

        assertEquals(false, sandbox.getState().isWorkspaceRootReady());
    }

    @Test
    void resume_restoresDockerSandboxState() {
        DockerSandboxClient client = new DockerSandboxClient();
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("resumed-session");
        state.setWorkspaceSpec(new WorkspaceSpec());

        Sandbox sandbox = client.resume(state);

        assertNotNull(sandbox);
        assertEquals("resumed-session", sandbox.getState().getSessionId());
    }

    @Test
    void serializeAndDeserialize_roundTripsState() throws Exception {
        DockerSandboxClient client = new DockerSandboxClient();
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("serialize-test");
        state.setWorkspaceRootReady(true);

        String json = client.serializeState(state);
        var deserialized = client.deserializeState(json);

        assertNotNull(deserialized);
        assertEquals("serialize-test", deserialized.getSessionId());
        assertEquals(true, deserialized.isWorkspaceRootReady());
    }
}
