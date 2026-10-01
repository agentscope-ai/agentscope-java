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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Windows nested-quote loss in {@code doExec} (#2924): on Windows the
 * script must travel via stdin ({@code -i} + plain {@code sh}), because {@link ProcessBuilder}
 * funnels arguments through {@code cmd.exe}, which strips the nested double quotes sandbox
 * commands rely on.
 */
class DockerSandboxExecArgsTest {

    private static final String CMD =
            "if [ -e 'test.txt' ]; then echo 'EXISTS'; exit 1; fi; "
                    + "mkdir -p \"$(dirname 'test.txt')\" 2>&1";

    @Test
    void unixPath_usesShDashCWithScriptInline() {
        List<String> args = DockerSandbox.buildExecArgs(CMD, "/workspace", "container-1", false);

        assertEquals(
                List.of("docker", "exec", "-w", "/workspace", "container-1", "sh", "-c", CMD),
                args);
    }

    @Test
    void windowsPath_passesScriptViaStdin() {
        List<String> args = DockerSandbox.buildExecArgs(CMD, "/workspace", "container-1", true);

        // -i + plain sh: the script is fed on stdin, never placed on the Windows command line.
        assertEquals(
                List.of("docker", "exec", "-i", "-w", "/workspace", "container-1", "sh"), args);
        assertFalse(
                args.contains("-c"),
                "no -c on Windows: the script must not travel through cmd.exe");
    }

    @Test
    void windowsStdin_writeScriptTo_writesAndCloses() throws Exception {
        // Behavioral pin of the actual #2924 fix: the seam writes the full script bytes and
        // closes the stream (EOF tells non-interactive sh to run the buffered script).
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream counted =
                new OutputStream() {
                    @Override
                    public void write(int b) {
                        sink.write(b);
                    }

                    @Override
                    public void close() {
                        closed = true;
                    }
                };

        DockerSandbox.writeScriptTo(counted, CMD);

        assertEquals(CMD, sink.toString("UTF-8"), "the script bytes must reach stdin verbatim");
        assertTrue(closed, "stdin must be closed so sh sees EOF");
    }

    private volatile boolean closed;
}
