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
