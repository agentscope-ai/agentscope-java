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
 * Command validator for Windows systems.
 *
 * <p><b>Validation Order:</b>
 * <ol>
 *   <li>Extract executable from command (remove quotes, handle paths with spaces, remove path, remove extensions, convert to lowercase)</li>
 *   <li>If no whitelist configured → allow (backward compatible)</li>
 *   <li>Check for multiple command separators → reject if found</li>
 *   <li>Check relative path safety (commands starting with {@code .\} or {@code ./}) → reject if escapes current directory</li>
 *   <li>Check whitelist (case-insensitive) → reject if not in whitelist</li>
 * </ol>
 *
 * <p><b>Multiple Command Detection:</b> Detects Windows-specific separators:
 * {@code &}, {@code &&}, {@code |}, {@code ||}, newline
 * (ignores separators within quotes or after escape {@code ^})
 * <br>Note: Semicolon ({@code ;}) is NOT a separator in Windows cmd.exe
 *
 * <p><b>Executable Extraction:</b>
 * <ul>
 *   <li>Handles quoted commands (double quotes)</li>
 *   <li>Handles paths with spaces (e.g., {@code C:\Program Files\app.exe})</li>
 *   <li>Extracts command name without path (handles both {@code \} and {@code /})</li>
 *   <li>Removes extensions: {@code .exe}, {@code .bat}, {@code .cmd} (case-insensitive)</li>
 *   <li>Converts result to lowercase for consistent matching</li>
 * </ul>
 */
public class WindowsCommandValidator implements CommandValidator {

    private static final Logger logger = LoggerFactory.getLogger(WindowsCommandValidator.class);

    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        // Extract and check executable (case-insensitive for Windows)
        String executable = extractExecutable(command);

        // If no whitelist is configured, allow all commands (backward compatible)
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return ValidationResult.allowed(executable);
        }

        // Check for multiple commands
        if (containsMultipleCommands(command)) {
            logger.debug("Command contains multiple command separators: {}", command);
            return ValidationResult.rejected(
                    "Command contains multiple command separators (&, |, newline)",
                    extractExecutable(command));
        }

        if (executable.startsWith(".\\") || executable.startsWith("./")) {
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
                    executable = trimmed.substring(1, endQuote);
                } else {
                    // No closing quote, treat as unquoted
                    executable = extractFirstToken(trimmed);
                }
            } else {
                // Check if this looks like a path (contains \ or /)
                if (trimmed.contains("\\") || trimmed.contains("/")) {
                    // This is a path, need to extract the full path before arguments
                    executable = extractPathFromCommand(trimmed);
                } else {
                    // Simple command without path, extract first token
                    executable = extractFirstToken(trimmed);
                }
            }

            // Remove .exe, .bat, .cmd extensions if present (case-insensitive)
            String lowerExecutable = executable.toLowerCase();
            if (lowerExecutable.endsWith(".exe")
                    || lowerExecutable.endsWith(".bat")
                    || lowerExecutable.endsWith(".cmd")) {
                int dotIndex = lowerExecutable.lastIndexOf('.');
                if (dotIndex > 0) {
                    executable = lowerExecutable.substring(0, dotIndex);
                }
            }

            return executable;
        } catch (Exception e) {
            logger.warn("Failed to parse command '{}': {}", command, e.getMessage());
            return "";
        }
    }

    /**
     * Extract first token (word before space/tab) from command.
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
     * Extract path from command that contains path separators.
     * Handles paths with spaces like "C:\Program Files\app.exe arg1"
     */
    private String extractPathFromCommand(String command) {
        // Look for common executable extensions to find where the path ends
        String lowerCommand = command.toLowerCase();
        int exePos = lowerCommand.indexOf(".exe");
        int batPos = lowerCommand.indexOf(".bat");
        int cmdPos = lowerCommand.indexOf(".cmd");

        // Find the first occurrence of any extension
        int extPos = -1;
        if (exePos >= 0) extPos = exePos;
        if (batPos >= 0 && (extPos < 0 || batPos < extPos)) extPos = batPos;
        if (cmdPos >= 0 && (extPos < 0 || cmdPos < extPos)) extPos = cmdPos;

        if (extPos >= 0) {
            // Found an extension, extract up to the end of the extension
            int endPos = extPos + 4; // .exe, .bat, .cmd are all 4 characters
            // Check if there's a space or end of string after the extension
            if (endPos >= command.length()
                    || command.charAt(endPos) == ' '
                    || command.charAt(endPos) == '\t') {
                return command.substring(0, endPos);
            }
        }

        // No extension found, or extension not followed by space
        // Fall back to extracting first token
        return extractFirstToken(command);
    }

    /**
     * Check if the command contains multiple command separators outside of quotes.
     *
     * <p>Detects: &amp;, |, newline (ignores separators within quotes or after escape ^)
     * <p>Note: Semicolon is NOT a separator in Windows cmd.exe
     *
     * @param command The command to check
     * @return true if multiple commands are detected, false otherwise
     */
    @Override
    public boolean containsMultipleCommands(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // Handle escape sequences (^ is the escape character in cmd.exe)
            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '^') {
                escaped = true;
                continue;
            }

            // Track quote state (Windows cmd.exe primarily uses double quotes)
            if (c == '"') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            // Only check for separators outside quotes
            if (!inDoubleQuote) {
                // Check for command separators (&, |, newline)
                // Note: Semicolon is NOT a separator in Windows cmd.exe
                if (c == '&' || c == '|' || c == '\n') {
                    return true;
                }
            }
        }

        return false;
    }
}
