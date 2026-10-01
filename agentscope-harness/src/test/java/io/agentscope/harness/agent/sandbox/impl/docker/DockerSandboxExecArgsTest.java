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
    void windowsStdin_scriptSurvivesNestedDoubleQuotes() {
        // The contract being pinned: the script is identical on both paths — only the
        // transport differs. A caller feeds `buildExecArgs(..., true)` the same command string
        // it writes to stdin, so nested double quotes never cross cmd.exe.
        List<String> args = DockerSandbox.buildExecArgs(CMD, "/workspace", "container-1", true);
        assertEquals(
                -1,
                String.join(" ", args).indexOf("\"$(dirname"),
                "the script body must not appear in the args on Windows");
    }
}
