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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ShellExecuteTool}. */
class ShellExecuteToolTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    private RecordingSandbox sandbox;
    private ShellExecuteTool tool;

    @BeforeEach
    void setUp() {
        sandbox = new RecordingSandbox();
        tool = new ShellExecuteTool(sandbox);
    }

    @Test
    void execute_omittedTimeout_defaultsTo30() {
        String result = tool.execute(RT, "ls", null, null);

        assertTrue(result.contains("Exit code: 0"));
        assertEquals("ls", sandbox.command);
        assertEquals(30, sandbox.timeoutSeconds);
    }

    @Test
    void execute_explicitTimeout_isPassedThrough() {
        String result = tool.execute(RT, "ls", null, 90);

        assertTrue(result.contains("Exit code: 0"));
        assertEquals("ls", sandbox.command);
        assertEquals(90, sandbox.timeoutSeconds);
    }

    @Test
    void execute_withWorkingDirectory_prefixesCd() {
        String result = tool.execute(RT, "ls", "sub", null);

        assertTrue(result.contains("Exit code: 0"));
        assertTrue(sandbox.command.startsWith("cd "));
        assertTrue(sandbox.command.endsWith(" && ls"));
        assertEquals(30, sandbox.timeoutSeconds);
    }

    @Test
    void commandWithWorkingDirectory_usesCmdCompatibleQuotingOnWindows() {
        assertEquals(
                "cd /d \"workspace dir\" && dir",
                ShellExecuteTool.commandWithWorkingDirectory("workspace dir", "dir", true));
    }

    @Test
    void commandWithWorkingDirectory_preservesShellSafeQuotingOnUnix() {
        assertEquals(
                "cd 'workspace dir' && ls",
                ShellExecuteTool.commandWithWorkingDirectory("workspace dir", "ls", false));
    }

    private static final class RecordingSandbox extends LocalFilesystemWithShell {

        private String command;
        private Integer timeoutSeconds;

        private RecordingSandbox() {
            super(Path.of(System.getProperty("java.io.tmpdir")));
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
            return new ExecuteResponse("out", 0, false);
        }
    }
}
