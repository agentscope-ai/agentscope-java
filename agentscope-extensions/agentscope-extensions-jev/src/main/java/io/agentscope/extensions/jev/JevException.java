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

package io.agentscope.extensions.jev;

/** Client, transport, validation, and API error for Jev System One calls. */
public class JevException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;
    private final boolean retryable;

    public JevException(String message) {
        this(message, null, null, false);
    }

    public JevException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
        this.retryable = false;
    }

    public JevException(
            String message, Integer statusCode, String responseBody, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryable = retryable;
    }

    public JevException(
            String message,
            Throwable cause,
            Integer statusCode,
            String responseBody,
            boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryable = retryable;
    }

    /** The HTTP status, or {@code null} when the request failed before a response was received. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** The raw HTTP error body, or {@code null}. */
    public String getResponseBody() {
        return responseBody;
    }

    /** Whether the configured retry policy may retry this error. */
    public boolean isRetryable() {
        return retryable;
    }
}
