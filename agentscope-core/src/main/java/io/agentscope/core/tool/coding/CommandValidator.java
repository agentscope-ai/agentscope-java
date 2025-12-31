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

/**
 * Interface for validating shell commands before execution.
 *
 * <p><b>Validation Flow:</b>
 * <ol>
 *   <li><b>Extract Executable:</b> Parse command to extract the executable name</li>
 *   <li><b>Whitelist Check:</b> If whitelist is empty/null, allow all (backward compatible)</li>
 *   <li><b>Multi-Command Detection:</b> Reject if command contains multiple command separators</li>
 *   <li><b>Relative Path Safety:</b> For commands starting with {@code ./} or {@code .\}, verify path doesn't escape current directory</li>
 *   <li><b>Whitelist Validation:</b> Reject if executable not in whitelist</li>
 * </ol>
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
     * <p>Validation checks (in order):
     * <ol>
     *   <li>Extract executable name</li>
     *   <li>If whitelist is null/empty → allow (backward compatible)</li>
     *   <li>Check for multiple command separators → reject if found</li>
     *   <li>Check relative path safety → reject if escapes current directory</li>
     *   <li>Check whitelist → reject if not in whitelist</li>
     * </ol>
     *
     * @param command The command string to validate
     * @param allowedCommands Set of allowed command executables (null or empty means allow all)
     * @return ValidationResult containing the validation outcome
     */
    ValidationResult validate(String command, Set<String> allowedCommands);

    /**
     * Extract the executable name from a command string.
     *
     * <p>Extraction process:
     * <ul>
     *   <li>Remove surrounding quotes (if present)</li>
     *   <li>Extract first token (before space/tab)</li>
     *   <li>Remove directory path (platform-specific)</li>
     *   <li>Remove file extensions (platform-specific)</li>
     * </ul>
     *
     * @param command The command string
     * @return The executable name, or empty string if extraction fails
     */
    String extractExecutable(String command);

    /**
     * Check if the command contains multiple command separators.
     *
     * <p>Uses platform-specific detection:
     * <ul>
     *   <li>Unix: {@code &}, {@code |}, {@code ;}, newline (escape: {@code \})</li>
     *   <li>Windows: {@code &}, {@code |}, newline (escape: {@code ^})</li>
     * </ul>
     * <p>Separators within quotes are ignored.
     *
     * @param command The command string
     * @return true if multiple commands are detected, false otherwise
     */
    boolean containsMultipleCommands(String command);

    /**
     * Validate if a relative path (starting with ./ or .\) stays within the current directory.
     *
     * <p><b>Algorithm:</b> Uses depth-tracking to detect directory traversal:
     * <ol>
     *   <li>Normalize path separators ({@code \} → {@code /})</li>
     *   <li>Remove leading {@code ./}</li>
     *   <li>Split by {@code /} into segments</li>
     *   <li>Track depth: {@code ..} decreases depth, normal dirs increase depth</li>
     *   <li>If depth &lt; 0 at any point → path escapes current directory</li>
     * </ol>
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>{@code ./script.sh} → ✅ allowed (depth: 0→1)</li>
     *   <li>{@code ./subdir/script.sh} → ✅ allowed (depth: 0→1→2)</li>
     *   <li>{@code ./a/b/../c/script.sh} → ✅ allowed (depth: 0→1→2→1→2)</li>
     *   <li>{@code ./../script.sh} → ❌ rejected (depth: 0→-1)</li>
     *   <li>{@code ./../../script.sh} → ❌ rejected (depth: 0→-1→-2)</li>
     * </ul>
     *
     * <p>Supports both Unix ({@code /}) and Windows ({@code \}) path separators.
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
