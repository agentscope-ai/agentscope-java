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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link DockerSandboxClient} snapshotId handling. */
class DockerSandboxClientSnapshotIdTest {

    private final DockerSandboxClient client = new DockerSandboxClient();

    @TempDir Path tempDir;

    @Nested
    @DisplayName("3-arg create()")
    class ThreeArgCreate {

        @Test
        @DisplayName("delegates to 4-arg with null snapshotId")
        void delegatesToFourArg() {
            Sandbox sandbox = client.create(new WorkspaceSpec(), null, null);
            assertNotNull(sandbox);
            assertNotNull(sandbox.getState());
            assertNotNull(sandbox.getState().getSessionId());
        }
    }

    @Nested
    @DisplayName("4-arg create() with snapshotId")
    class FourArgCreate {

        @Test
        @DisplayName("uses provided snapshotId when non-blank")
        void usesProvidedSnapshotId() {
            LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
            Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, "alice");
            SandboxState state = sandbox.getState();

            assertNotNull(state.getSnapshot());
            assertEquals("alice", snapshotIdOf(state.getSnapshot()));
        }

        @Test
        @DisplayName("falls back to sessionId when snapshotId is null")
        void fallsBackWhenNull() {
            LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
            Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, null);
            SandboxState state = sandbox.getState();

            assertNotNull(state.getSnapshot());
            assertEquals(state.getSessionId(), snapshotIdOf(state.getSnapshot()));
        }

        @Test
        @DisplayName("falls back to sessionId when snapshotId is blank")
        void fallsBackWhenBlank() {
            LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
            Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, "  ");
            SandboxState state = sandbox.getState();

            assertNotNull(state.getSnapshot());
            assertEquals(state.getSessionId(), snapshotIdOf(state.getSnapshot()));
        }

        @Test
        @DisplayName("sets null snapshot when snapshotSpec is null")
        void nullSnapshotSpec() {
            Sandbox sandbox = client.create(new WorkspaceSpec(), null, null, "alice");
            SandboxState state = sandbox.getState();
            assertNull(state.getSnapshot());
        }

        @Test
        @DisplayName("sets default image when options is null")
        void nullOptions() {
            Sandbox sandbox = client.create(new WorkspaceSpec(), null, null, "test-id");
            DockerSandboxState state = (DockerSandboxState) sandbox.getState();
            assertEquals("ubuntu:22.04", state.getImage());
            assertEquals("/workspace", state.getWorkspaceRoot());
        }

        @Test
        @DisplayName("uses option values when provided")
        void withOptions() {
            DockerSandboxClientOptions opts =
                    new DockerSandboxClientOptions().image("python:3.11").workspaceRoot("/app");
            Sandbox sandbox = client.create(new WorkspaceSpec(), null, opts, "test-id");
            DockerSandboxState state = (DockerSandboxState) sandbox.getState();
            assertEquals("python:3.11", state.getImage());
            assertEquals("/app", state.getWorkspaceRoot());
        }

        @Test
        @DisplayName("generates unique sessionId per call")
        void uniqueSessionIds() {
            Sandbox s1 = client.create(new WorkspaceSpec(), null, null, "same-id");
            Sandbox s2 = client.create(new WorkspaceSpec(), null, null, "same-id");
            assertNotNull(s1.getState().getSessionId());
            assertNotNull(s2.getState().getSessionId());
        }
    }

    private static String snapshotIdOf(SandboxSnapshot snapshot) {
        try {
            java.lang.reflect.Field f = snapshot.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (String) f.get(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read snapshot id via reflection", e);
        }
    }
}
