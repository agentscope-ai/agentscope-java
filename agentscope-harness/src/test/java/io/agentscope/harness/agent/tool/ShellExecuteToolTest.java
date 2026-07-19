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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShellExecuteToolTest {

    @Test
    void commandWithWorkingDirectory_usesCmdCompatibleQuotingOnWindows() {
        assertEquals(
                "cd /d \"workspace dir\" && dir",
                ShellExecuteTool.commandWithWorkingDirectory("workspace dir", "dir", true));
    }

    @Test
    void commandWithWorkingDirectory_preservesShellSafeQuotingOnUnix() {
        assertEquals(
                "cd 'workspace dir' && ls",
                ShellExecuteTool.commandWithWorkingDirectory("workspace dir", "ls", false));
    }
}
