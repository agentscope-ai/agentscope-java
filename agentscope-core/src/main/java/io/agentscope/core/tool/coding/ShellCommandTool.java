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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tool for executing shell commands with timeout support and security validation.
 *
 * <p>This tool provides the capability to execute shell commands and capture their output,
 * including return code, standard output, and standard error. It supports:
 * <ul>
 *   <li>Executing shell commands with configurable timeout (default 300 seconds)</li>
 *   <li>Capturing stdout, stderr, and return code</li>
 *   <li>Automatic process termination on timeout</li>
 *   <li>Command whitelist validation - only allow specific commands</li>
 *   <li>User approval callback for non-whitelisted commands</li>
 *   <li>Multiple command detection - prevents command chaining (e.g., cmd1 &amp;&amp; cmd2)</li>
 *   <li>Platform-specific validation - different rules for Windows and Unix/Linux/macOS</li>
 * </ul>
 *
 * <h2>Security Warning</h2>
 * <p><b>CRITICAL:</b> The default no-argument constructor {@code new ShellCommandTool()} creates
 * an unrestricted instance that allows execution of <b>arbitrary shell commands</b>. This poses
 * significant security risks:
 * <ul>
 *   <li><b>Remote Code Execution (RCE):</b> Attackers can execute malicious commands on the host</li>
 *   <li><b>Data Exfiltration:</b> Sensitive data can be accessed and transmitted</li>
 *   <li><b>System Compromise:</b> Full system control may be obtained</li>
 * </ul>
 *
 * <p><b>Production Deployment Requirements:</b>
 * <ul>
 *   <li>ALWAYS use {@code new ShellCommandTool(allowedCommands)} with an explicit whitelist</li>
 *   <li>ALWAYS implement an approval callback for user-facing applications</li>
 *   <li>NEVER expose the unrestricted constructor to untrusted users or LLM prompts</li>
 *   <li>Consider additional security layers (sandboxing, containerization, least privilege)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // ✅ SECURE: Whitelist mode (production)
 * Set<String> allowedCommands = Set.of("ls", "cat", "grep");
 * ShellCommandTool tool = new ShellCommandTool(allowedCommands);
 *
 * // ✅ MORE SECURE: Whitelist + approval callback
 * Function<String, Boolean> callback = cmd -> askUserForApproval(cmd);
 * ShellCommandTool tool = new ShellCommandTool(allowedCommands, callback);
 *
 * // ⚠️ DANGEROUS: Unrestricted mode (local development only)
 * // WARNING: Never use in production or user-facing applications
 * ShellCommandTool tool = new ShellCommandTool();
 * }</pre>
 *
 * @see CommandValidator
 * @see UnixCommandValidator
 * @see WindowsCommandValidator
 */
public class ShellCommandTool {

    private static final Logger logger = LoggerFactory.getLogger(ShellCommandTool.class);
    private static final int DEFAULT_TIMEOUT = 300;

    private final Set<String> allowedCommands;
    private final Function<String, Boolean> approvalCallback;
    private final CommandValidator commandValidator;

    public ShellCommandTool() {
        this(null, null);
    }

    public ShellCommandTool(Set<String> allowedCommands) {
        this(allowedCommands, null);
    }

    /**
     * Constructor with command whitelist and approval callback.
     *
     * @param allowedCommands Set of allowed command executables
     * @param approvalCallback Callback function to request user approval
     */
    public ShellCommandTool(
            Set<String> allowedCommands, Function<String, Boolean> approvalCallback) {
        this(allowedCommands, approvalCallback, createDefaultValidator());
    }

    /**
     * Constructor with command whitelist, approval callback, and custom validator.
     *
     * @param allowedCommands Set of allowed command executables (null to allow all commands)
     * @param approvalCallback Callback function to request user approval
     * @param commandValidator Custom command validator
     */
    public ShellCommandTool(
            Set<String> allowedCommands,
            Function<String, Boolean> approvalCallback,
            CommandValidator commandValidator) {
        // If allowedCommands is null, create an empty HashSet (which means allow all by default)
        // If provided, use it directly
        this.allowedCommands = allowedCommands != null ? allowedCommands : new HashSet<>();
        this.approvalCallback = approvalCallback;
        this.commandValidator =
                commandValidator != null ? commandValidator : createDefaultValidator();
    }

