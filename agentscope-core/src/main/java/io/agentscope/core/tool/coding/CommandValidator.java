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

/**
 * Interface for validating shell commands before execution.
 *
 * <p>Provides platform-specific validation logic to ensure commands are safe to execute
 * based on whitelist and command structure. Implementations detect multiple command
 * separators (e.g., &amp;, |, ;) and validate executables against an allowed list.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link UnixCommandValidator} - For Unix/Linux/macOS systems</li>
 *   <li>{@link WindowsCommandValidator} - For Windows systems</li>
 * </ul>
 *
 * @see UnixCommandValidator
 * @see WindowsCommandValidator
 * @see ShellCommandTool
 */
public interface CommandValidator {

    /**
     * Validate if a command is allowed to execute.
     *
     * @param command The command string to validate
     * @param allowedCommands Set of allowed command executables (null or empty means allow all)
     * @return ValidationResult containing the validation outcome
     */
    ValidationResult validate(String command, Set<String> allowedCommands);

    /**
     * Extract the executable name from a command string.
     *
     * @param command The command string
     * @return The executable name, or empty string if extraction fails
     */
    String extractExecutable(String command);

    /**
     * Check if the command contains multiple command separators.
     *
     * @param command The command string
     * @return true if multiple commands are detected, false otherwise
     */
    boolean containsMultipleCommands(String command);

    /**
     * Validate if a relative path (starting with ./ or .\) stays within the current directory.
     *
     * <p>This method normalizes the path by processing ".." segments and ensures
     * the final path does not escape the current directory. It supports both Unix-style (/)
     * and Windows-style (\) path separators.
     *
     * <p>Examples:
     * <ul>
     *   <li>./script.sh → true (within current dir)</li>
     *   <li>.\script.bat → true (within current dir, Windows)</li>
     *   <li>./subdir/script.sh → true (within current dir)</li>
     *   <li>./subdir/../script.sh → true (resolves to ./script.sh)</li>
     *   <li>./../script.sh → false (escapes to parent dir)</li>
     *   <li>.\..\..\script.bat → false (escapes to grandparent dir, Windows)</li>
     * </ul>
     *
     * @param path The path to validate
     * @return true if the path stays within current directory, false if it escapes
     */
    default boolean isPathWithinCurrentDirectory(String path) {
        // Normalize path: replace all backslashes with forward slashes
        String normalizedPath = path.replace('\\', '/');

        // Remove leading ./
        normalizedPath = normalizedPath.substring(2);

        // Split by / and process each segment
        String[] segments = normalizedPath.split("/");
        int depth = 0;

        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                // Skip empty segments and current directory references
                continue;
            } else if (segment.equals("..")) {
                // Go up one level
                depth--;
                // If depth becomes negative, we've escaped the current directory
                if (depth < 0) {
                    return false;
                }
            } else {
                // Regular directory or file name, go down one level
                depth++;
            }
        }

        // If we end up at depth >= 0, we're still within or at current directory
        return depth >= 0;
    }

    /**
     * Result of command validation.
     */
    class ValidationResult {
        private final boolean allowed;
        private final String reason;
        private final String executable;

        public ValidationResult(boolean allowed, String reason, String executable) {
            this.allowed = allowed;
            this.reason = reason;
            this.executable = executable;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }

        public String getExecutable() {
            return executable;
        }

        public static ValidationResult allowed(String executable) {
            return new ValidationResult(true, "Command is in whitelist", executable);
        }

        public static ValidationResult rejected(String reason, String executable) {
            return new ValidationResult(false, reason, executable);
        }
    }
}
