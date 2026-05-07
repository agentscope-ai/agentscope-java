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
package io.agentscope.examples.harness.sandbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerPythonSandboxImageTest {

    @Test
    void runStreamingPrintsCommandOutput() throws Exception {
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8);
                PrintStream stderr = new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8)) {
            DockerPythonSandboxImage.runStreaming(
                    List.of("sh", "-c", "printf 'build-log\\n'; printf 'warn-log\\n' >&2"),
                    5,
                    "failed",
                    stdout,
                    stderr);
        }

        assertTrue(stdoutBuffer.toString(StandardCharsets.UTF_8).contains("build-log"));
        assertTrue(stderrBuffer.toString(StandardCharsets.UTF_8).contains("warn-log"));
    }
}
