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
 * <p>Implementations of this interface provide platform-specific validation logic
 * to ensure commands are safe to execute based on whitelist and command structure.
 *
 * <h2>Purpose</h2>
 * <p>CommandValidator provides a pluggable validation mechanism for shell commands,
 * allowing different validation strategies for different platforms (Windows, Unix/Linux/macOS)
 * or custom security requirements.
 *
 * <h2>Validation Flow</h2>
 * <ol>
 *   <li>Extract the executable name from the command string</li>
 *   <li>Check if the command contains multiple command separators (e.g., &amp;, |, ;)</li>
 *   <li>Validate against the whitelist (if configured)</li>
 *   <li>Return a ValidationResult indicating whether the command is allowed</li>
 * </ol>
 *
 * <h2>Platform-Specific Implementations</h2>
 * <ul>
 *   <li>{@link UnixCommandValidator} - For Unix/Linux/macOS systems</li>
 *   <li>{@link WindowsCommandValidator} - For Windows systems</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Using default platform-specific validator
 * ShellCommandTool tool = new ShellCommandTool(allowedCommands);
 *
 * // Using custom validator
 * CommandValidator customValidator = new CommandValidator() {
 *     public ValidationResult validate(String command, Set<String> allowedCommands) {
 *         String executable = extractExecutable(command);
 *         // Custom validation logic
 *         if (isCommandSafe(executable)) {
 *             return ValidationResult.allowed(executable);
 *         }
 *         return ValidationResult.rejected("Custom security policy violation", executable);
 *     }
 *
 *     public String extractExecutable(String command) {
 *         // Custom extraction logic
 *         return command.split(" ")[0];
 *     }
 *
 *     public boolean containsMultipleCommands(String command) {
 *         // Custom multiple command detection
 *         return command.contains("&&") || command.contains(";");
 *     }
 * };
 *
 * ShellCommandTool tool = new ShellCommandTool(allowedCommands, callback, customValidator);
 * }</pre>
 *
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>Always validate against a whitelist in production environments</li>
 *   <li>Be aware of platform-specific command separators and escaping rules</li>
 *   <li>Consider that simple pattern matching may have false positives (e.g., URLs with &amp;)</li>
 *   <li>Implement additional security layers (sandboxing, containerization) when possible</li>
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
