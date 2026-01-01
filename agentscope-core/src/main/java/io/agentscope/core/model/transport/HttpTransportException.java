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
package io.agentscope.core.model.transport;

/**
 * Exception thrown when HTTP transport operations fail.
 *
 * <p>This exception wraps underlying transport errors such as connection failures,
 * timeouts, and protocol errors.
 */
public class HttpTransportException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;

    /**
     * Create a new HttpTransportException with a message.
     *
     * @param message the error message
     */
    public HttpTransportException(String message) {
        super(message);
        this.statusCode = null;
        this.responseBody = null;
    }

    /**
     * Create a new HttpTransportException with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public HttpTransportException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
    }

    /**
     * Create a new HttpTransportException for an HTTP error response.
     *
     * @param message the error message
     * @param statusCode the HTTP status code
     * @param responseBody the response body (may contain error details)
     */
    public HttpTransportException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Create a new HttpTransportException for an HTTP error response with cause.
     *
     * @param message the error message
     * @param statusCode the HTTP status code
     * @param responseBody the response body
     * @param cause the underlying cause
     */
    public HttpTransportException(
            String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Get the HTTP status code if available.
     *
     * @return the status code, or null if not an HTTP error
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Get the response body if available.
     *
     * @return the response body, or null if not available
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * Check if this is an HTTP error (has status code).
     *
     * @return true if this exception represents an HTTP error response
     */
    public boolean isHttpError() {
        return statusCode != null;
    }

    /**
     * Check if this is a client error (4xx status code).
     *
     * @return true if status code is 4xx
     */
    public boolean isClientError() {
        return statusCode != null && statusCode >= 400 && statusCode < 500;
    }

    /**
     * Check if this is a server error (5xx status code).
     *
     * @return true if status code is 5xx
     */
    public boolean isServerError() {
        return statusCode != null && statusCode >= 500 && statusCode < 600;
    }

    /**
     * Check if this error is retryable.
     *
     * <p>Server errors (5xx) and certain client errors (429 Too Many Requests)
     * are typically retryable.
     *
     * @return true if the error is retryable
     */
    public boolean isRetryable() {
        if (statusCode == null) {
            // Connection errors are usually retryable
            return true;
        }
        // Server errors and rate limiting are retryable
        return isServerError() || statusCode == 429;
    }
}
