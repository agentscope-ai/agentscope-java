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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DockerSandboxExecTest {

    private static final int OUTPUT_LIMIT = 512 * 1024;

    @Test
    void streamsNestedQuotesAndUnicodeThroughStdinInsteadOfWindowsArgv() throws Exception {
        String command =
                "mkdir -p \"$(dirname 'test file.txt')\"; "
                        + "printf '%s\\n' \"nested \\\"quotes\\\" 世界 🚀\"";
        FakeProcess process = FakeProcess.success("done\n", "");
        TestDockerSandbox sandbox = new TestDockerSandbox(state(), process);

        ExecResult result = sandbox.execute(command, 30);

        assertEquals(
                List.of("docker", "exec", "-i", "-w", "/workspace", "container-123", "sh", "-s"),
                sandbox.startedCommand);
        assertFalse(sandbox.startedCommand.contains(command));
        assertArrayEquals(
                DockerSandbox.wrapCommandForStdin(command).getBytes(StandardCharsets.UTF_8),
                process.stdin.toByteArray());
        assertTrue(process.stdin.closed);
        assertEquals("done\n", result.stdout());
        assertEquals("", result.stderr());
        assertFalse(result.truncated());
    }

    @Test
    void wrapsMultilineAndHeredocWithoutChangingTheirBytes() throws Exception {
        String command =
                "python3 - <<'PY'\n" + "print(\"nested 'quotes'\")\n" + "print('多行')\n" + "PY\n";
        FakeProcess process = FakeProcess.success("", "");
        TestDockerSandbox sandbox = new TestDockerSandbox(state(), process);

        sandbox.execute(command, 30);

        String expected = "{\n:\n" + command + "} </dev/null\n";
        assertEquals(expected, process.stdin.toString(StandardCharsets.UTF_8));
        assertTrue(process.stdin.closed);
    }

    @Test
    void preservesNonZeroExitDetails() {
        FakeProcess process = new FakeProcess(true, 7, "partial", "failed");
        TestDockerSandbox sandbox = new TestDockerSandbox(state(), process);

        SandboxException.ExecException error =
                assertThrows(
                        SandboxException.ExecException.class,
                        () -> sandbox.execute("echo broken >&2; exit 7", 30));

        assertEquals(7, error.getExitCode());
        assertEquals("partial", error.getStdout());
        assertEquals("failed", error.getStderr());
        assertTrue(process.stdin.closed);
    }

    @Test
    void destroysProcessAndClosesStdinOnTimeout() {
        FakeProcess process = new FakeProcess(false, 0, "", "");
        TestDockerSandbox sandbox = new TestDockerSandbox(state(), process);

        assertThrows(
                SandboxException.ExecTimeoutException.class, () -> sandbox.execute("sleep 60", 1));

        assertTrue(process.destroyed);
        assertTrue(process.stdin.closed);
    }

    @Test
    void retainsOutputTruncationBehavior() throws Exception {
        byte[] oversized = new byte[OUTPUT_LIMIT + 32];
        java.util.Arrays.fill(oversized, (byte) 'x');
        FakeProcess process = new FakeProcess(true, 0, oversized, new byte[0]);
        TestDockerSandbox sandbox = new TestDockerSandbox(state(), process);

        ExecResult result = sandbox.execute("printf output", 30);

        assertEquals(OUTPUT_LIMIT, result.stdout().length());
        assertTrue(result.truncated());
    }

    private static DockerSandboxState state() {
        DockerSandboxState state = new DockerSandboxState();
        state.setContainerId("container-123");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }

    private static final class TestDockerSandbox extends DockerSandbox {

        private final Process process;
        private List<String> startedCommand;

        private TestDockerSandbox(DockerSandboxState state, Process process) {
            super(state);
            this.process = process;
        }

        @Override
        Process startExecProcess(List<String> command) {
            this.startedCommand = List.copyOf(command);
            return process;
        }

        private ExecResult execute(String command, int timeoutSeconds) throws Exception {
            return doExec(null, command, timeoutSeconds);
        }
    }

    private static final class FakeProcess extends Process {

        private final boolean exits;
        private final int exitCode;
        private final InputStream stdout;
        private final InputStream stderr;
        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private volatile boolean destroyed;

        private FakeProcess(boolean exits, int exitCode, String stdout, String stderr) {
            this(
                    exits,
                    exitCode,
                    stdout.getBytes(StandardCharsets.UTF_8),
                    stderr.getBytes(StandardCharsets.UTF_8));
        }

        private FakeProcess(boolean exits, int exitCode, byte[] stdout, byte[] stderr) {
            this.exits = exits;
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout);
            this.stderr = new ByteArrayInputStream(stderr);
        }

        private static FakeProcess success(String stdout, String stderr) {
            return new FakeProcess(true, 0, stdout, stderr);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return exits;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {

        private volatile boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
