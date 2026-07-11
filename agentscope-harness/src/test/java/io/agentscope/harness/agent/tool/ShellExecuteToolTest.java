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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ShellExecuteTool}. */
class ShellExecuteToolTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    private AbstractSandboxFilesystem sandbox;
    private ShellExecuteTool tool;

    @BeforeEach
    void setUp() {
        sandbox = mock(AbstractSandboxFilesystem.class);
        tool = new ShellExecuteTool(sandbox);
    }

    @Test
    void schema_onlyRequiresCommand() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        AgentTool registeredTool = toolkit.getTool(ShellExecuteTool.NAME);

        assertEquals(List.of("command"), registeredTool.getParameters().get("required"));
    }

    @Test
    void execute_omittedOptionalParameters_defaultsTimeoutToThirtySeconds() {
        when(sandbox.execute(RT, "pwd", 30)).thenReturn(new ExecuteResponse("", 0, false));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        Map<String, Object> input = Map.of("command", "pwd");
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call-execute")
                        .name(ShellExecuteTool.NAME)
                        .input(input)
                        .content("{\"command\":\"pwd\"}")
                        .build();

        toolkit.callTool(
                        ToolCallParam.builder()
                                .toolUseBlock(toolUse)
                                .input(input)
                                .runtimeContext(RT)
                                .build())
                .block(Duration.ofSeconds(3));

        verify(sandbox).execute(RT, "pwd", 30);
    }

    @Test
    void execute_explicitTimeout_passesTimeoutToSandbox() {
        when(sandbox.execute(RT, "pwd", 10)).thenReturn(new ExecuteResponse("", 0, false));

        tool.execute(RT, "pwd", null, 10);

        verify(sandbox).execute(RT, "pwd", 10);
    }

    @Test
    void execute_nonPositiveTimeout_defaultsTimeoutToThirtySeconds() {
        when(sandbox.execute(RT, "pwd", 30)).thenReturn(new ExecuteResponse("", 0, false));

        tool.execute(RT, "pwd", null, 0);

        verify(sandbox).execute(RT, "pwd", 30);
    }
}
