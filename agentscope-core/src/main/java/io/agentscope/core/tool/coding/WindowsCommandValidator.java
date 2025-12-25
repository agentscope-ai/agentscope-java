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

import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command validator for Windows systems.
 *
 * <p>This validator detects multiple commands using Windows-specific separators:
 * <ul>
 *   <li>{@code &} - Command separator</li>
 *   <li>{@code &&} - Conditional AND</li>
 *   <li>{@code |} - Pipe</li>
 *   <li>{@code ||} - Conditional OR</li>
 *   <li>Newline - Command separator</li>
 * </ul>
 *
 * <p>Note: Windows does not use semicolon (;) as a command separator in cmd.exe,
 * so it is not included in the pattern.
 */
public class WindowsCommandValidator implements CommandValidator {

    private static final Logger logger = LoggerFactory.getLogger(WindowsCommandValidator.class);

    /**
     * Pattern to detect multiple commands in Windows cmd.exe.
     * Matches: &, |, newline
     * Note: Semicolon is NOT a command separator in Windows cmd.exe
     */
    private static final Pattern MULTI_COMMAND_PATTERN = Pattern.compile("[&|]|\\n");

    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        // If no whitelist is configured, allow all commands (backward compatible)
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return ValidationResult.allowed(extractExecutable(command));
        }

        // Check for multiple commands
        if (containsMultipleCommands(command)) {
            logger.debug("Command contains multiple command separators: {}", command);
            return ValidationResult.rejected(
                    "Command contains multiple command separators (&, |, newline)",
                    extractExecutable(command));
        }

        // Extract and check executable (case-insensitive for Windows)
        String executable = extractExecutable(command);

        // Check if any whitelist entry matches (case-insensitive)
        boolean inWhitelist =
                allowedCommands.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(executable));

        if (inWhitelist) {
            logger.debug("Command '{}' is in whitelist", executable);
            return ValidationResult.allowed(executable);
        } else {
            logger.debug("Command '{}' is NOT in whitelist", executable);
            return ValidationResult.rejected(
                    "Command '" + executable + "' is not in the allowed whitelist", executable);
        }
    }

    @Override
    public String extractExecutable(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "";
        }

        try {
            String trimmed = command.trim();
            String executable;

            // Handle quoted commands: "command" (Windows typically uses double quotes)
            if (trimmed.startsWith("\"")) {
                int endQuote = trimmed.indexOf('"', 1);
                if (endQuote > 0) {
                    // Extract the quoted part
                    executable = trimmed.substring(1, endQuote);
                } else {
                    // No closing quote, treat as unquoted
                    int spaceIndex = trimmed.indexOf(' ');
                    executable = spaceIndex > 0 ? trimmed.substring(0, spaceIndex) : trimmed;
                }
            } else {
                // Extract first word (before space or tab)
                int spaceIndex = trimmed.indexOf(' ');
                int tabIndex = trimmed.indexOf('\t');
                int splitIndex = -1;

                if (spaceIndex >= 0 && tabIndex >= 0) {
                    splitIndex = Math.min(spaceIndex, tabIndex);
                } else if (spaceIndex >= 0) {
                    splitIndex = spaceIndex;
                } else if (tabIndex >= 0) {
                    splitIndex = tabIndex;
                }

                executable = splitIndex > 0 ? trimmed.substring(0, splitIndex) : trimmed;
            }

            // Extract just the command name without path (handle both \ and /)
            int lastBackslash = executable.lastIndexOf('\\');
            int lastForwardSlash = executable.lastIndexOf('/');
            int lastSlash = Math.max(lastBackslash, lastForwardSlash);

            if (lastSlash >= 0) {
                executable = executable.substring(lastSlash + 1);
            }

            // Remove .exe, .bat, .cmd extensions if present (case-insensitive)
            String lowerExecutable = executable.toLowerCase();
            if (lowerExecutable.endsWith(".exe")
                    || lowerExecutable.endsWith(".bat")
                    || lowerExecutable.endsWith(".cmd")) {
                int dotIndex = executable.lastIndexOf('.');
                if (dotIndex > 0) {
                    executable = executable.substring(0, dotIndex);
                }
            }

            return executable;
        } catch (Exception e) {
            logger.warn("Failed to parse command '{}': {}", command, e.getMessage());
            return "";
        }
    }

    @Override
    public boolean containsMultipleCommands(String command) {
        if (command == null) {
            return false;
        }
        return MULTI_COMMAND_PATTERN.matcher(command).find();
    }
}
