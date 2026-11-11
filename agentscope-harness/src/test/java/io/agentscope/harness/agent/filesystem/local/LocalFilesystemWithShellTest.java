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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
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
    }

    @Test
    void execute_keepsPathVariableWhenCustomEnvReplacesInheritedEnvironment(@TempDir Path dir) {
        // env replaces the inherited environment; dropping PATH with it left the shell unable to
        // resolve anything, so a command that needs the environment failed where an empty one
        // worked (#1396).
        boolean windows = isWindows();
        String command = windows ? "echo %PATH%" : "echo $PATH";

        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(
                        dir,
                        LocalFsMode.UNRESTRICTED,
                        null,
                        60,
                        100_000,
                        Map.of("AGENTSCOPE_TEST_VAR", "1"),
                        false,
                        null,
                        null,
                        null);

        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), resp.output());
        String path = resp.output().trim();
        assertFalse(path.isEmpty(), "PATH reached the child empty: " + resp.output());
        if (windows) {
            assertFalse(path.contains("%PATH%"), "PATH was not expanded: " + resp.output());
        }
    }

    @Test
    void execute_usesTheShellThatWasConfigured(@TempDir Path dir) {
        // sh leaves BASH_VERSION empty and cmd.exe prints it literally; only a version number
        // proves bash ran.
        assumeTrue(canResolve("bash"), "bash is not installed on this host");

        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(
                        dir,
                        LocalFsMode.UNRESTRICTED,
                        null,
                        60,
                        100_000,
                        null,
                        false,
                        null,
                        null,
                        "bash");

        ExecuteResponse resp = fs.execute(null, "echo \"$BASH_VERSION\"", 60);

        assertEquals(0, resp.exitCode(), resp.output());
        assertTrue(
                resp.output().trim().matches("\\d+\\.\\d+.*"),
                "the command did not run under bash: " + resp.output());
    }

    @Test
    void execute_letsTheCallerOverrideABootstrapVariable(@TempDir Path dir) {
        // The bootstrap set restores PATH; a caller that names PATH still wins.
        String marker = dir.resolve("custom-bin").toString();
        String command = isWindows() ? "echo %PATH%" : "echo $PATH";

        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(
                        dir,
                        LocalFsMode.UNRESTRICTED,
                        null,
                        60,
                        100_000,
                        Map.of("PATH", marker),
                        false,
                        null,
                        null,
                        null);

        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), resp.output());
        assertTrue(resp.output().contains("custom-bin"), resp.output());
    }

    @Test
    void constructor_rejectsConfiguredShellThatCannotBeFound(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-shell");

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new LocalFilesystemWithShell(
                                        dir,
                                        LocalFsMode.UNRESTRICTED,
                                        null,
                                        60,
                                        100_000,
                                        null,
                                        false,
                                        null,
                                        null,
                                        missing.toString()));

        assertTrue(
                thrown.getMessage().contains(missing.toString()),
                "message should name the path, was: " + thrown.getMessage());
    }

    @Test
    void execute_usesTheSyntaxOfTheConfiguredShell(@TempDir Path dir) {
        // Arithmetic expansion is POSIX-only: cmd.exe would echo the expression unchanged.
        assumeTrue(canResolve("bash"), "bash is not installed on this host");

        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(
                        dir,
                        LocalFsMode.UNRESTRICTED,
                        null,
                        60,
                        100_000,
                        null,
                        false,
                        null,
                        null,
                        "bash");

        ExecuteResponse resp = fs.execute(null, "echo $((6*7))", 60);

        assertEquals(0, resp.exitCode(), resp.output());
        assertTrue(resp.output().contains("42"), resp.output());
    }

    private static boolean canResolve(String shell) {
        try {
            ShellResolver.resolve(shell, System.getenv());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
