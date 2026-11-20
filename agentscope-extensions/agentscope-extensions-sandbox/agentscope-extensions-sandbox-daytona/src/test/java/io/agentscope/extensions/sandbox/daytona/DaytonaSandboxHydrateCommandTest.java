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
package io.agentscope.extensions.sandbox.daytona;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DaytonaSandboxHydrateCommandTest {

    @Test
    void hydratePreservesArchivePermissions() throws Exception {
        DaytonaHttp http = mock(DaytonaHttp.class);
        ObjectNode success = new ObjectMapper().createObjectNode();
        success.put("exitCode", 0);
        success.put("result", "");
        when(http.execute(anyString(), anyString(), anyString(), anyInt())).thenReturn(success);
        DaytonaSandbox sandbox = new DaytonaSandbox(state(), http);

        sandbox.doHydrateWorkspace(new ByteArrayInputStream(new byte[] {1}));

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(http, times(3)).execute(eq("sandbox-1"), commands.capture(), anyString(), anyInt());
        assertTrue(
                commands.getAllValues().stream()
                        .anyMatch(
                                command ->
                                        command.contains("subprocess.run")
                                                && command.contains("xpf")),
                "hydrate must extract with tar --preserve-permissions: " + commands.getAllValues());
    }

    private static DaytonaSandboxState state() {
        DaytonaSandboxState state = new DaytonaSandboxState();
        state.setSandboxId("sandbox-1");
        state.setWorkspaceRoot("/workspace");
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        state.setWorkspaceSpec(workspaceSpec);
        return state;
    }
}
