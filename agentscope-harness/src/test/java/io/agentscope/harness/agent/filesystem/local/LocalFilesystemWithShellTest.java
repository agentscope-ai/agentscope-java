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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemWithShellTest {

    @TempDir Path workspace;

    @Test
    void drainsThirtyKilobyteStdoutWithoutPipeDeadlock() {
        assertLargeOutputCompletes(stdoutCommand(3000));
    }

    @Test
    void drainsLargeStdoutWithoutPipeDeadlock() {
        assertLargeOutputCompletes(stdoutCommand(9000));
    }

    @Test
    void drainsLargeStderrWithoutPipeDeadlock() {
        assertLargeOutputCompletes(stderrCommand(9000));
    }

    @Test
    void drainsMixedOutputWithoutPipeDeadlock() {
        assertLargeOutputCompletes(mixedOutputCommand(4500));
    }

    @Test
    void inheritedPipeCannotBlockPastCommandDeadline() {
        long started = System.nanoTime();
        ExecuteResponse response =
                filesystem().execute(RuntimeContext.empty(), inheritedPipeCommand(), 1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(124, response.exitCode(), response::output);
        assertTrue(elapsedMs < 5_000, "timeout cleanup took " + elapsedMs + "ms");
    }

    @Test
    void timeoutTerminatesDescendantBeforeItCanWriteMarker() throws Exception {
        Path marker = workspace.resolve("descendant-survived.txt");
        ExecuteResponse response =
                filesystem()
                        .execute(
                                RuntimeContext.empty(),
                                descendantMarkerCommand(marker.getFileName().toString()),
                                1);

        assertEquals(124, response.exitCode(), response::output);
        TimeUnit.MILLISECONDS.sleep(3_500);
        assertFalse(Files.exists(marker), "descendant survived shell timeout and wrote its marker");
    }

    private void assertLargeOutputCompletes(String command) {
        ExecuteResponse response = filesystem().execute(RuntimeContext.empty(), command, 5);

        assertEquals(0, response.exitCode(), response::output);
        assertTrue(response.truncated(), "large output should retain bounded aggregate output");
        assertTrue(response.output().contains("Output truncated at 4096 bytes"), response::output);
    }

    private LocalFilesystemWithShell filesystem() {
        return new LocalFilesystemWithShell(
                workspace, false, 5, 4096, null, true, ignored -> List.of());
    }

    private static String stdoutCommand(int lines) {
        return isWindows()
                ? "for /L %i in (1,1," + lines + ") do @echo 1234567890"
                : "i=1; while [ $i -le " + lines + " ]; do echo 1234567890; i=$((i+1)); done";
    }

    private static String stderrCommand(int lines) {
        return isWindows()
                ? "for /L %i in (1,1," + lines + ") do @echo 1234567890 1>&2"
                : "i=1; while [ $i -le " + lines + " ]; do echo 1234567890 >&2; i=$((i+1)); done";
    }

    private static String mixedOutputCommand(int lines) {
        return isWindows()
                ? "for /L %i in (1,1,"
                        + lines
                        + ") do @echo out-1234567890 & @echo err-1234567890 1>&2"
                : "i=1; while [ $i -le "
                        + lines
                        + " ]; do echo out-1234567890; echo err-1234567890 >&2; i=$((i+1)); done";
    }

    private static String inheritedPipeCommand() {
        return isWindows() ? "start /b cmd.exe /c \"ping -n 30 127.0.0.1 >nul\"" : "(sleep 30) &";
    }

    private static String descendantMarkerCommand(String marker) {
        return isWindows()
                ? "start /b cmd.exe /c \"ping -n 4 127.0.0.1 >nul & echo survived>"
                        + marker
                        + "\" & ping -n 30 127.0.0.1 >nul"
                : "(sleep 3; echo survived > " + marker + ") & sleep 30";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
