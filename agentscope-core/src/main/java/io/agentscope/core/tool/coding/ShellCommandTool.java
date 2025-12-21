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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for executing shell commands with timeout support.
 *
 * <p>This tool provides the capability to execute shell commands and capture their output,
 * including return code, standard output, and standard error. It supports:
 * <ul>
 *   <li>Executing any shell command</li>
 *   <li>Configurable timeout (default 300 seconds)</li>
 *   <li>Capturing stdout, stderr, and return code</li>
 *   <li>Automatic process termination on timeout</li>
 * </ul>
 *
 * <p><b>Security Warning:</b> This tool executes arbitrary shell commands and should only
 * be used in trusted environments. Consider implementing additional security measures such
 * as command whitelisting or sandboxing for production use.
 */
public class ShellCommandTool {

    private static final Logger logger = LoggerFactory.getLogger(ShellCommandTool.class);
    private static final int DEFAULT_TIMEOUT = 300;

    /**
     * Execute a shell command and return the return code, standard output, and
     * standard error within tags.
     *
     * @param command The shell command to execute
     * @param timeout The maximum time (in seconds) allowed for the command to run (default: 300)
     * @return A ToolResultBlock containing the formatted output with returncode, stdout, and stderr
     */
    @Tool(
            name = "execute_shell_command",
            description =
                    "Execute given command and return the return code, standard output and "
                            + "error within <returncode></returncode>, <stdout></stdout> and "
                            + "<stderr></stderr> tags.")
    public Mono<ToolResultBlock> executeShellCommand(
            @ToolParam(name = "command", description = "The shell command to execute")
                    String command,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "The maximum time (in seconds) allowed for the command to run",
                            required = false)
                    Integer timeout) {

        int actualTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        logger.debug(
                "Executing shell command: '{}' with timeout: {} seconds", command, actualTimeout);

        return Mono.fromCallable(() -> executeCommand(command, actualTimeout))
                .timeout(Duration.ofSeconds(actualTimeout + 5)) // Extra buffer for cleanup
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error executing command '{}': {}", command, e.getMessage(), e);
                            if (e instanceof TimeoutException) {
                                return Mono.just(
                                        formatResult(
                                                -1,
                                                "",
                                                String.format(
                                                        "TimeoutError: The command execution"
                                                                + " exceeded the timeout of %d"
                                                                + " seconds.",
                                                        actualTimeout)));
                            }
                            return Mono.just(formatResult(-1, "", "Error: " + e.getMessage()));
                        });
    }

    /**
     * Execute the command using ProcessBuilder and capture output.
     *
     * @param command The command to execute
     * @param timeoutSeconds The timeout in seconds
     * @return ToolResultBlock with formatted result
     */
    private ToolResultBlock executeCommand(String command, int timeoutSeconds) {
        ProcessBuilder processBuilder;

        // Determine the shell based on the operating system
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            processBuilder = new ProcessBuilder("sh", "-c", command);
        }

        Process process = null;
        try {
            // Start the process
            process = processBuilder.start();

            // Wait for the process to complete with timeout
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                // Timeout occurred
                logger.warn("Command '{}' exceeded timeout of {} seconds", command, timeoutSeconds);

                // Try to capture partial output before terminating
                String stdout = readStream(process.getInputStream());
                String stderr = readStream(process.getErrorStream());

                // Terminate the process
                process.destroyForcibly();

                String timeoutMessage =
                        String.format(
                                "TimeoutError: The command execution exceeded the timeout of %d"
                                        + " seconds.",
                                timeoutSeconds);

                // Append timeout message to stderr
                if (stderr != null && !stderr.isEmpty()) {
                    stderr = stderr + "\n" + timeoutMessage;
                } else {
                    stderr = timeoutMessage;
                }

                return formatResult(-1, stdout != null ? stdout : "", stderr);
            }

            // Process completed normally
            int returnCode = process.exitValue();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            logger.debug("Command '{}' completed with return code: {}", command, returnCode);

            return formatResult(
                    returnCode, stdout != null ? stdout : "", stderr != null ? stderr : "");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Command execution was interrupted: {}", command, e);

            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            return formatResult(-1, "", "Error: Command execution was interrupted");

        } catch (IOException e) {
            logger.error(
                    "IOException while executing command '{}': {}", command, e.getMessage(), e);
            return formatResult(-1, "", "Error: " + e.getMessage());

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Read all content from an input stream.
     *
     * @param inputStream The input stream to read from
     * @return The content as a string
     */
    private String readStream(java.io.InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append("\n");
                }
                output.append(line);
            }
        } catch (IOException e) {
            logger.error("Error reading stream: {}", e.getMessage(), e);
        }
        return output.toString();
    }

    /**
     * Format the execution result with XML-style tags.
     *
     * @param returnCode The process return code
     * @param stdout The standard output
     * @param stderr The standard error
     * @return ToolResultBlock with formatted result
     */
    private ToolResultBlock formatResult(int returnCode, String stdout, String stderr) {
        String formattedOutput =
                String.format(
                        "<returncode>%d</returncode><stdout>%s</stdout><stderr>%s</stderr>",
                        returnCode, stdout, stderr);

        return ToolResultBlock.of(TextBlock.builder().text(formattedOutput).build());
    }
}
