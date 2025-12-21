/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.coding;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for ShellCommandTool.
 *
 * <p>Tests shell command execution functionality including stdout/stderr capture,
 * return codes, and timeout handling.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("ShellCommandTool Unit Tests")
class ShellCommandToolTest {

    private ShellCommandTool shellCommandTool;

    @BeforeEach
    void setUp() {
        shellCommandTool = new ShellCommandTool();
    }

    @Test
    @DisplayName("Should execute simple command successfully")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_SimpleCommand_Unix() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Hello, World!'", 10);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Hello, World!"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should execute simple command successfully on Windows")
    @EnabledOnOs(OS.WINDOWS)
    void testExecuteShellCommand_SimpleCommand_Windows() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo Hello, World!", 10);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Hello") || text.contains("World"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should capture stdout correctly")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_CaptureStdout() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Test output'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("<stdout>"));
                            assertTrue(text.contains("Test output"));
                            assertTrue(text.contains("</stdout>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should capture stderr correctly")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_CaptureStderr() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Error message' >&2", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("<stderr>"));
                            assertTrue(text.contains("Error message"));
                            assertTrue(text.contains("</stderr>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return non-zero exit code for failed command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_NonZeroExitCode() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("exit 42", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>42</returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command not found")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_CommandNotFound() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("nonexistent_command_xyz123", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Should have non-zero return code
                            assertTrue(!text.contains("<returncode>0</returncode>"));
                            // Should contain error in stderr
                            assertTrue(
                                    text.contains("<stderr>")
                                            && (text.contains("not found")
                                                    || text.contains("command not found")));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle timeout correctly")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_Timeout() {
        // Command that sleeps for 10 seconds but timeout is 2 seconds
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("sleep 10", 2);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Should timeout with return code -1 and error message
                            assertTrue(
                                    text.contains("<returncode>-1</returncode>")
                                            || text.contains("TimeoutError"));
                            // Should contain either TimeoutError or timeout message
                            assertTrue(
                                    text.contains("TimeoutError")
                                            || text.contains("timeout")
                                            || text.contains("exceeded"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should use default timeout when not specified")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_DefaultTimeout() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Default timeout'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Default timeout"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should execute multiple commands in sequence")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_MultipleCommands() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'First' && echo 'Second'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("First"));
                            assertTrue(text.contains("Second"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_EmptyCommand() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Should complete without error (empty command returns 0 on most
                            // shells)
                            assertTrue(text.contains("<returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with special characters")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_SpecialCharacters() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Special: !@#$%^&*()'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Special"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multiline output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_MultilineOutput() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("printf 'Line1\\nLine2\\nLine3'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Line1"));
                            assertTrue(text.contains("Line2"));
                            assertTrue(text.contains("Line3"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle commands with pipes")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_Pipes() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Hello World' | grep 'World'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("World"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle directory listing command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_DirectoryListing() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("ls -la /tmp", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("<stdout>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should format output with all three tags")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_OutputFormat() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("echo 'Test'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Verify all three tags are present
                            assertTrue(text.contains("<returncode>"));
                            assertTrue(text.contains("</returncode>"));
                            assertTrue(text.contains("<stdout>"));
                            assertTrue(text.contains("</stdout>"));
                            assertTrue(text.contains("<stderr>"));
                            assertTrue(text.contains("</stderr>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with environment variables")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_EnvironmentVariables() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("TEST_VAR=hello && echo $TEST_VAR", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>"));
                        })
                .verifyComplete();
    }

    // ==================== Exception and Edge Case Tests ====================

    @Test
    @DisplayName("Should handle null command gracefully")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_NullCommand() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand(null, null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Should return error result
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("<returncode>-1</returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle negative timeout value")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_NegativeTimeout() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("echo 'Test'", -1);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Should either use default timeout or return error
                            assertTrue(text.contains("<returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle zero timeout value")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_ZeroTimeout() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("echo 'Test'", 0);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Zero timeout should cause immediate timeout
                            assertTrue(
                                    text.contains("TimeoutError")
                                            || text.contains("<returncode>-1</returncode>")
                                            || text.contains("<returncode>0</returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle very long command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_VeryLongCommand() {
        // Create a very long but valid command
        StringBuilder longCommand = new StringBuilder("echo '");
        for (int i = 0; i < 1000; i++) {
            longCommand.append("x");
        }
        longCommand.append("'");

        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(longCommand.toString(), 10);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with large output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_LargeOutput() {
        // Generate large output (1000 lines)
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "for i in {1..1000}; do echo \"Line $i\"; done", 30);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>"));
                            assertTrue(text.contains("Line 1"));
                            assertTrue(text.contains("Line 1000") || text.contains("Line 999"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle both stdout and stderr output simultaneously")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_MixedOutput() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "echo 'stdout message' && echo 'stderr message' >&2", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("stdout message"));
                            assertTrue(text.contains("stderr message"));
                            assertTrue(text.contains("<stdout>"));
                            assertTrue(text.contains("<stderr>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with newline characters")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_NewlineInCommand() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Line 1'; echo 'Line 2'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Line 1"));
                            assertTrue(text.contains("Line 2"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with unicode characters")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_UnicodeCharacters() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo '你好世界 🌍 Привет'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(
                                    text.contains("你好世界")
                                            || text.contains("Hello")
                                            || text.contains("<stdout>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with quotes and escapes")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_QuotesAndEscapes() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo \"It's a \\\"test\\\"\"", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command that exits with various codes")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_VariousExitCodes() {
        for (int exitCode : new int[] {0, 1, 2, 127, 255}) {
            Mono<ToolResultBlock> result =
                    shellCommandTool.executeShellCommand("exit " + exitCode, null);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                assertNotNull(block);
                                String text = extractText(block);
                                assertTrue(
                                        text.contains("<returncode>" + exitCode + "</returncode>"));
                            })
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("Should handle command with infinite loop that gets timeout")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_InfiniteLoop() {
        // Use a sleep loop that will be terminated by timeout
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("while true; do sleep 1; done", 2);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            // Should timeout with return code -1 and error message
                            assertTrue(
                                    text.contains("<returncode>-1</returncode>")
                                            || text.contains("TimeoutError"));
                            // Should contain timeout error message
                            assertTrue(
                                    text.contains("TimeoutError")
                                            || text.contains("timeout")
                                            || text.contains("exceeded"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with background process")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_BackgroundProcess() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'foreground' && sleep 0.1 &", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("foreground"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with conditional logic")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_ConditionalLogic() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "if [ 1 -eq 1 ]; then echo 'true'; else echo 'false'; fi", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("true"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with file operations")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_FileOperations() {
        // Create temp file, write to it, read from it, delete it
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "tmpfile=$(mktemp) && echo 'test content' > $tmpfile && cat $tmpfile &&"
                                + " rm $tmpfile",
                        null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("test content"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with redirection")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_Redirection() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "echo 'output' > /dev/null && echo 'visible'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("visible"));
                            assertTrue(!text.contains("output") || text.contains("visible"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with very short timeout")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_VeryShortTimeout() {
        // Sleep longer than timeout
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("sleep 10", 1);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>-1</returncode>"));
                            assertTrue(text.contains("TimeoutError"));
                            assertTrue(text.contains("exceeded the timeout of 1"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_EmptyOutput() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("true", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("<stdout></stdout>"));
                            assertTrue(text.contains("<stderr></stderr>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with only stderr output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteShellCommand_OnlyStderr() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'only error' >&2", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("<stdout></stdout>"));
                            assertTrue(text.contains("only error"));
                            assertTrue(text.contains("<stderr>"));
                        })
                .verifyComplete();
    }

    /**
     * Extract text content from ToolResultBlock for assertion.
     */
    private String extractText(ToolResultBlock block) {
        if (block.getOutput() != null && !block.getOutput().isEmpty()) {
            return block.getOutput().get(0).toString();
        }
        return "";
    }
}
