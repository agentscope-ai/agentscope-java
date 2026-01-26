/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.model.tts;

/**
 * Exception thrown when TTS operations fail.
 *
 * <p>This exception encapsulates errors that occur during text-to-speech
 * synthesis, including API errors, network issues, and invalid configurations.
 */
public class TTSException extends RuntimeException {

    /** HTTP status code (if applicable). */
    private final Integer statusCode;

    /** Error code from the TTS provider. */
    private final String errorCode;

    /** Raw response body (for debugging). */
    private final String responseBody;

    /**
     * Creates a new TTSException with a message.
     *
     * @param message error message
     */
    public TTSException(String message) {
        super(message);
        this.statusCode = null;
        this.errorCode = null;
        this.responseBody = null;
    }

    /**
     * Creates a new TTSException with a message and cause.
     *
     * @param message error message
     * @param cause the underlying cause
     */
    public TTSException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.errorCode = null;
        this.responseBody = null;
    }

    /**
     * Creates a new TTSException with HTTP status code.
     *
     * @param message error message
     * @param statusCode HTTP status code
     * @param responseBody raw response body
     */
    public TTSException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = null;
        this.responseBody = responseBody;
    }

    /**
     * Creates a new TTSException with error code.
     *
     * @param message error message
     * @param errorCode error code from provider
     * @param responseBody raw response body
     */
    public TTSException(String message, String errorCode, String responseBody) {
        super(message);
        this.statusCode = null;
        this.errorCode = errorCode;
        this.responseBody = responseBody;
    }

    /**
     * Gets the HTTP status code.
     *
     * @return status code, or null if not applicable
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Gets the error code from the TTS provider.
     *
     * @return error code, or null if not available
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Gets the raw response body.
     *
     * @return response body for debugging
     */
    public String getResponseBody() {
        return responseBody;
    }
}
