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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemWithShellTest {

    @Test
    void outputCharset_usesNativeEncodingOnWindows() {
        assertEquals(
                Charset.forName("windows-1252"),
                LocalFilesystemWithShell.outputCharset("Windows 10", "windows-1252"));
    }

    @Test
    void outputCharset_usesUtf8OnNonWindowsSystems() {
        assertEquals(StandardCharsets.UTF_8, LocalFilesystemWithShell.outputCharset("Linux"));
    }

    @Test
    void outputCharset_fallsBackToDefaultWhenWindowsNativeEncodingIsUnavailable() {
        assertEquals(
                Charset.defaultCharset(),
                LocalFilesystemWithShell.outputCharset("Windows 10", null));
    }

    @Test
    void execute_outputLargerThanOsPipeBufferCompletesWithoutDeadlock(@TempDir Path tempDir) {
        // ~68-72 KB of stdout: beyond the OS pipe buffer (~4 KB on Windows, 64 KB on Linux),
        // below the default maxOutputBytes cap. Before stdout/stderr were drained concurrently
        // with waitFor, this deadlocked and was misreported as a timeout (exit 124).
        int lines = 4000;
        String payload = "0123456789abcdef"; // 16 chars per line
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command =
                windows
                        ? "for /l %i in (1,1," + lines + ") do @echo " + payload
                        : "i=0; while [ \"$i\" -lt "
                                + lines
                                + " ]; do echo "
                                + payload
                                + "; i=$((i+1)); done";

        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), "unexpected exit code, output: " + resp.output());
        assertFalse(resp.truncated());
        assertEquals(lines, resp.output().split(payload, -1).length - 1);
        assertFalse(Files.exists(tempDir.resolve("large_tool_results")));
    }

    @Test
    void execute_keepsHeadAndDiagnosticTailAndSavesCompleteCombinedOutput(@TempDir Path tempDir)
            throws IOException {
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, 256, null, false);
        ExecuteResponse response = fs.execute(null, largeOutputCommand(7), 60);

        assertEquals(7, response.exitCode());
        assertFalse(response.timedOut());
        assertTrue(response.truncated());
        assertTrue(response.output().startsWith("stdout-head"));
        assertTrue(response.output().contains("UTF-8 bytes omitted"));
        assertTrue(response.output().contains("stdout-tail"));
        assertTrue(response.output().contains("[stderr] fatal-tail"));
        assertTrue(response.output().endsWith("Exit code: 7"));
        String path = savedPath(response);
        ReadResult read = fs.read(null, path, 0, 0);
        assertTrue(read.isSuccess(), read.error());
        String complete = read.fileData().content();
        assertEquals(80, complete.split("0123456789abcdef", -1).length - 1);
        assertTrue(complete.contains("stdout-tail"));
        assertTrue(complete.endsWith("[stderr] fatal-tail"));
        assertFalse(complete.contains("bytes omitted"));
        assertEquals(complete, Files.readString(tempDir.resolve(path)));

        ExecuteResponse second = fs.execute(null, largeOutputCommand(0), 60);
        assertEquals(0, second.exitCode());
        assertFalse(path.equals(savedPath(second)), "each invocation needs a unique artifact");
        assertTrue(
                Files.isRegularFile(tempDir.resolve(path)), "older output must not be overwritten");
    }

    @Test
    void previewOutput_usesUtf8BytesWithoutSplittingCodePoints() {
        String complete = "é汉\uD840\uDC00".repeat(12);
        byte[] bytes = complete.getBytes(StandardCharsets.UTF_8);
        Pattern marker = Pattern.compile("\n\n\\.\\.\\. (\\d+) UTF-8 bytes omitted \\.\\.\\.\n\n");
        for (int budget = 0; budget < bytes.length; budget++) {
            String preview = LocalFilesystemWithShell.previewOutput(bytes, budget);
            Matcher match = marker.matcher(preview);
            assertTrue(match.find(), "missing omission marker for budget " + budget);
            String head = preview.substring(0, match.start());
            String tail = preview.substring(match.end());
            int retained = (head + tail).getBytes(StandardCharsets.UTF_8).length;
            assertTrue(retained <= budget, "retained bytes exceed budget " + budget);
            assertEquals(bytes.length - retained, Integer.parseInt(match.group(1)));
            assertTrue(complete.startsWith(head));
            assertTrue(complete.endsWith(tail));
            assertFalse(preview.contains("\uFFFD"), "preview split a UTF-8 code point");
        }
        assertEquals(complete, LocalFilesystemWithShell.previewOutput(bytes, bytes.length));
    }

    @Test
    void execute_truncatesMultibyteOutputByBytesNotStringLength(@TempDir Path tempDir) {
        assumeFalse(isWindows(), "Windows shell output uses the native encoding instead of UTF-8");
        String complete = "é".repeat(40); // 40 Java characters, 80 UTF-8 bytes.
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, 60, null, false);
        assertTrue(fs.write(null, "payload.txt", complete).isSuccess());

        ExecuteResponse response = fs.execute(null, "cat payload.txt", 60);

        assertEquals(0, response.exitCode());
        assertTrue(response.truncated());
        assertTrue(response.output().startsWith("é".repeat(15)));
        assertTrue(response.output().contains("20 UTF-8 bytes omitted"));
        assertEquals(complete, fs.read(null, savedPath(response), 0, 0).fileData().content());

        LocalFilesystemWithShell exactBudget =
                new LocalFilesystemWithShell(tempDir, false, 60, 80, null, false);
        ExecuteResponse exact = exactBudget.execute(null, "cat payload.txt", 60);
        assertFalse(exact.truncated());
        assertEquals(complete, exact.output());
    }

    @Test
    void execute_handlesZeroAndOneByteBudgets(@TempDir Path tempDir) {
        for (int budget : new int[] {0, 1}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, budget, null, false);
            ExecuteResponse response = fs.execute(null, "echo hello", 60);
            assertEquals(0, response.exitCode());
            assertTrue(response.truncated());
            assertTrue(response.output().contains("UTF-8 bytes omitted"));
            ReadResult read = fs.read(null, savedPath(response), 0, 0);
            assertTrue(read.isSuccess(), read.error());
            assertTrue(read.fileData().content().contains("hello"));
        }
    }

    @Test
    void execute_saveFailureDoesNotChangeExitCodeOrRepeatCommand(@TempDir Path tempDir)
            throws IOException {
        for (int exitCode : new int[] {0, 7}) {
            AtomicInteger attempts = new AtomicInteger();
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, 256, null, false) {
                        @Override
                        public WriteResult write(RuntimeContext rc, String path, String content) {
                            attempts.incrementAndGet();
                            assertTrue(path.startsWith("large_tool_results/execute/"));
                            assertEquals(80, content.split("0123456789abcdef", -1).length - 1);
                            return WriteResult.fail("storage unavailable");
                        }
                    };
            String invocationFile = "invocations-" + exitCode + ".txt";
            String command =
                    "echo invocation >> "
                            + invocationFile
                            + (isWindows() ? " & " : "; ")
                            + largeOutputCommand(exitCode);
            ExecuteResponse response = fs.execute(null, command, 60);

            assertEquals(exitCode, response.exitCode());
            assertEquals(exitCode == 0, response.isSuccess());
            assertTrue(response.truncated());
            assertTrue(response.output().contains("fatal-tail"));
            assertTrue(response.output().contains("Full output unavailable"));
            assertFalse(response.output().contains("read_file"));
            assertFalse(response.output().contains("large_tool_results"));
            assertEquals(1, attempts.get());
            assertEquals(1, Files.readAllLines(tempDir.resolve(invocationFile)).size());
        }
    }

    @Test
    void execute_artifactIsReadableThroughOverlayInOwnNamespaceNotShellCwd(@TempDir Path tempDir)
            throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        NamespaceFactory namespace = rc -> List.of("sessions", rc.getSessionId());
        RuntimeContext rt = RuntimeContext.builder().sessionId("own").build();
        RuntimeContext other = RuntimeContext.builder().sessionId("other").build();
        for (LocalFsMode mode : LocalFsMode.values()) {
            LocalFilesystemWithShell upper =
                    new LocalFilesystemWithShell(
                            workspace,
                            mode,
                            PathPolicy.of(project),
                            60,
                            256,
                            null,
                            false,
                            namespace,
                            project);
            upper.namespaceBoundary(true);
            AbstractSandboxFilesystem overlay =
                    new ProjectAwareOverlay(
                            upper,
                            new LocalFilesystem(project),
                            new LocalFilesystem(project),
                            workspace,
                            namespace);

            ExecuteResponse response = overlay.execute(rt, largeOutputCommand(7), 60);
            String path = savedPath(response);
            ReadResult read = overlay.read(rt, path, 0, 0);
            assertEquals(7, response.exitCode());
            assertTrue(read.isSuccess(), read.error());
            assertEquals(80, read.fileData().content().split("0123456789abcdef", -1).length - 1);
            assertTrue(Files.isRegularFile(workspace.resolve("sessions/own").resolve(path)));
            assertFalse(Files.exists(project.resolve(path)), "artifact must not use shellCwd");
            assertFalse(overlay.read(other, path, 0, 0).isSuccess());
        }
    }

    @Test
    void execute_namespaceBoundaryDenialDoesNotChangeOutcome(@TempDir Path tempDir)
            throws IOException {
        assumeFalse(isWindows(), "test uses Unix symlinks without Windows privilege requirements");
        Path own = Files.createDirectories(tempDir.resolve("sessions/own"));
        Path other = Files.createDirectories(tempDir.resolve("sessions/other"));
        Files.createSymbolicLink(own.resolve("large_tool_results"), other);
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(
                        tempDir,
                        LocalFsMode.ROOTED,
                        PathPolicy.empty(),
                        60,
                        256,
                        null,
                        false,
                        rc -> List.of("sessions", rc.getSessionId()),
                        null);
        fs.namespaceBoundary(true);

        ExecuteResponse response =
                fs.execute(
                        RuntimeContext.builder().sessionId("own").build(),
                        largeOutputCommand(0),
                        60);

        assertEquals(0, response.exitCode());
        assertTrue(response.truncated());
        assertTrue(response.output().contains("Full output unavailable"));
        assertFalse(response.output().contains("read_file"));
        try (var entries = Files.list(other)) {
            assertEquals(0, entries.count(), "save must not bypass the namespace boundary");
        }
    }

    @Test
    void execute_timeoutKeepsPartialOutputAndLabelsSavedCapture(@TempDir Path tempDir) {
        assumeFalse(isWindows(), "exec sleep ensures the timed-out process has no surviving child");
        for (int budget : new int[] {4096, 256}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 1, budget, null, false);
            String command =
                    "echo partial-head; i=0; while [ \"$i\" -lt 80 ]; do echo 0123456789abcdef;"
                            + " i=$((i+1)); done; echo partial-tail; echo timeout-diagnostic >&2;"
                            + " exec sleep 30";
            ExecuteResponse response = fs.execute(null, command, 1);

            assertEquals(124, response.exitCode());
            assertTrue(response.timedOut());
            assertFalse(response.isSuccess());
            assertTrue(
                    response.output().contains("Only partial output was captured before timeout"));
            assertTrue(response.output().contains("partial-head"));
            assertTrue(response.output().contains("partial-tail"));
            assertTrue(response.output().contains("[stderr] timeout-diagnostic"));
            assertEquals(budget == 256, response.truncated());
            if (response.truncated()) {
                String saved = fs.read(null, savedPath(response), 0, 0).fileData().content();
                assertTrue(saved.startsWith("Only partial output was captured before timeout"));
                assertEquals(80, saved.split("0123456789abcdef", -1).length - 1);
                assertTrue(saved.contains("[stderr] timeout-diagnostic"));
            }
        }
    }

    private static String savedPath(ExecuteResponse response) {
        Matcher match =
                Pattern.compile("Use `read_file` with path `([^`]+)`").matcher(response.output());
        assertTrue(match.find(), "missing saved-output reference: " + response.output());
        String path = match.group(1);
        assertFalse(Path.of(path).isAbsolute(), "artifact key must remain namespace-relative");
        return path;
    }

    private static String largeOutputCommand(int exitCode) {
        if (isWindows()) {
            return "echo stdout-head & (for /l %i in (1,1,80) do @echo 0123456789abcdef)"
                    + " & echo stdout-tail & echo fatal-tail 1>&2 & exit /b "
                    + exitCode;
        }
        return "echo stdout-head; i=0; while [ \"$i\" -lt 80 ]; do echo 0123456789abcdef;"
                + " i=$((i+1)); done; echo stdout-tail; echo fatal-tail >&2; exit "
                + exitCode;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
