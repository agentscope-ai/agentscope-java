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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command validator for Unix-like systems (Linux, macOS).
 *
 * <p>This validator detects multiple commands using Unix-specific separators:
 * <ul>
 *   <li>{@code &} - Background execution</li>
 *   <li>{@code &&} - Conditional AND</li>
 *   <li>{@code |} - Pipe</li>
 *   <li>{@code ||} - Conditional OR</li>
 *   <li>{@code ;} - Command separator</li>
 *   <li>Newline - Command separator</li>
 * </ul>
 */
public class UnixCommandValidator implements CommandValidator {

    private static final Logger logger = LoggerFactory.getLogger(UnixCommandValidator.class);

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
                    "Command contains multiple command separators (&, |, ;, newline)",
                    extractExecutable(command));
        }

        // Extract and check executable
        String executable = extractExecutable(command);
        boolean inWhitelist = allowedCommands.contains(executable);

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

            // Handle quoted commands: 'command' or "command"
            if ((trimmed.startsWith("'") && trimmed.contains("' "))
                    || (trimmed.startsWith("\"") && trimmed.contains("\" "))) {
                char quote = trimmed.charAt(0);
                int endQuote = trimmed.indexOf(quote, 1);
                if (endQuote > 0) {
                    trimmed = trimmed.substring(1, endQuote);
                }
            }

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

            String executable = splitIndex > 0 ? trimmed.substring(0, splitIndex) : trimmed;

            // Extract just the command name without path
            if (executable.contains("/")) {
                int lastSlash = executable.lastIndexOf('/');
                executable = executable.substring(lastSlash + 1);
            }

            return executable;
        } catch (Exception e) {
            logger.warn("Failed to parse command '{}': {}", command, e.getMessage());
            return "";
        }
    }

    /**
     * Check if the command contains multiple command separators outside of quotes.
     *
     * <p>This method properly handles quoted strings and only detects separators
     * that are not within single or double quotes.
     *
     * <p>Detected separators: &amp;, |, ;, newline
     *
     * @param command The command to check
     * @return true if multiple commands are detected, false otherwise
     */
    @Override
    public boolean containsMultipleCommands(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // Handle escape sequences
            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            // Track quote state
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            // Only check for separators outside quotes
            if (!inSingleQuote && !inDoubleQuote) {
                // Check for command separators
                if (c == '&' || c == '|' || c == ';' || c == '\n') {
                    return true;
                }
            }
        }

        return false;
    }
}
