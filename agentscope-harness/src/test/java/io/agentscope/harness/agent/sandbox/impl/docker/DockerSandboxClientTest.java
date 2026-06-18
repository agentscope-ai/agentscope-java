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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
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

    /**
     * Tests that LocalSandboxSnapshot can be serialized and deserialized correctly.
     * This is the core fix for the bug: LocalSandboxSnapshot now has a default constructor
     * for Jackson deserialization.
     */
    @Test
    void serializeAndDeserialize_withLocalSandboxSnapshot_preservesSessionId(@TempDir Path tempDir)
            throws Exception {
        // Pre-create a snapshot tar file
        String sessionId = "test-session-id";
        Path tarFile = tempDir.resolve(sessionId + ".tar");
        try (OutputStream out = Files.newOutputStream(tarFile)) {
            out.write("dummy tar content".getBytes());
        }

        DockerSandboxClient client = new DockerSandboxClient();
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setWorkspaceRootReady(false);
        state.setSnapshot(new LocalSandboxSnapshot(tempDir.toString(), sessionId));

        // Serialize to JSON
        String json = client.serializeState(state);

        // Deserialize from JSON
        SandboxState deserialized = client.deserializeState(json);

        // Verify sessionId is preserved
        assertNotNull(deserialized);
        assertEquals(sessionId, deserialized.getSessionId());

        // Verify snapshot is correctly deserialized
        assertNotNull(deserialized.getSnapshot());
        assertTrue(deserialized.getSnapshot() instanceof LocalSandboxSnapshot);
        LocalSandboxSnapshot snapshot = (LocalSandboxSnapshot) deserialized.getSnapshot();
        assertEquals(sessionId, snapshot.getId());
        assertEquals(tempDir.toString(), snapshot.getBasePath());

        // Verify isRestorable() works after deserialization
        assertTrue(snapshot.isRestorable());
    }
}
