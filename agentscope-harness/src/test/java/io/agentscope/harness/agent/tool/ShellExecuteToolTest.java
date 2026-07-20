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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
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
    void execute_omittedTimeout_defaultsTo30() {
        when(sandbox.execute(eq(RT), eq("ls"), eq(30)))
                .thenReturn(new ExecuteResponse("out", 0, false));

        String result = tool.execute(RT, "ls", null, null);

        assertTrue(result.contains("Exit code: 0"));
        verify(sandbox).execute(RT, "ls", 30);
    }

    @Test
    void execute_explicitTimeout_isPassedThrough() {
        when(sandbox.execute(eq(RT), eq("ls"), eq(90)))
                .thenReturn(new ExecuteResponse("out", 0, false));

        String result = tool.execute(RT, "ls", null, 90);

        assertTrue(result.contains("Exit code: 0"));
        verify(sandbox).execute(RT, "ls", 90);
    }

    @Test
    void execute_withWorkingDirectory_prefixesCd() {
        when(sandbox.execute(eq(RT), eq("cd 'sub' && ls"), eq(30)))
                .thenReturn(new ExecuteResponse("out", 0, false));

        String result = tool.execute(RT, "ls", "sub", null);

        assertTrue(result.contains("Exit code: 0"));
        verify(sandbox).execute(RT, "cd 'sub' && ls", 30);
    }
}
