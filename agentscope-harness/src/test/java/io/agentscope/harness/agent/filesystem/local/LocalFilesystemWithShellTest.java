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

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
}
