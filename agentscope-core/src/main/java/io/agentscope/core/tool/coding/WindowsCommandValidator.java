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
                    executable = extractFirstToken(trimmed);
                }
            } else {
                // Check if this looks like a path (contains \ or /)
                // If it's a path, extract until we hit a space that's NOT part of the path
                if (trimmed.contains("\\") || trimmed.contains("/")) {
                    // This is a path, need to extract the full path before arguments
                    executable = extractPathFromCommand(trimmed);
                } else {
                    // Simple command without path, extract first token
                    executable = extractFirstToken(trimmed);
                }
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

    @Override
    public boolean containsMultipleCommands(String command) {
        if (command == null) {
            return false;
        }
        return MULTI_COMMAND_PATTERN.matcher(command).find();
    }
}
