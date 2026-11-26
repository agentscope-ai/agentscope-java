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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertErrorKind;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertNoErrorMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
                "Positive timeout in seconds (default: 30). Unsupported values are rejected before"
                        + " execution.",
                properties.get("timeout").get("description"));
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
        assertNoErrorMetadata(result);
        assertEquals("ls", sandbox.command);
        assertEquals(30, sandbox.timeoutSeconds);
    }

    @Test
    void invalidTimeoutDoesNotExecuteCommand() {
        for (int timeout : new int[] {0, -1}) {
            ToolResultBlock result = tool.execute(RT, "side-effect", null, timeout);
            assertEquals(ToolResultState.ERROR, result.getState());
            assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
            assertEquals(null, sandbox.command);
        }
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
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
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
        assertErrorKind(result, ToolErrorKind.COMMAND_FAILED, "test");
    }

    @Test
    void exposesConfirmedTimeoutToTheModel() {
        sandbox.response = new ExecuteResponse("partial output", 124, false, true);

        var result = tool.execute(RT, "sleep 60", null, null);

        assertTrue(textOf(result).contains("Timed out: true"));
        assertTrue(textOf(result).contains("partial output"));
        assertEquals(ToolResultState.ERROR, result.getState());
        assertErrorKind(result, ToolErrorKind.TIMEOUT, "sleep");
    }

    @Test
    void missingExitCodeIsNotSuccess() {
        sandbox.response = new ExecuteResponse("unknown", null, false);
        var result = tool.execute(RT, "test", null, null);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(ToolExecutionDetails.Outcome.UNKNOWN, result.getExecutionDetails().outcome());
        assertErrorKind(result, ToolErrorKind.INTERNAL_ERROR, "test");
    }

    @Test
    void truncatedExternalOutputDoesNotInventRecoveryPathOrTail() {
        AbstractSandboxFilesystem external = mock(AbstractSandboxFilesystem.class);
        when(external.execute(RT, "test", 30))
                .thenReturn(new ExecuteResponse("only the head survived", 2, true));

        ToolResultBlock result = new ShellExecuteTool(external).execute(RT, "test", null, null);

        String output = textOf(result);
        assertTrue(output.contains("Output was truncated"));
        assertTrue(
                output.contains(
                        "Without a saved-output path above, omitted content is unavailable"));
        assertFalse(output.contains("read_file"));
        assertFalse(output.contains("large_tool_results"));
        assertFalse(output.contains("saved to"));
        assertFalse(output.contains("tail"));
        assertErrorKind(result, ToolErrorKind.COMMAND_FAILED, "test");
        verify(external).execute(RT, "test", 30);
    }

    @Test
    void localSavedOutputReferenceCanBeReadWithFileTool(@TempDir Path tempDir) {
        LocalFilesystemWithShell local =
                new LocalFilesystemWithShell(tempDir, false, 30, 16, null, false);
        ShellExecuteTool localTool = new ShellExecuteTool(local);
        ToolResultBlock result =
                localTool.execute(RT, "echo diagnostic-output-with-a-long-tail", null, null);
        String output = textOf(result);
        var reference =
                java.util.regex.Pattern.compile("Use `read_file` with path `([^`]+)`")
                        .matcher(output);
        assertTrue(reference.find(), output);

        ToolResultBlock read =
                new FilesystemTool(local).readFile(RT, reference.group(1), null, null);

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertNoErrorMetadata(result);
        assertTrue(result.getExecutionDetails().outputTruncated());
        assertTrue(textOf(read).contains("diagnostic-output-with-a-long-tail"));
        assertFalse(textOf(read).contains("bytes omitted"));
    }

    @Test
    void exitZeroWithTracebackIsStillSuccess() {
        sandbox.response =
                new ExecuteResponse(
                        "Traceback (most recent call last):\nValueError: demonstration", 0, false);

        ToolResultBlock result = tool.execute(RT, "python example.py", null, null);

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals(
                ToolExecutionDetails.Outcome.SUCCEEDED, result.getExecutionDetails().outcome());
        assertNoErrorMetadata(result);
        assertTrue(textOf(result).contains("Traceback"));
    }

    @Test
    void executeResponseThreeAndFourArgumentConstructorsRemainCompatible() {
        ExecuteResponse legacy = new ExecuteResponse("same", 0, false);
        ExecuteResponse explicit = new ExecuteResponse("same", 0, false, false);
        assertEquals(legacy, explicit);
        assertTrue(legacy.isSuccess());
        assertFalse(legacy.timedOut());

        sandbox.response = new ExecuteResponse("partial", 0, false, true);
        ToolResultBlock timedOut = tool.execute(RT, "python script.py", null, null);
        assertEquals(ToolResultState.ERROR, timedOut.getState());
        assertTrue(textOf(timedOut).contains("Timed out: true"));
        assertErrorKind(timedOut, ToolErrorKind.TIMEOUT, "python");
    }

    @Test
    void outputDigestMetadataIsIndependentOfSuccessFailureTruncationAndTimeout() {
        String digest = "AB".repeat(32);
        for (ExecuteResponse response :
                new ExecuteResponse[] {
                    new ExecuteResponse("ok", 0, false, false, digest),
                    new ExecuteResponse("failed", 7, true, false, digest),
                    new ExecuteResponse("partial", 124, true, true, digest)
                }) {
            sandbox.response = response;
            ToolResultBlock result = tool.execute(RT, "test", null, null);
            assertEquals(
                    digest.toLowerCase(java.util.Locale.ROOT),
                    result.getMetadata().get("agentscope_output_sha256"));
            assertEquals(
                    response.isSuccess() ? ToolResultState.SUCCESS : ToolResultState.ERROR,
                    result.getState());
            assertEquals(response.exitCode(), result.getExecutionDetails().exitCode());
            assertEquals(response.truncated(), result.getExecutionDetails().outputTruncated());
            if (response.isSuccess()) {
                assertNoErrorMetadata(result);
            } else {
                assertErrorKind(
                        result,
                        response.timedOut() ? ToolErrorKind.TIMEOUT : ToolErrorKind.COMMAND_FAILED,
                        "test");
            }
        }
    }

    @Test
    void absentOrInvalidBackendDigestsDoNotAddMetadata() {
        for (String digest :
                new String[] {
                    null,
                    "",
                    "a".repeat(63),
                    "g".repeat(64),
                    "a".repeat(64) + "\n",
                    "large_tool_results/execute/uuid.txt"
                }) {
            sandbox.response = new ExecuteResponse("failed", 7, true, false, digest);
            ToolResultBlock result = tool.execute(RT, "test", null, null);
            assertFalse(result.getMetadata().containsKey("agentscope_output_sha256"));
            assertErrorKind(result, ToolErrorKind.COMMAND_FAILED, "test");
        }
    }

    @Test
    void executeResponseJsonOmitsNullDigestAndPreservesLegacyConstructors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ExecuteResponse legacy = new ExecuteResponse("same", 0, false);
        assertNull(legacy.outputDigest());
        assertFalse(mapper.readTree(mapper.writeValueAsString(legacy)).has("outputDigest"));
        assertEquals(
                legacy,
                mapper.readValue(
                        "{\"output\":\"same\",\"exitCode\":0,\"truncated\":false,\"timedOut\":false}",
                        ExecuteResponse.class));
        String digest = "ab".repeat(32);
        ExecuteResponse withDigest = new ExecuteResponse("same", 0, false, false, digest);
        var json = mapper.readTree(mapper.writeValueAsString(withDigest));
        assertEquals(digest, json.get("outputDigest").asText());
        // isSuccess() already serializes as a derived getter; preserve that legacy behavior.
        assertEquals(
                withDigest,
                mapper.readValue(
                        "{\"output\":\"same\",\"exitCode\":0,\"truncated\":false,"
                                + "\"timedOut\":false,\"outputDigest\":\""
                                + digest
                                + "\"}",
                        ExecuteResponse.class));
        assertTrue(withDigest.isSuccess());
    }

    @ParameterizedTest
    @MethodSource("executableScopes")
    void failureScopeRecognizesSimplePrefixesWithoutIncludingArguments(
            String command, String scope) {
        assertEquals(scope, ShellExecuteTool.executableScope(command));
        sandbox.response = new ExecuteResponse("failed", 1, false);
        ToolResultBlock result = tool.execute(RT, command, null, null);
        assertErrorKind(result, ToolErrorKind.COMMAND_FAILED, scope);
    }

    static Stream<Arguments> executableScopes() {
        return Stream.of(
                Arguments.of("git status --token secret", "git"),
                Arguments.of("/usr/bin/git status", "git"),
                Arguments.of(
                        "\"/opt/tools with spaces/my executable\" --token secret", "my executable"),
                Arguments.of("'C:\\Program Files\\Git\\bin\\git.exe' status", "git.exe"),
                Arguments.of("\"C:\\Program Files\\Python\\python.exe\" script.py", "python.exe"),
                Arguments.of("/opt/my\\ tools/git status", "git"),
                Arguments.of("cd 'project dir' && git diff", "git"),
                Arguments.of("cd project; python -m pytest", "python"),
                Arguments.of("cd /d \"C:\\work dir\" && git status", "git"),
                Arguments.of("cd -- project && cd nested; command -p git diff", "git"),
                Arguments.of("cd 'a; b && c' && git status", "git"),
                Arguments.of("cd 'a'\\''b' && git status", "git"),
                Arguments.of("VAR=x MODE='secret value' /usr/bin/python -V", "python"),
                Arguments.of("env KEY=value python script.py", "python"),
                Arguments.of("env -u HOME -i -- TOKEN='top secret' git status", "git"),
                Arguments.of("/usr/bin/env --unset=HOME -uPATH VAR=x command -- git status", "git"),
                Arguments.of("command -p -- /bin/git diff", "git"),
                Arguments.of("exec -- /bin/python -V", "python"),
                Arguments.of("exec -a 'do not leak me' -c /bin/python -V", "python"),
                Arguments.of("env -i command -p exec -- /usr/bin/git status", "git"),
                Arguments.of("cd", "cd"));
    }

    @Test
    void envOptionsAfterAssignmentsDoNotExposeArgumentsInFailureMetadata() {
        sandbox.response = new ExecuteResponse("failed", 127, false);
        ToolResultBlock result = tool.execute(RT, "env X=1 -u HOME secret", null, null);
        assertErrorKind(result, ToolErrorKind.COMMAND_FAILED, null);
        assertFalse(result.getMetadata().containsValue("secret"));
    }

    @ParameterizedTest
    @MethodSource("ambiguousCommands")
    void failureScopeIsNullForAmbiguousOrUnsupportedSyntax(String command) {
        assertNull(ShellExecuteTool.executableScope(command), command);
    }

    static Stream<Arguments> ambiguousCommands() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("  "),
                Arguments.of("VAR=secret"),
                Arguments.of("\"VAR=secret\" git"),
                Arguments.of("env VAR=secret"),
                Arguments.of("env X=1 -u HOME secret"),
                Arguments.of("env -u"),
                Arguments.of("env -u HOME"),
                Arguments.of("env --unknown secret git"),
                Arguments.of("env -S 'git status'"),
                Arguments.of("command -v git"),
                Arguments.of("exec -a"),
                Arguments.of("cd dir &&"),
                Arguments.of("cd one two && git"),
                Arguments.of("cd -X && git"),
                Arguments.of("cd dir || git"),
                Arguments.of("git status && python script.py"),
                Arguments.of("git status | tee output.txt"),
                Arguments.of("if true; then git; fi"),
                Arguments.of("$(executable) --token secret"),
                Arguments.of("$EXECUTABLE --token secret"),
                Arguments.of("%EXECUTABLE% --token secret"),
                Arguments.of("'unterminated executable"));
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
