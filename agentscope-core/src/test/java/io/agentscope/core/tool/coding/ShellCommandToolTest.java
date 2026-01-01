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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for ShellCommandTool.
 */
@Tag("unit")
@DisplayName("ShellCommandTool")
class ShellCommandToolTest {

    private ShellCommandTool tool;

    @BeforeEach
    void setUp() {
        tool = new ShellCommandTool();
    }

    private String extractText(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(c -> c instanceof TextBlock)
                .map(c -> ((TextBlock) c).getText())
                .findFirst()
                .orElse("");
    }

    @Nested
    @DisplayName("Basic Execution")
    class BasicExecutionTests {

        @Test
        @DisplayName("Should execute simple command on Unix")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void executeSimpleCommandUnix() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("echo 'Hello'", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>0</returncode>"));
                                assertTrue(text.contains("Hello"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should execute simple command on Windows")
        @EnabledOnOs(OS.WINDOWS)
        void executeSimpleCommandWindows() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("echo Hello", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>0</returncode>"));
                                assertTrue(text.contains("Hello"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should capture stdout")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void captureStdout() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("echo 'test output'", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<stdout>"));
                                assertTrue(text.contains("test output"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should capture stderr")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void captureStderr() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("ls /nonexistent", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<stderr>"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Whitelist Management")
    class WhitelistManagementTests {

        @Test
        @DisplayName("Should return unmodifiable view from getAllowedCommands")
        void shouldReturnUnmodifiableViewFromGetAllowedCommands() {
            Set<String> initial = new HashSet<>();
            initial.add("ls");
            ShellCommandTool tool = new ShellCommandTool(initial);

            Set<String> commands = tool.getAllowedCommands();

            // Should throw UnsupportedOperationException when trying to modify
            assertThrows(UnsupportedOperationException.class, () -> commands.add("pwd"));
            assertThrows(UnsupportedOperationException.class, () -> commands.remove("ls"));
            assertThrows(UnsupportedOperationException.class, commands::clear);
        }

        @Test
        @DisplayName("Should add command to whitelist and detect duplicates")
        void shouldAddCommandAndDetectDuplicates() {
            ShellCommandTool tool = new ShellCommandTool();

            assertTrue(tool.addAllowedCommand("ls"));
            assertFalse(tool.addAllowedCommand("ls")); // Already exists
            assertTrue(tool.isCommandAllowed("ls"));
        }

        @Test
        @DisplayName("Should remove command from whitelist")
        void shouldRemoveCommandFromWhitelist() {
            Set<String> initial = new HashSet<>();
            initial.add("ls");
            initial.add("pwd");
            ShellCommandTool tool = new ShellCommandTool(initial);

            assertTrue(tool.removeAllowedCommand("ls"));
            assertFalse(tool.removeAllowedCommand("ls")); // Already removed
            assertFalse(tool.isCommandAllowed("ls"));
            assertTrue(tool.isCommandAllowed("pwd"));
        }

        @Test
        @DisplayName("Should clear all commands from whitelist")
        void shouldClearAllCommandsFromWhitelist() {
            Set<String> initial = new HashSet<>();
            initial.add("ls");
            initial.add("pwd");
            initial.add("cat");
            ShellCommandTool tool = new ShellCommandTool(initial);

            assertEquals(3, tool.getAllowedCommands().size());

            tool.clearAllowedCommands();

            assertEquals(0, tool.getAllowedCommands().size());
            assertFalse(tool.isCommandAllowed("ls"));
        }

        @Test
        @DisplayName("Should create defensive copy and prevent external modifications")
        void shouldCreateDefensiveCopyAndPreventExternalModifications() {
            Set<String> original = new HashSet<>();
            original.add("ls");
            original.add("pwd");

            ShellCommandTool tool = new ShellCommandTool(original);

            // Modify original set after tool creation
            original.add("cat");
            original.remove("ls");

            // Tool's whitelist should not be affected by external changes
            Set<String> toolCommands = tool.getAllowedCommands();
            assertEquals(2, toolCommands.size());
            assertTrue(toolCommands.contains("ls"));
            assertTrue(toolCommands.contains("pwd"));
            assertFalse(toolCommands.contains("cat"));
        }
    }

    @Nested
    @DisplayName("Exit Code Handling")
    class ExitCodeTests {

        @Test
        @DisplayName("Should return 0 for successful command")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void returnZeroForSuccess() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("true", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>0</returncode>"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return non-zero for failed command")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void returnNonZeroForFailure() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("false", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertFalse(text.contains("<returncode>0</returncode>"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Timeout Handling")
    class TimeoutTests {

        @Test
        @DisplayName("Should timeout long-running command")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void timeoutLongCommand() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("sleep 10", 1);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("TimeoutError"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should complete fast command within timeout")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void completeWithinTimeout() {
            Mono<ToolResultBlock> result = tool.executeShellCommand("echo fast", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertFalse(text.contains("TimeoutError"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Whitelist Validation")
    class WhitelistValidationTests {

        @Test
        @DisplayName("Should allow whitelisted command")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void allowWhitelistedCommand() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result = toolWithWhitelist.executeShellCommand("echo test", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>0</returncode>"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject non-whitelisted command")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void rejectNonWhitelistedCommand() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result = toolWithWhitelist.executeShellCommand("ls", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("not in the allowed whitelist"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject multiple commands")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void rejectMultipleCommands() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            whitelist.add("ls");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result =
                    toolWithWhitelist.executeShellCommand("echo test && ls", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("multiple command separators"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should allow dynamic whitelist modification")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void allowDynamicWhitelistModification() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            // Initially reject
            Mono<ToolResultBlock> result1 = toolWithWhitelist.executeShellCommand("ls", 10);
            StepVerifier.create(result1)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("not in the allowed whitelist"));
                            })
                    .verifyComplete();

            // Add to whitelist
            toolWithWhitelist.addAllowedCommand("ls");

            // Now allow
            Mono<ToolResultBlock> result2 = toolWithWhitelist.executeShellCommand("ls", 10);
            StepVerifier.create(result2)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Callback Approval")
    class CallbackApprovalTests {

        @Test
        @DisplayName("Should execute when callback approves")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void executeWhenCallbackApproves() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            Function<String, Boolean> callback = cmd -> true; // Always approve
            ShellCommandTool toolWithCallback = new ShellCommandTool(whitelist, callback);

            Mono<ToolResultBlock> result = toolWithCallback.executeShellCommand("ls", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject when callback denies")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void rejectWhenCallbackDenies() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            Function<String, Boolean> callback = cmd -> false; // Always deny
            ShellCommandTool toolWithCallback = new ShellCommandTool(whitelist, callback);

            Mono<ToolResultBlock> result = toolWithCallback.executeShellCommand("ls", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("rejected by use"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should not invoke callback for whitelisted commands")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void notInvokeCallbackForWhitelisted() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            Function<String, Boolean> callback =
                    cmd -> {
                        callbackInvoked.set(true);
                        return true;
                    };
            ShellCommandTool toolWithCallback = new ShellCommandTool(whitelist, callback);

            Mono<ToolResultBlock> result = toolWithCallback.executeShellCommand("echo test", 10);

            StepVerifier.create(result).assertNext(block -> assertNotNull(block)).verifyComplete();

            assertFalse(callbackInvoked.get());
        }
    }

    @Nested
    @DisplayName("Security Tests")
    class SecurityTests {

        @Test
        @DisplayName("Should prevent command injection via chaining")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void preventCommandInjectionViaChaining() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result =
                    toolWithWhitelist.executeShellCommand("echo test; rm -rf /", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("multiple command separators"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should prevent command injection without spaces")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void preventCommandInjectionWithoutSpaces() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result =
                    toolWithWhitelist.executeShellCommand("echo test&malicious", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("multiple command separators"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should require quotes for URLs with special characters")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void requireQuotesForUrls() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            // Without quotes - should be rejected
            Mono<ToolResultBlock> result1 =
                    toolWithWhitelist.executeShellCommand("curl http://example.com?a=1&b=2", 10);
            StepVerifier.create(result1)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("multiple command separators"));
                            })
                    .verifyComplete();

            // With quotes - should be allowed
            Mono<ToolResultBlock> result2 =
                    toolWithWhitelist.executeShellCommand(
                            "curl \"http://example.com?a=1&b=2\"", 10);
            StepVerifier.create(result2)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                // May fail due to network, but should not be rejected by validator
                                assertTrue(
                                        text.contains("<returncode>")
                                                || text.contains("Could not resolve host"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Platform-Specific Tests")
    class PlatformSpecificTests {

        @Test
        @DisplayName("Should handle Windows paths in whitelist")
        @EnabledOnOs(OS.WINDOWS)
        void handleWindowsPathsInWhitelist() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("cmd");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result =
                    toolWithWhitelist.executeShellCommand("cmd /c echo test", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle case-insensitive commands on Windows")
        @EnabledOnOs(OS.WINDOWS)
        void handleCaseInsensitiveCommandsOnWindows() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("cmd");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            Mono<ToolResultBlock> result =
                    toolWithWhitelist.executeShellCommand("CMD /c echo test", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should not detect semicolon as separator on Windows")
        @EnabledOnOs(OS.WINDOWS)
        void notDetectSemicolonOnWindows() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            ShellCommandTool toolWithWhitelist = new ShellCommandTool(whitelist);

            // Semicolon is not a separator in Windows cmd.exe
            Mono<ToolResultBlock> result =
                    toolWithWhitelist.executeShellCommand("echo test;more", 10);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                // Should execute, not be rejected
                                assertTrue(text.contains("<returncode>"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("AgentTool Interface Implementation")
    class AgentToolInterfaceTests {

        @Test
        @DisplayName("Should implement getName correctly")
        void testGetName() {
            ShellCommandTool tool = new ShellCommandTool();
            assertEquals("execute_shell_command", tool.getName());
        }

        @Test
        @DisplayName("Should include whitelist in description")
        void testDescriptionWithWhitelist() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("ls");
            whitelist.add("pwd");
            whitelist.add("cat");
            ShellCommandTool tool = new ShellCommandTool(whitelist);

            String description = tool.getDescription();

            assertNotNull(description);
            assertTrue(description.contains("ALLOWED COMMANDS WHITELIST"));
            assertTrue(description.contains("cat"));
            assertTrue(description.contains("ls"));
            assertTrue(description.contains("pwd"));
            assertTrue(description.contains("Only these commands can be executed directly"));
        }

        @Test
        @DisplayName("Should show no whitelist message when empty")
        void testDescriptionWithoutWhitelist() {
            ShellCommandTool tool = new ShellCommandTool();

            String description = tool.getDescription();

            assertNotNull(description);
            assertTrue(description.contains("No whitelist configured"));
            assertTrue(description.contains("all commands require approval"));
        }

        @Test
        @DisplayName("Should return valid parameters schema")
        void testGetParameters() {
            ShellCommandTool tool = new ShellCommandTool();

            Map<String, Object> params = tool.getParameters();

            assertNotNull(params);
            assertEquals("object", params.get("type"));
            assertTrue(params.containsKey("properties"));
            assertTrue(params.containsKey("required"));

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");
            assertTrue(properties.containsKey("command"));
            assertTrue(properties.containsKey("timeout"));
        }

        @Test
        @DisplayName("Should execute via callAsync")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void testCallAsync() {
            ShellCommandTool tool = new ShellCommandTool();

            Map<String, Object> input = new HashMap<>();
            input.put("command", "echo test");
            input.put("timeout", 10);

            ToolCallParam param = ToolCallParam.builder().input(input).build();

            Mono<ToolResultBlock> result = tool.callAsync(param);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>0</returncode>"));
                                assertTrue(text.contains("test"));
                            })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle missing timeout in callAsync")
        @EnabledOnOs({OS.LINUX, OS.MAC})
        void testCallAsyncWithoutTimeout() {
            ShellCommandTool tool = new ShellCommandTool();

            Map<String, Object> input = new HashMap<>();
            input.put("command", "echo test");

            ToolCallParam param = ToolCallParam.builder().input(input).build();

            Mono<ToolResultBlock> result = tool.callAsync(param);

            StepVerifier.create(result)
                    .assertNext(
                            block -> {
                                String text = extractText(block);
                                assertTrue(text.contains("<returncode>0</returncode>"));
                            })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should handle concurrent whitelist modifications")
        void testConcurrentWhitelistModifications() throws InterruptedException {
            ShellCommandTool tool = new ShellCommandTool();
            int threadCount = 10;
            int operationsPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // Create multiple threads that add/remove commands concurrently
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                new Thread(
                                () -> {
                                    try {
                                        for (int j = 0; j < operationsPerThread; j++) {
                                            String command = "cmd" + threadId + "_" + j;
                                            tool.addAllowedCommand(command);
                                            tool.isCommandAllowed(command);
                                            tool.removeAllowedCommand(command);
                                        }
                                        successCount.incrementAndGet();
                                    } finally {
                                        latch.countDown();
                                    }
                                })
                        .start();
            }

            latch.await();
            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("Should handle concurrent reads during description generation")
        void testConcurrentDescriptionGeneration() throws InterruptedException {
            ShellCommandTool tool = new ShellCommandTool();
            tool.addAllowedCommand("ls");
            tool.addAllowedCommand("pwd");

            int threadCount = 20;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // Multiple threads reading description while one thread modifies whitelist
            for (int i = 0; i < threadCount; i++) {
                new Thread(
                                () -> {
                                    try {
                                        for (int j = 0; j < 50; j++) {
                                            String desc = tool.getDescription();
                                            assertNotNull(desc);
                                            assertTrue(desc.contains("Execute a shell command"));
                                        }
                                        successCount.incrementAndGet();
                                    } finally {
                                        latch.countDown();
                                    }
                                })
                        .start();
            }

            // Concurrent modification thread
            new Thread(
                            () -> {
                                for (int i = 0; i < 100; i++) {
                                    tool.addAllowedCommand("cmd" + i);
                                    tool.removeAllowedCommand("cmd" + (i - 1));
                                }
                            })
                    .start();

            latch.await();
            assertEquals(threadCount, successCount.get());
        }
    }
}
