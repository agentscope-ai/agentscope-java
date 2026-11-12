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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

class AgentRunSandboxWorkspaceRootTest {

    private static final String SPEC_ROOT = "/root/workspace";

    @Test
    void createUsesWorkspaceSpecRootOverOptionsDefault() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        WorkspaceSpec spec = workspaceSpec(SPEC_ROOT);

        Sandbox sandbox = client.create(spec, null, options());

        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();
        assertEquals(SPEC_ROOT, state.getWorkspaceRoot());
    }

    @Test
    void resumeUsesWorkspaceSpecRootOverPersistedStateRoot() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);

        client.resume(state);

        assertEquals(SPEC_ROOT, state.getWorkspaceRoot());
    }

    @Test
    void resumeResetsWorkspaceStateWhenRootChanges() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");

        client.resume(state);

        assertFalse(state.isWorkspaceRootReady(), "the new root has not been initialized");
        assertNull(state.getWorkspaceProjectionHash());
    }

    @Test
    void resumeKeepsWorkspaceStateWhenRootIsUnchanged() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(SPEC_ROOT);
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("same-projection");

        client.resume(state);

        assertTrue(state.isWorkspaceRootReady());
        assertEquals("same-projection", state.getWorkspaceProjectionHash());
    }

    @Test
    void resumeKeepsPersistedNasStateWhenRootIsUnchanged() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setWorkspaceSpec(workspaceSpec(SPEC_ROOT));
        state.setWorkspaceRoot(SPEC_ROOT);
        state.setWorkspaceOnNas(true);

        client.resume(state);

        assertTrue(
                state.isWorkspaceOnNas(),
                "the persisted mount state stays authoritative when the root is unchanged");
    }

    @Test
    void createMarksWorkspaceOnNasWhenSpecRootIsCoveredByMount() {
        AgentRunSandboxClient client = new AgentRunSandboxClient(options(), null);
        AgentRunSandboxClientOptions opts = options();
        opts.setNasConfig(nasMount("/mnt/data"));

        Sandbox sandbox = client.create(workspaceSpec("/mnt/data/ws"), null, opts);

        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();
        assertTrue(state.isWorkspaceOnNas());
    }

    @Test
    void filesystemSpecWorkspaceRootDrivesEffectiveWorkspaceSpec() {
        AgentRunFilesystemSpec spec =
                new AgentRunFilesystemSpec()
                        .apiKey("test-api-key")
                        .templateName("test-template")
                        .mcpServerUrl("https://example.com/mcp")
                        .nasConfig(nasMount("/mnt/data"))
                        .workspaceRoot("/mnt/data/ws");
        SandboxContext context = spec.toSandboxContext();
        AgentRunSandboxClient client = (AgentRunSandboxClient) context.getClient();

        Sandbox sandbox =
                client.create(
                        context.getWorkspaceSpec(),
                        null,
                        (AgentRunSandboxClientOptions) context.getClientOptions());

        AgentRunSandboxState state = (AgentRunSandboxState) sandbox.getState();
        assertEquals("/mnt/data/ws", state.getWorkspaceRoot());
        assertTrue(state.isWorkspaceOnNas());
    }

    private static AgentRunNasMountConfig nasMount(String mountDir) {
        AgentRunNasMountConfig nas = new AgentRunNasMountConfig();
        nas.setMountDir(mountDir);
        return nas;
    }

    private static WorkspaceSpec workspaceSpec(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        return spec;
    }

    private static AgentRunSandboxClientOptions options() {
        AgentRunSandboxClientOptions options = new AgentRunSandboxClientOptions();
        options.setApiKey("test-api-key");
        options.setTemplateName("test-template");
        options.setMcpServerUrl("https://example.com/mcp");
        return options;
    }
}
