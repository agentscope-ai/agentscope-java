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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code docker run} command assembled by {@link DockerSandbox}, focused on
 * the container keep-alive entrypoint.
 *
 * <p>Regression guard for issue #2884: the old entrypoint {@code while :; do sleep 3600; done}
 * left PID 1 ({@code sh}) blocked in a foreground {@code sleep} that never observed SIGTERM, so
 * {@code docker stop --time=30} waited the full 30s before SIGKILL and every agent turn paid a
 * ~30s teardown delay. The entrypoint must instead install a SIGTERM trap and wait on a
 * backgrounded sleep so PID 1 exits promptly.
 */
class DockerSandboxRunCommandTest {

    @SuppressWarnings("unchecked")
    private static List<String> buildRunCommand(DockerSandboxState state, String containerName)
            throws Exception {
        DockerSandbox sandbox = new DockerSandbox(state);
        Method m = DockerSandbox.class.getDeclaredMethod("buildDockerRunCommand", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(sandbox, containerName);
    }

    private static DockerSandboxState minimalState() {
        DockerSandboxState state = new DockerSandboxState();
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }

    @Test
    void entrypointInstallsSigtermTrapSoDockerStopReturnsPromptly() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");

        // The entrypoint is the last three tokens: sh -c <script>.
        int n = cmd.size();
        assertEquals("sh", cmd.get(n - 3));
        assertEquals("-c", cmd.get(n - 2));

        String script = cmd.get(n - 1);
        // A SIGTERM trap must be installed so PID 1 (sh) exits on `docker stop`.
        assertTrue(script.contains("trap"), "entrypoint must install a signal trap: " + script);
        assertTrue(script.contains("TERM"), "entrypoint must trap SIGTERM: " + script);
        // The sleep must be backgrounded and waited on, so the trap can interrupt the wait
        // (a foreground sleep would defer the trap until it returns).
        assertTrue(
                script.contains("sleep 3600 &"),
                "sleep must be backgrounded so wait is interruptible: " + script);
        assertTrue(
                script.contains("wait"),
                "entrypoint must wait on the backgrounded sleep: " + script);
    }

    @Test
    void entrypointDoesNotUseForegroundSleepLoop() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");
        String script = cmd.get(cmd.size() - 1);

        // Regression: the old foreground-sleep loop swallowed SIGTERM.
        assertFalse(
                script.equals("while :; do sleep 3600; done"),
                "must not use the SIGTERM-ignoring foreground sleep loop");
    }

    @Test
    void entrypointUsesOnlyPortableShellPrimitives() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");
        String script = cmd.get(cmd.size() - 1);

        // `sleep infinity` is a GNU coreutils extension rejected by BusyBox/Alpine, which would
        // prevent the container from starting on minimal images. The entrypoint must avoid it.
        assertFalse(
                script.contains("sleep infinity"),
                "entrypoint must not depend on non-portable `sleep infinity`: " + script);
    }

    @Test
    void runCommandStartsWithDetachedNamedRun() throws Exception {
        List<String> cmd = buildRunCommand(minimalState(), "agentscope-sandbox-test");

        assertEquals(
                List.of("docker", "run", "-d", "--name", "agentscope-sandbox-test"),
                cmd.subList(0, 5));
        // Image precedes the entrypoint tokens.
        assertEquals("ubuntu:22.04", cmd.get(cmd.size() - 4));
    }
}
