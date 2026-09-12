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

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalFilesystemWithShellTest {

    private static final int OUTPUT_LINES = 8192;
    private static final String STDOUT_LINE = "out-" + "0123456789abcdef".repeat(8);
    private static final String STDERR_LINE = "err-" + "fedcba9876543210".repeat(8);

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

    @ParameterizedTest
    @ValueSource(strings = {"stdout", "stderr", "both"})
    void execute_outputLargerThanOsPipeBufferCompletesWithoutDeadlock(
            String streams, @TempDir Path tempDir) {
        // More than 1 MiB per stream, with room to capture both streams without truncation.
        // Alternating writes to both pipes also catches sequential stdout/stderr readers.
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 10, 4 * 1024 * 1024, null, false);
        ExecuteResponse resp = fs.execute(null, largeOutputCommand(streams), 10);

        assertEquals(0, resp.exitCode());
        assertFalse(resp.truncated());
        String stdout = (STDOUT_LINE + "\n").repeat(OUTPUT_LINES);
        String stderr = ("[stderr] " + STDERR_LINE + "\n").repeat(OUTPUT_LINES).stripTrailing();
        String expected =
                switch (streams) {
                    case "stdout" -> stdout;
                    case "stderr" -> stderr;
                    default -> stdout + "\n" + stderr;
                };
        assertEquals(expected, resp.output().replace("\r\n", "\n"));
    }

    @Test
    void execute_outputBeyondCaptureLimitStillDrainsBothPipes(@TempDir Path tempDir) {
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 10, 128, null, false);
        ExecuteResponse resp = fs.execute(null, largeOutputCommand("both"), 10);

        assertEquals(0, resp.exitCode());
        assertTrue(resp.truncated());
        assertEquals(
                STDOUT_LINE.substring(0, 128) + "\n\n... Output truncated at 128 bytes.",
                resp.output());
    }

    private static String largeOutputCommand(String streams) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String stdout = "echo " + STDOUT_LINE;
        String stderr = windows ? "1>&2 echo " + STDERR_LINE : "echo " + STDERR_LINE + " >&2";
        String body =
                switch (streams) {
                    case "stdout" -> stdout;
                    case "stderr" -> stderr;
                    default -> stdout + (windows ? "&" : "; ") + stderr;
                };
        // Shell builtins keep the reproducer independent of Python and external child processes.
        return windows
                ? "for /l %i in (1,1," + OUTPUT_LINES + ") do @(" + body + ")"
                : "i=0; while [ \"$i\" -lt "
                        + OUTPUT_LINES
                        + " ]; do "
                        + body
                        + "; i=$((i+1)); done";
    }
}
