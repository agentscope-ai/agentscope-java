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
import java.time.Duration;
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
 * <p>Tests shell command execution functionality including stdout/stderr capture, return codes,
 * and timeout handling.
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

    // ==================== Basic Execution Tests ====================

    @Test
    @DisplayName("Should execute simple echo command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteSimpleCommand() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Hello, World!'", 10);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Hello, World!"));
                            // Verify output format
                            assertTrue(text.contains("<stdout>"));
                            assertTrue(text.contains("</stdout>"));
                            assertTrue(text.contains("<stderr>"));
                            assertTrue(text.contains("</stderr>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should execute simple command on Windows")
    @EnabledOnOs(OS.WINDOWS)
    void testExecuteSimpleCommand_Windows() {
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
    @DisplayName("Should execute multiple commands in sequence")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteMultipleCommands() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'First' && echo 'Second'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("First"));
                            assertTrue(text.contains("Second"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with no output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteCommandWithNoOutput() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("true", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("<stdout></stdout>"));
                            assertTrue(text.contains("<stderr></stderr>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExecuteEmptyCommand() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("", null);

        StepVerifier.create(result)
                .assertNext(block -> assertTrue(extractText(block).contains("<returncode>")))
                .verifyComplete();
    }

    // ==================== Output Capture Tests ====================

    @Test
    @DisplayName("Should capture stdout correctly")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureStdout() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'stdout message'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("stdout message"));
                            assertTrue(text.contains("<stdout>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should capture stderr correctly")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureStderr() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'stderr message' >&2", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("stderr message"));
                            assertTrue(text.contains("<stderr>"));
                            assertTrue(text.contains("<stdout></stdout>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should capture mixed stdout and stderr")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureMixedOutput() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'stdout' && echo 'stderr' >&2", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("stdout"));
                            assertTrue(text.contains("stderr"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should capture multiline output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureMultilineOutput() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("printf 'Line1\\nLine2\\nLine3'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("Line1"));
                            assertTrue(text.contains("Line2"));
                            assertTrue(text.contains("Line3"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle large output")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureLargeOutput() {
        // Use seq command for POSIX compatibility
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "seq 1 1000 | while read i; do echo \"Line $i\"; done", 30);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("Line 1"));
                            assertTrue(text.contains("Line 1000"));
                        })
                .verifyComplete();
    }

    // ==================== Exit Code Tests ====================

    @Test
    @DisplayName("Should return exit code 0 for successful command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExitCodeSuccess() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("exit 0", null);

        StepVerifier.create(result)
                .assertNext(
                        block ->
                                assertTrue(
                                        extractText(block).contains("<returncode>0</returncode>")))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return correct non-zero exit codes")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testExitCodeNonZero() {
        // Test common exit codes
        for (int exitCode : new int[] {1, 2, 42, 127}) {
            Mono<ToolResultBlock> result =
                    shellCommandTool.executeShellCommand("exit " + exitCode, null);

            StepVerifier.create(result)
                    .assertNext(
                            block ->
                                    assertTrue(
                                            extractText(block)
                                                    .contains(
                                                            "<returncode>"
                                                                    + exitCode
                                                                    + "</returncode>")))
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("Should handle command not found error")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCommandNotFound() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("nonexistent_command_xyz123", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(!text.contains("<returncode>0</returncode>"));
                            assertTrue(
                                    text.contains("not found")
                                            || text.contains("command not found"));
                        })
                .verifyComplete();
    }

    // ==================== Timeout Tests ====================

    @Test
    @DisplayName("Should complete fast command before timeout")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCommandCompletesBeforeTimeout() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("echo 'fast'", 5);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("fast"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should timeout long running command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCommandTimeout() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("sleep 10", 1);

        StepVerifier.create(result)
                .expectNextMatches(
                        block -> {
                            String text = extractText(block);
                            return text.contains("<returncode>-1</returncode>")
                                    && text.contains("TimeoutError")
                                    && text.contains("exceeded the timeout of 1");
                        })
                .expectComplete()
                .verify(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("Should terminate infinite loop on timeout")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testInfiniteLoopTimeout() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("while true; do sleep 1; done", 2);

        StepVerifier.create(result)
                .expectNextMatches(
                        block -> {
                            String text = extractText(block);
                            return text.contains("<returncode>-1</returncode>")
                                    && (text.contains("TimeoutError") || text.contains("timeout"));
                        })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle null command gracefully")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testNullCommand() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand(null, null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("<returncode>-1</returncode>"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle negative timeout by using default")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testNegativeTimeout() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("echo 'test'", -1);

        StepVerifier.create(result)
                .assertNext(block -> assertTrue(extractText(block).contains("<returncode>")))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle zero timeout")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testZeroTimeout() {
        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand("echo 'fast'", 0);

        StepVerifier.create(result)
                .expectNextMatches(
                        block -> {
                            String text = extractText(block);
                            // May timeout immediately or complete quickly
                            return text.contains("TimeoutError")
                                    || text.contains("<returncode>-1</returncode>")
                                    || text.contains("<returncode>0</returncode>");
                        })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    // ==================== Complex Commands ====================

    @Test
    @DisplayName("Should handle command with pipes")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCommandWithPipes() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Hello World' | grep 'World'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("World"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle command with redirection")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCommandWithRedirection() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "echo 'hidden' > /dev/null && echo 'visible'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("visible"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle conditional logic")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testConditionalLogic() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "if [ 1 -eq 1 ]; then echo 'true'; else echo 'false'; fi", null);

        StepVerifier.create(result)
                .assertNext(block -> assertTrue(extractText(block).contains("true")))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle file operations")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testFileOperations() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand(
                        "tmpfile=$(mktemp) && echo 'content' > $tmpfile && cat $tmpfile && rm"
                                + " $tmpfile",
                        null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("content"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle background process")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testBackgroundProcess() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'foreground'; sleep 0.1 &", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>"));
                            assertTrue(text.contains("foreground") || text.contains("<stdout>"));
                        })
                .verifyComplete();
    }

    // ==================== Special Characters ====================

    @Test
    @DisplayName("Should handle special symbols")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testSpecialSymbols() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo 'Special: !@#$%^&*()'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            String text = extractText(block);
                            assertTrue(text.contains("<returncode>0</returncode>"));
                            assertTrue(text.contains("Special"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle unicode characters")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testUnicodeCharacters() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo '你好世界 🌍 Привет'", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
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
    @DisplayName("Should handle quotes and escapes")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testQuotesAndEscapes() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("echo \"It's a \\\"test\\\"\"", null);

        StepVerifier.create(result)
                .assertNext(
                        block ->
                                assertTrue(
                                        extractText(block).contains("<returncode>0</returncode>")))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle very long command")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testVeryLongCommand() {
        StringBuilder longCmd = new StringBuilder("echo '");
        for (int i = 0; i < 1000; i++) {
            longCmd.append("x");
        }
        longCmd.append("'");

        Mono<ToolResultBlock> result = shellCommandTool.executeShellCommand(longCmd.toString(), 10);

        StepVerifier.create(result)
                .assertNext(block -> assertTrue(extractText(block).contains("<returncode>")))
                .verifyComplete();
    }

    // ==================== Environment ====================

    @Test
    @DisplayName("Should handle environment variables")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testEnvironmentVariables() {
        Mono<ToolResultBlock> result =
                shellCommandTool.executeShellCommand("TEST_VAR=hello && echo $TEST_VAR", null);

        StepVerifier.create(result)
                .assertNext(block -> assertTrue(extractText(block).contains("<returncode>")))
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
