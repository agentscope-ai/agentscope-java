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
package io.agentscope.core.util;

/**
 * Utility methods for exception handling.
 */
public final class ExceptionUtils {

    private ExceptionUtils() {
        // Prevent instantiation
    }

    /**
     * Extracts the most informative error message from an exception.
     *
     * <p>This method attempts to retrieve an error message in the following order:
     * <ol>
     *   <li>The exception's own message</li>
     *   <li>The cause's message (if the exception message is empty)</li>
     *   <li>The exception's simple class name (as a fallback)</li>
     * </ol>
     *
     * @param throwable The exception to extract the message from (may be null)
     * @return A non-null error message string
     */
    public static String getErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }

        // Try to get the message from the exception
        String message = throwable.getMessage();
        if (message != null && !message.isEmpty()) {
            return message;
        }

        // If no message, try to get the cause's message
        Throwable cause = throwable.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
            return cause.getMessage();
        }

        // Fall back to the exception class name
        return throwable.getClass().getSimpleName();
    }
}
