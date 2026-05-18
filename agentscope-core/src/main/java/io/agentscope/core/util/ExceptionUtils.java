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

import org.jspecify.annotations.Nullable;

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

    /**
     * Retrieves the root cause of an exception.
     *
     * @param original The original exception (maybe null)
     * @return The root cause of the original exception, or null if the original is null
     */
    public static @Nullable Throwable getRootCause(@Nullable Throwable original) {
        return getRootCause(original, null);
    }

    /**
     * Retrieves the root cause of an expected type exception.
     *
     * @param original The original exception (allow null)
     * @param expectedType The expected type of the root cause (allow null)
     * @return The expected type root cause of the original exception, or null if the original is null,
     *         or null if not have expected type
     */
    public static @Nullable Throwable getRootCause(
            @Nullable Throwable original, @Nullable Class<? extends Throwable> expectedType) {
        if (original == null) {
            return null;
        } else {
            Throwable rootCause = null;

            for (Throwable cause = original.getCause();
                    cause != null && cause != rootCause;
                    cause = cause.getCause()) {
                if (expectedType != null && expectedType.isInstance(cause)) {
                    return cause;
                }
                rootCause = cause;
            }

            return expectedType == null ? rootCause : null;
        }
    }
}
