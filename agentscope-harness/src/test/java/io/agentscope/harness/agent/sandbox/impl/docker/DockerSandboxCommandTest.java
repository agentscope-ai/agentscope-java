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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class DockerSandboxCommandTest {

    @Test
    void execUsesStdinInsteadOfEmbeddingComplexShellProgramInHostArguments() throws Exception {
        String script =
                "mkdir -p \"$(dirname '/workspace/a b/result.txt')\" && "
                        + "printf '%s' \"nested 'quotes' and $HOME\" > '/workspace/a b/result.txt'";

        List<String> command = DockerSandbox.buildExecCommand("container-id", "/workspace");
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        DockerSandbox.writeShellProgram(stdin, script);

        assertEquals(
                List.of("docker", "exec", "-i", "-w", "/workspace", "container-id", "sh", "-s"),
                command);
        assertFalse(command.contains(script));
        assertEquals(script, stdin.toString(StandardCharsets.UTF_8));
    }

    @Test
    void execHostArgumentsStayBoundedForLargeShellPrograms() throws Exception {
        String script = "printf 'x%.0s' $(seq 1 100000)\n#" + "x".repeat(100_000);

        List<String> command = DockerSandbox.buildExecCommand("container-id", "/workspace");
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        DockerSandbox.writeShellProgram(stdin, script);

        assertEquals(8, command.size());
        assertFalse(command.stream().anyMatch(argument -> argument.length() > 100));
        assertEquals(script.getBytes(StandardCharsets.UTF_8).length, stdin.size());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTSCOPE_DOCKER_INTEGRATION", matches = "true")
    void realDockerExecPreservesComplexAndLargeShellPrograms() throws Exception {
        DockerSandboxClient client = new DockerSandboxClient();
        DockerSandboxClientOptions options =
                new DockerSandboxClientOptions().image("ubuntu:24.04").workspaceRoot("/workspace");
        try (Sandbox sandbox = client.create(new WorkspaceSpec(), null, options)) {
            sandbox.start();

            String complex =
                    "mkdir -p \"$(dirname '/workspace/a b/result.txt')\" && printf '%s' 'nested"
                            + " \"quotes\" and $HOME' > '/workspace/a b/result.txt' && cat"
                            + " '/workspace/a b/result.txt'";
            ExecResult complexResult = sandbox.exec(RuntimeContext.empty(), complex, 30);
            assertEquals("nested \"quotes\" and $HOME", complexResult.stdout());

            String large =
                    "#"
                            + "x".repeat(100_000)
                            + "\n"
                            + "printf '%s' 'large-script-ok' > /workspace/large.txt && cat"
                            + " /workspace/large.txt";
            ExecResult largeResult = sandbox.exec(RuntimeContext.empty(), large, 30);
            assertEquals("large-script-ok", largeResult.stdout());
        }
    }
}