    /**
     * Create a default command validator based on the operating system.
     *
     * @return Platform-specific command validator
     */
    private static CommandValidator createDefaultValidator() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new WindowsCommandValidator();
        } else {
            return new UnixCommandValidator();
        }
    }

    /**
     * Get the set of allowed commands.
     * The returned set can be modified to dynamically update the whitelist.
     *
     * @return The mutable set of allowed command executables
     */
    public Set<String> getAllowedCommands() {
        return allowedCommands;
    }

    /**
     * Execute a shell command and return the return code, standard output, and
     * standard error within tags.
     *
     * <p>Security features:
     * <ul>
     *   <li>Command whitelist validation - only whitelisted commands execute directly</li>
     *   <li>Multiple command detection - prevents command chaining attacks (&amp;, |, ;)</li>
     *   <li>User approval callback - requests permission for non-whitelisted commands</li>
     *   <li>Platform-specific validation - different rules for Windows and Unix/Linux/macOS</li>
     * </ul>
     *
     * @param command The shell command to execute
     * @param timeout The maximum time (in seconds) allowed for the command to run (default: 300)
     * @return A ToolResultBlock containing the formatted output with returncode, stdout, and stderr
     */
    @Tool(
            name = "execute_shell_command",
            description =
                    "Execute a shell command with security validation and return the result."
                        + " Commands are validated against a whitelist (if configured)."
                        + " Non-whitelisted commands require user approval via callback. Multiple"
                        + " command separators (&, |, ;) are detected and blocked for security."
                        + " Returns output in format:"
                        + " <returncode>code</returncode><stdout>output</stdout><stderr>error</stderr>."
                        + " If command is rejected, returncode will be -1 with SecurityError in"
                        + " stderr.")
    public Mono<ToolResultBlock> executeShellCommand(
            @ToolParam(name = "command", description = "The shell command to execute")
                    String command,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "The maximum time (in seconds) allowed for the command to run",
                            required = false)
                    Integer timeout) {

        int actualTimeout = timeout != null && timeout > 0 ? timeout : DEFAULT_TIMEOUT;
        logger.debug(
                "Executing shell command: '{}' with timeout: {} seconds", command, actualTimeout);

        // Validate command before execution
        CommandValidator.ValidationResult validationResult =
                commandValidator.validate(command, allowedCommands);

        if (!validationResult.isAllowed()) {
            logger.info(
                    "Command '{}' validation failed: {}", command, validationResult.getReason());

            // Request user approval
            if (!requestUserApproval(command)) {
                String errorMsg =
                        approvalCallback == null
                                ? "SecurityError: "
                                        + validationResult.getReason()
                                        + " and no approval callback is configured."
                                : "SecurityError: Command execution was rejected by user. Reason: "
                                        + validationResult.getReason();
                logger.warn("Command '{}' execution rejected: {}", command, errorMsg);
                return Mono.just(formatResult(-1, "", errorMsg));
            }

            logger.info("Command '{}' approved by user, proceeding with execution", command);
        }

        return Mono.fromCallable(() -> executeCommand(command, actualTimeout))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(actualTimeout + 2))
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
            long startTime = System.currentTimeMillis();
            logger.debug("Starting command execution: {}", command);

            // Start the process
            process = processBuilder.start();

            // Wait for the process to complete with timeout
            logger.debug("Waiting for process with timeout: {} seconds", timeoutSeconds);
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long waitElapsed = System.currentTimeMillis() - startTime;
            logger.debug(
                    "process.waitFor returned: completed={}, elapsed={}ms", completed, waitElapsed);

            if (!completed) {
                // Timeout occurred
                logger.warn(
                        "Command '{}' exceeded timeout of {} seconds (actual wait: {}ms)",
                        command,
                        timeoutSeconds,
                        waitElapsed);

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
            // Clean up process resources
            if (process != null && process.isAlive()) {
                // Destroy the process if still alive
                // Note: Streams are already closed by try-with-resources in readStream()
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

    /**
     * Request user approval for command execution via callback.
     *
     * @param command The command to approve
     * @return true if approved, false otherwise
     */
    private boolean requestUserApproval(String command) {
        if (approvalCallback == null) {
            logger.warn("No approval callback configured, rejecting command: {}", command);
            return false;
        }

        try {
            Boolean approved = approvalCallback.apply(command);
            if (approved != null && approved) {
                logger.info("User approved command execution: {}", command);
                return true;
            } else {
                logger.info("User rejected command execution: {}", command);
                return false;
            }
        } catch (Exception e) {
            logger.error(
                    "Error during approval callback for command '{}': {}",
                    command,
                    e.getMessage(),
                    e);
            return false;
        }
    }
}
