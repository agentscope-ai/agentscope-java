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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
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
    @SuppressWarnings("unchecked")
    void schemaDescribesShellCapabilitiesAndPerCallParameters() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        var schema = toolkit.getToolSchemas().get(0);

        assertTrue(schema.getDescription().contains("environment's shell"));
        assertTrue(schema.getDescription().contains("when they support the operation"));
        assertTrue(schema.getDescription().contains("Each call starts a new shell"));
        assertTrue(schema.getDescription().contains("changes are scoped to that call"));
        Map<String, Map<String, Object>> properties =
                (Map<String, Map<String, Object>>) schema.getParameters().get("properties");
        assertTrue(
                properties
                        .get("working_directory")
                        .get("description")
                        .toString()
                        .contains("this command only, relative to the shell's starting directory"));
        assertEquals(
                "Timeout in seconds (default: 30)", properties.get("timeout").get("description"));
    }

    /** Extracts the concatenated text output of a tool result. */
    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Test
    void execute_omittedTimeout_defaultsTo30() {
        ToolResultBlock result = tool.execute(RT, "ls", null, null);

        assertTrue(textOf(result).contains("Exit code: 0"));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("ls", sandbox.command);
        assertEquals(30, sandbox.timeoutSeconds);
    }

    @Test
    void execute_explicitTimeout_isPassedThrough() {
        ToolResultBlock result = tool.execute(RT, "ls", null, 90);

        assertTrue(textOf(result).contains("Exit code: 0"));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("ls", sandbox.command);
        assertEquals(90, sandbox.timeoutSeconds);
    }

    @Test
    void execute_withWorkingDirectory_prefixesCd() {
        ToolResultBlock result = tool.execute(RT, "ls", "sub", null);

        assertTrue(textOf(result).contains("Exit code: 0"));
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(sandbox.command.startsWith("cd "));
        assertTrue(sandbox.command.endsWith(" && ls"));
        assertEquals(30, sandbox.timeoutSeconds);
    }

    @Test
    void execute_rejectsAbsoluteWorkingDirectory() {
        ToolResultBlock result = tool.execute(RT, "ls", "/etc", null);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("must be a relative path"));
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

    @Test
    void shellSelectionFollowsLocalOverlayAndPrimaryRouting() {
        AbstractFilesystem lower = mock(AbstractFilesystem.class);
        AbstractSandboxFilesystem remote = mock(AbstractSandboxFilesystem.class);

        assertTrue(ShellExecuteTool.usesWindowsShell(sandbox, true));
        assertFalse(ShellExecuteTool.usesWindowsShell(sandbox, false));
        assertTrue(ShellExecuteTool.usesWindowsShell(OverlayFilesystem.of(sandbox, lower), true));
        assertTrue(
                ShellExecuteTool.usesWindowsShell(
                        new RoutedSandboxFilesystem(sandbox, Map.of()), true));
        assertFalse(ShellExecuteTool.usesWindowsShell(remote, true));
        assertFalse(ShellExecuteTool.usesWindowsShell(OverlayFilesystem.of(remote, lower), true));
        assertFalse(
                ShellExecuteTool.usesWindowsShell(
                        new RoutedSandboxFilesystem(remote, Map.of()), true));
    }

    @Test
    void remoteWorkingDirectoryUsesPosixSyntaxEvenOnWindowsHost() {
        AbstractSandboxFilesystem remote = mock(AbstractSandboxFilesystem.class);
        when(remote.execute(RT, "cd 'project dir' && echo ok", 30))
                .thenReturn(new ExecuteResponse("ok", 0, false));

        var result = new ShellExecuteTool(remote).execute(RT, "echo ok", "project dir", null);

        assertEquals(ToolResultState.SUCCESS, result.getState());
        verify(remote).execute(RT, "cd 'project dir' && echo ok", 30);
    }

    @Test
    void preservesFailureAndTruncationAsFacts() {
        sandbox.response = new ExecuteResponse("failed", 2, true);
        var result = tool.execute(RT, "test", null, null);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(
                new ToolExecutionDetails("shell", ToolExecutionDetails.Outcome.FAILED, 2, true),
                result.getExecutionDetails());
    }

    @Test
    void missingExitCodeIsNotSuccess() {
        sandbox.response = new ExecuteResponse("unknown", null, false);
        var result = tool.execute(RT, "test", null, null);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(ToolExecutionDetails.Outcome.UNKNOWN, result.getExecutionDetails().outcome());
    }

    private static final class RecordingSandbox extends LocalFilesystemWithShell {

        private String command;
        private Integer timeoutSeconds;
        private ExecuteResponse response = new ExecuteResponse("out", 0, false);

        private RecordingSandbox() {
            super(Path.of(System.getProperty("java.io.tmpdir")));
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
            return response;
        }
    }
}
