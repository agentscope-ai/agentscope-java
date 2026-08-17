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
 * Exception thrown when Dify API returns an error response.
 *
 * <p>This exception encapsulates error information from Dify API responses,
 * including HTTP status codes, error codes, and error messages.
 *
 * <p>Common error scenarios:
 * <ul>
 *   <li>400 Bad Request: Invalid parameters or malformed request
 *   <li>401 Unauthorized: Invalid or missing API key
 *   <li>403 Forbidden: Insufficient permissions
 *   <li>404 Not Found: Dataset or document not found
 *   <li>429 Too Many Requests: Rate limit exceeded
 *   <li>500 Internal Server Error: Dify service error
 * </ul>
 */
public class DifyApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    /**
     * Creates a new DifyApiException with a message.
     *
     * @param message the error message
     */
    public DifyApiException(String message) {
        super(message);
        this.statusCode = -1;
        this.errorCode = null;
    }

    /**
     * Creates a new DifyApiException with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DifyApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.errorCode = null;
    }

    /**
     * Creates a new DifyApiException with HTTP status code.
     *
     * @param statusCode the HTTP status code
     * @param message the error message
     */
    public DifyApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = null;
    }

    /**
     * Creates a new DifyApiException with full error details.
     *
     * @param statusCode the HTTP status code
     * @param errorCode the Dify error code
     * @param message the error message
     */
    public DifyApiException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    /**
     * Creates a new DifyApiException with full error details and cause.
     *
     * @param statusCode the HTTP status code
     * @param errorCode the Dify error code
     * @param message the error message
     * @param cause the underlying cause
     */
    public DifyApiException(int statusCode, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    /**
     * Gets the HTTP status code.
     *
     * @return the status code, or -1 if not available
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Gets the Dify error code.
     *
     * @return the error code, or null if not available
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Checks if this is a client error (4xx status code).
     *
     * @return true if status code is 4xx
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * Checks if this is a server error (5xx status code).
     *
     * @return true if status code is 5xx
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Checks if this is a rate limit error (429 status code).
     *
     * @return true if status code is 429
     */
    public boolean isRateLimitError() {
        return statusCode == 429;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DifyApiException");
        if (statusCode > 0) {
            sb.append(" [").append(statusCode).append("]");
        }
        if (errorCode != null) {
            sb.append(" (").append(errorCode).append(")");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
