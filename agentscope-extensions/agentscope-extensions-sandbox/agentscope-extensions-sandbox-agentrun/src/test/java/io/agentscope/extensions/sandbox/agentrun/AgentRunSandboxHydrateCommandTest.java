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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunSandboxHydrateCommandTest {

    @Test
    void hydratePreservesArchivePermissions() throws Exception {
        AgentRunMcpChannel mcp = mock(AgentRunMcpChannel.class);
        when(mcp.exec(anyString(), isNull(), anyInt()))
                .thenReturn(new AgentRunMcpChannel.ExecResult(0, "", ""));
        AgentRunSandbox sandbox =
                new AgentRunSandbox(state(), options(), mock(AgentRunDataPlaneHttp.class), mcp);

        sandbox.doHydrateWorkspace(new ByteArrayInputStream(new byte[] {1}));

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(mcp, times(3)).exec(commands.capture(), isNull(), anyInt());
        assertTrue(
                commands.getAllValues().stream()
                        .anyMatch(
                                command ->
                                        command.contains("subprocess.run")
                                                && command.contains("xpf")),
                "hydrate must extract with tar --preserve-permissions: " + commands.getAllValues());
    }

    private static AgentRunSandboxState state() {
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setWorkspaceRoot("/workspace");
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        state.setWorkspaceSpec(workspaceSpec);
        return state;
    }

    private static AgentRunSandboxClientOptions options() {
        AgentRunSandboxClientOptions options = new AgentRunSandboxClientOptions();
        options.setApiKey("test-key");
        options.setTemplateName("agentscope-default");
        return options;
    }
}
