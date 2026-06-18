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
package io.agentscope.extensions.sandbox.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRunSandboxClientSnapshotIdTest {

    private final AgentRunSandboxClient client;

    AgentRunSandboxClientSnapshotIdTest() {
        AgentRunSandboxClientOptions opts = new AgentRunSandboxClientOptions();
        opts.setApiKey("test-key");
        opts.setTemplateName("test-template");
        opts.setMcpServerUrl("http://localhost:8080");
        opts.setRegion("cn-hangzhou");
        opts.setAccountId("123456789");
        this.client = new AgentRunSandboxClient(opts, null);
    }

    @TempDir Path tempDir;

    @Test
    @DisplayName("constructor tolerates null defaultOptions")
    void constructorWithNullDefaultOptions() {
        AgentRunSandboxClient c = new AgentRunSandboxClient(null, null);
        AgentRunSandboxClientOptions opts = new AgentRunSandboxClientOptions();
        opts.setApiKey("test-key");
        opts.setTemplateName("test-template");
        opts.setMcpServerUrl("http://localhost:8080");
        opts.setRegion("cn-hangzhou");
        opts.setAccountId("123456789");
        Sandbox sandbox = c.create(new WorkspaceSpec(), null, opts);
        assertNotNull(sandbox);
        assertNotNull(sandbox.getState().getSessionId());
    }

    @Test
    @DisplayName("3-arg create delegates to 4-arg")
    void threeArgDelegatesToFourArg() {
        Sandbox sandbox = client.create(new WorkspaceSpec(), null, null);
        assertNotNull(sandbox);
        assertNotNull(sandbox.getState().getSessionId());
    }

    @Test
    @DisplayName("4-arg create uses provided snapshotId")
    void usesProvidedSnapshotId() {
        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
        Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, "dave");
        SandboxState state = sandbox.getState();
        assertNotNull(state.getSnapshot());
        assertEquals("dave", snapshotIdOf(state.getSnapshot()));
    }

    @Test
    @DisplayName("4-arg create falls back to sessionId when null")
    void fallsBackWhenNull() {
        LocalSnapshotSpec spec = new LocalSnapshotSpec(tempDir.toString());
        Sandbox sandbox = client.create(new WorkspaceSpec(), spec, null, null);
        SandboxState state = sandbox.getState();
        assertNotNull(state.getSnapshot());
        assertEquals(state.getSessionId(), snapshotIdOf(state.getSnapshot()));
    }

    private static String snapshotIdOf(SandboxSnapshot snapshot) {
        try {
            java.lang.reflect.Field f = snapshot.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (String) f.get(snapshot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
