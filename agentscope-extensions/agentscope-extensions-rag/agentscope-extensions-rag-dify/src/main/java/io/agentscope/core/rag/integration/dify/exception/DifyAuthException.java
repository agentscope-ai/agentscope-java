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
package io.agentscope.core.rag.integration.dify.exception;

/**
 * Exception thrown when Dify API authentication fails.
 *
 * <p>This exception is a specialized version of {@link DifyApiException} for
 * authentication-related errors. It typically indicates:
 * <ul>
 *   <li>Invalid API key
 *   <li>Expired API key
 *   <li>Missing API key
 *   <li>Insufficient permissions for the requested operation
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try {
 *     difyClient.retrieve(...);
 * } catch (DifyAuthException e) {
 *     log.error("Authentication failed: {}", e.getMessage());
 *     // Check your API key configuration
 * }
 * }</pre>
 */
public class DifyAuthException extends DifyApiException {

    /**
     * Creates a new DifyAuthException with a message.
     *
     * @param message the error message
     */
    public DifyAuthException(String message) {
        super(401, "UNAUTHORIZED", message);
    }

    /**
     * Creates a new DifyAuthException with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DifyAuthException(String message, Throwable cause) {
        super(401, "UNAUTHORIZED", message, cause);
    }

    /**
     * Creates a new DifyAuthException with custom status code.
     *
     * <p>Use this constructor for permission-related errors (403 Forbidden).
     *
     * @param statusCode the HTTP status code (401 or 403)
     * @param message the error message
     */
    public DifyAuthException(int statusCode, String message) {
        super(statusCode, "UNAUTHORIZED", message);
    }

    @Override
    public String toString() {
        return "DifyAuthException [" + getStatusCode() + "]: " + getMessage();
    }
}
