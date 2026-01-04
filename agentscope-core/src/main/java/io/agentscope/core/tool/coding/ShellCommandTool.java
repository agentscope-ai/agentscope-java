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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tool for executing shell commands with security validation.
 *
 * <p>Features: command whitelist, user approval callback, multiple command detection,
 * timeout support (default 300s), platform-specific validation.
 *
 * <p><b>Security Warning:</b> {@code new ShellCommandTool()} allows arbitrary command execution.
 * For production, ALWAYS use whitelist: {@code new ShellCommandTool(allowedCommands)}
 * or with callback: {@code new ShellCommandTool(allowedCommands, approvalCallback)}
 *
 * @see CommandValidator
 * @see UnixCommandValidator
 * @see WindowsCommandValidator
 */
public class ShellCommandTool implements AgentTool {

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
        // Use ConcurrentHashMap.newKeySet() for thread-safe, high-performance concurrent access
        // Create defensive copy to prevent external modifications
        if (allowedCommands != null && !allowedCommands.isEmpty()) {
            this.allowedCommands = ConcurrentHashMap.newKeySet(allowedCommands.size());
            this.allowedCommands.addAll(allowedCommands);
        } else {
            this.allowedCommands = ConcurrentHashMap.newKeySet();
        }
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

    // =============================== Allowed Commands Management ===============================

    /**
     * Get an unmodifiable view of the allowed commands.
     * The returned set is thread-safe for reading but cannot be modified directly.
     * Use {@link #addAllowedCommand(String)}, {@link #removeAllowedCommand(String)},
     * or {@link #clearAllowedCommands()} to modify the whitelist.
     *
     * @return An unmodifiable view of the allowed command executables
     */
    public Set<String> getAllowedCommands() {
        return Collections.unmodifiableSet(allowedCommands);
    }

    /**
     * Add a command to the whitelist in a thread-safe manner.
     *
     * @param command The command executable to add
     * @return true if the command was added, false if it was already present
     */
    public boolean addAllowedCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Command cannot be null or empty");
        }
        boolean added = allowedCommands.add(command);
        if (added) {
            logger.debug("Added command to whitelist: {}", command);
        }
        return added;
    }

    /**
     * Remove a command from the whitelist in a thread-safe manner.
     *
     * @param command The command executable to remove
     * @return true if the command was removed, false if it was not present
     */
    public boolean removeAllowedCommand(String command) {
        boolean removed = allowedCommands.remove(command);
        if (removed) {
            logger.debug("Removed command from whitelist: {}", command);
        }
        return removed;
    }

    /**
     * Clear all commands from the whitelist in a thread-safe manner.
     */
    public void clearAllowedCommands() {
        allowedCommands.clear();
        logger.debug("Cleared all commands from whitelist");
    }

    /**
     * Check if a command is in the whitelist.
     *
     * @param command The command executable to check
     * @return true if the command is whitelisted
     */
    public boolean isCommandAllowed(String command) {
        return allowedCommands.contains(command);
    }

    // ========================= AgentTool interface implementation =========================

    @Override
    public String getName() {
        return "execute_shell_command";
    }

    @Override
    public String getDescription() {
        StringBuilder desc = new StringBuilder();
        desc.append("Execute a shell command with security validation and return the result.");

        // Add whitelist information if configured
        if (!allowedCommands.isEmpty()) {
            desc.append(" ALLOWED COMMANDS WHITELIST: [");
            String commandList =
                    new ArrayList<>(allowedCommands).stream().collect(Collectors.joining(", "));
            desc.append(commandList);
            desc.append("]. Only these commands can be executed directly.");
        } else {
            desc.append(" No whitelist configured - all commands require approval.");
        }

        desc.append(" Commands are validated against the whitelist (if configured).");
        desc.append(" Non-whitelisted commands require user approval via callback.");
        desc.append(
                " Multiple command separators (&, |, ;) are detected and blocked for security.");
        desc.append(" Returns output in format:");
        desc.append(" <returncode>code</returncode><stdout>output</stdout><stderr>error</stderr>.");
        desc.append(" If command is rejected, returncode will be -1 with SecurityError in stderr.");

        return desc.toString();
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "command",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The shell command to execute"),
                                "timeout",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "The maximum time (in seconds) allowed for the"
                                                        + " command to run (default: 300)")),
                "required", List.of("command"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String command = (String) input.get("command");
        Integer timeout =
                input.containsKey("timeout") ? ((Number) input.get("timeout")).intValue() : null;

        return executeShellCommand(command, timeout);
    }

    // =============================== Execute shell command ===============================

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
    public Mono<ToolResultBlock> executeShellCommand(String command, Integer timeout) {

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
