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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellCommandToolTest {

    @TempDir Path tempDir;

    @Test
    void defaultConstructorRejectsCommands() {
        assertTrue(execute(new ShellCommandTool(), "echo marker").contains("SecurityError"));
    }

    @Test
    void whitelistedCommandExecutesWithoutApproval() {
        ShellCommandTool tool =
                new ShellCommandTool(
                        Set.of("echo"),
                        command -> {
                            throw new AssertionError(
                                    "A literal whitelisted command needs no approval");
                        });

        String result = execute(tool, "echo marker");

        assertTrue(result.contains("<returncode>0</returncode>"), result);
        assertTrue(result.contains("marker"), result);
    }

    @Test
    void redirectionWithoutCallbackCannotWriteFile() {
        ShellCommandTool tool = new ShellCommandTool(tempDir.toString(), Set.of("echo"), null);

        assertTrue(execute(tool, "echo marker > marker.txt").contains("SecurityError"));
        assertFalse(Files.exists(tempDir.resolve("marker.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void redirectionRequiresApprovalBeforeExecution(boolean approved) throws IOException {
        AtomicReference<String> requested = new AtomicReference<>();
        ShellCommandTool tool =
                new ShellCommandTool(
                        tempDir.toString(),
                        Set.of("echo"),
                        command -> {
                            requested.set(command);
                            return approved;
                        });
        String command = "echo marker > marker.txt";

        String result = execute(tool, command);

        assertEquals(command, requested.get());
        assertEquals(approved, Files.exists(tempDir.resolve("marker.txt")));
        if (approved) {
            assertTrue(result.contains("<returncode>0</returncode>"), result);
            assertEquals("marker", Files.readString(tempDir.resolve("marker.txt")).trim());
        } else {
            assertTrue(result.contains("SecurityError"), result);
        }
    }

    @Test
    void emptyWhitelistCanUseExplicitApproval() {
        AtomicReference<String> requested = new AtomicReference<>();
        ShellCommandTool tool =
                new ShellCommandTool(
                        null,
                        command -> {
                            requested.set(command);
                            return true;
                        });

        String result = execute(tool, "echo marker");

        assertEquals("echo marker", requested.get());
        assertTrue(result.contains("<returncode>0</returncode>"), result);
    }

    @Test
    void clearingWhitelistRequiresApprovalAgain() {
        ShellCommandTool tool = new ShellCommandTool(Set.of("echo"));
        tool.clearAllowedCommands();

        assertTrue(execute(tool, "echo marker").contains("SecurityError"));
    }

    @Test
    void failedApprovalDoesNotExecute() {
        ShellCommandTool nullApproval =
                new ShellCommandTool(tempDir.toString(), Set.of("echo"), command -> null);
        ShellCommandTool failingApproval =
                new ShellCommandTool(
                        tempDir.toString(),
                        Set.of("echo"),
                        command -> {
                            throw new IllegalStateException("Approval unavailable");
                        });

        assertTrue(execute(nullApproval, "echo marker > marker.txt").contains("SecurityError"));
        assertTrue(execute(failingApproval, "echo marker > marker.txt").contains("SecurityError"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsQuoteStrippingCannotBypassApproval() {
        ShellCommandTool tool = new ShellCommandTool(tempDir.toString(), Set.of("echo"), null);

        String result = execute(tool, "\"echo\" \"literal & echo marker > marker.txt\"");

        assertTrue(result.contains("SecurityError"), result);
        assertFalse(Files.exists(tempDir.resolve("marker.txt")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void substitutionCannotWriteFileWithoutApproval() {
        ShellCommandTool tool = new ShellCommandTool(tempDir.toString(), Set.of("echo"), null);

        String result = execute(tool, "echo \"$(touch marker.txt)\"");

        assertTrue(result.contains("SecurityError"), result);
        assertFalse(Files.exists(tempDir.resolve("marker.txt")));
    }

    private static String execute(ShellCommandTool tool, String command) {
        ToolResultBlock result = tool.executeShellCommand(command, 5).block(Duration.ofSeconds(10));
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
