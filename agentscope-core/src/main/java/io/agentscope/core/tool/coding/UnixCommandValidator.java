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

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command validator for Unix-like systems (Linux, macOS).
 *
 * <p><b>Validation Order:</b>
 * <ol>
 *   <li>Extract executable from command (remove quotes, extract first token, remove extensions)</li>
 *   <li>If no whitelist configured → allow (backward compatible)</li>
 *   <li>Check for multiple command separators → reject if found</li>
 *   <li>Check relative path safety (commands starting with {@code ./}) → reject if escapes current directory</li>
 *   <li>Check whitelist → reject if not in whitelist</li>
 * </ol>
 *
 * <p><b>Multiple Command Detection:</b> Detects Unix-specific separators:
 * {@code &}, {@code &&}, {@code |}, {@code ||}, {@code ;}, newline
 * (ignores separators within quotes or after escape {@code \})
 *
 * <p><b>Executable Extraction:</b>
 * <ul>
 *   <li>Handles quoted commands (single or double quotes)</li>
 *   <li>Extracts first token before space/tab</li>
 *   <li>Removes script extensions: {@code .sh}, {@code .py}, {@code .rb}, {@code .pl}, {@code .bash}, {@code .zsh}</li>
 * </ul>
 */
public class UnixCommandValidator implements CommandValidator {

    private static final Logger logger = LoggerFactory.getLogger(UnixCommandValidator.class);

    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        // Extract and check executable
        String executable = extractExecutable(command);

        // If no whitelist is configured, allow all commands (backward compatible)
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return ValidationResult.allowed(executable);
        }

        // Check for multiple commands
        if (containsMultipleCommands(command)) {
            logger.debug("Command contains multiple command separators: {}", command);
            return ValidationResult.rejected(
                    "Command contains multiple command separators (&, |, ;, newline)",
                    extractExecutable(command));
        }

        // execute executables with relative paths only if they are within the current directory
        if (executable.startsWith("./")) {
            if (isPathWithinCurrentDirectory(executable)) {
                logger.debug(
                        "Command '{}' is safe relative path executable file execution", executable);
                return ValidationResult.allowed(executable);
            } else {
                logger.debug(
                        "Command '{}' is not safe relative path executable file execution",
                        executable);
                return ValidationResult.rejected(
                        "Command '" + executable + "' escapes current directory", executable);
            }
        }

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
            String executable;

            // Handle quoted commands: 'command' or "command"
            if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
                char quote = trimmed.charAt(0);
                int endQuote = trimmed.indexOf(quote, 1);
                if (endQuote > 0) {
                    executable = trimmed.substring(1, endQuote);
                } else {
                    // No closing quote, treat as unquoted
                    executable = extractFirstToken(trimmed);
                }
            } else {
                // Extract first token
                executable = extractFirstToken(trimmed);
            }

            // Remove common script extensions if present (.sh, .py, .rb, .pl, etc.)
            // This ensures consistency with whitelist matching
            if (executable.endsWith(".sh")
                    || executable.endsWith(".py")
                    || executable.endsWith(".rb")
                    || executable.endsWith(".pl")
                    || executable.endsWith(".bash")
                    || executable.endsWith(".zsh")) {
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

    /**
     * Extract the first token from the command string.
     *
     * @param command the command string
     * @return the first token
     */
    private String extractFirstToken(String command) {
        int spaceIndex = command.indexOf(' ');
        int tabIndex = command.indexOf('\t');
        int splitIndex = -1;

        if (spaceIndex >= 0 && tabIndex >= 0) {
            splitIndex = Math.min(spaceIndex, tabIndex);
        } else if (spaceIndex >= 0) {
            splitIndex = spaceIndex;
        } else if (tabIndex >= 0) {
            splitIndex = tabIndex;
        }

        return splitIndex > 0 ? command.substring(0, splitIndex) : command;
    }

    /**
     * Check if the command contains multiple command separators outside of quotes.
     *
     * <p>Detects: &amp;, |, ;, newline (ignores separators within quotes or after escape \)
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
