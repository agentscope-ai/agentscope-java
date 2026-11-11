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
package io.agentscope.core.tool.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ShellCommandStdinTest {

    @Test
    void commandReadingStandardInputReceivesEndOfFile() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String command = windows ? "sort" : "exec sort";
        var result =
                new ShellCommandTool()
                        .executeShellCommand(command, 3)
                        .block(Duration.ofSeconds(10));

        assertNotNull(result);
        assertEquals(
                "<returncode>0</returncode><stdout></stdout><stderr></stderr>",
                ((TextBlock) result.getOutput().get(0)).getText());
    }
}
