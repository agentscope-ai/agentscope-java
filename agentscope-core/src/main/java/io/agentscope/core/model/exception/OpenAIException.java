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
package io.agentscope.core.model.exception;

/**
 * Base exception for all OpenAI API errors.
 *
 * <p>This is the root exception class for OpenAI-related errors.
 * Specific error conditions are represented by subclasses.
 */
public class OpenAIException extends RuntimeException {
    private final Integer statusCode;
    private final String errorCode;
    private final String responseBody;

    public OpenAIException(String message) {
        super(message);
        this.statusCode = null;
        this.errorCode = null;
        this.responseBody = null;
    }

    public OpenAIException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.errorCode = null;
        this.responseBody = null;
    }

    public OpenAIException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = null;
        this.responseBody = responseBody;
    }

    public OpenAIException(String message, int statusCode, String errorCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.responseBody = responseBody;
    }

    /**
     * Factory method to create appropriate exception subclass based on status code.
     *
     * @param statusCode HTTP status code
     * @param message Error message
     * @param errorCode OpenAI error code
     * @param responseBody Full response body
     * @return Appropriate exception subclass
     */
    public static OpenAIException create(
            int statusCode, String message, String errorCode, String responseBody) {
        return switch (statusCode) {
            case 400 -> new BadRequestException(message, errorCode, responseBody);
            case 401 -> new AuthenticationException(message, errorCode, responseBody);
            case 403 -> new PermissionDeniedException(message, errorCode, responseBody);
            case 404 -> new NotFoundException(message, errorCode, responseBody);
            case 422 -> new UnprocessableEntityException(message, errorCode, responseBody);
            case 429 -> new RateLimitException(message, errorCode, responseBody);
            default ->
                    statusCode >= 500 && statusCode < 600
                            ? new InternalServerException(
                                    message, statusCode, errorCode, responseBody)
                            : new OpenAIException(message, statusCode, errorCode, responseBody);
        };
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
