/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;

/**
 * Exception type for the {@code openai-official} provider.
 *
 * <p>Extends {@link ModelException} and implements {@link ModelHttpException} so that
 * HTTP-status-aware retry classification works through both the core
 * {@code ExecutionConfig.RETRYABLE_ERRORS} safety net and the module's own retryOn predicate.
 *
 * <p>Overrides {@link #isRetryableHttpStatus()} to include 408 and 409 in addition to the
 * core default (429 and 5xx). This aligns with the SDK {@code RetryingHttpClient.shouldRetry}
 * status-code set {408, 409, 429, >=500}.
 */
public class OpenAIOfficialModelException extends ModelException implements ModelHttpException {

    private final Integer statusCode;

    /**
     * Creates an exception with no HTTP status (for non-HTTP SDK failures such as
     * {@code OpenAIIoException}, {@code OpenAIInvalidDataException}, or validation errors).
     *
     * @param message   the error message
     * @param cause     the underlying SDK exception, or null
     * @param modelName the model name, or null if unknown
     */
    public OpenAIOfficialModelException(String message, Throwable cause, String modelName) {
        super(message, cause, modelName, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = null;
    }

    /**
     * Creates an exception with an HTTP status code (for {@code OpenAIServiceException}
     * subclasses such as {@code BadRequestException}, {@code RateLimitException}, etc.).
     *
     * @param message    the error message
     * @param cause      the underlying SDK exception
     * @param modelName  the model name, or null if unknown
     * @param statusCode the HTTP status code from the SDK exception
     */
    public OpenAIOfficialModelException(
            String message, Throwable cause, String modelName, Integer statusCode) {
        super(message, cause, modelName, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = statusCode;
    }

    /**
     * Creates a validation/non-retryable exception with no cause and no HTTP status.
     *
     * @param message the validation message
     */
    public OpenAIOfficialModelException(String message) {
        super(message, null, null, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = null;
    }

    /**
     * Creates a validation/non-retryable exception with no cause, no HTTP status,
     * but with a known model name.
     *
     * @param message   the validation message
     * @param modelName the model name, or null if unknown
     */
    public OpenAIOfficialModelException(String message, String modelName) {
        super(message, null, modelName, OpenAIOfficialConstants.PROVIDER_ID);
        this.statusCode = null;
    }

    @Override
    public Integer getStatusCode() {
        return statusCode;
    }

    @Override
    public boolean isRetryableHttpStatus() {
        if (statusCode == null) {
            return false;
        }
        return statusCode == 408
                || statusCode == 409
                || statusCode == 429
                || (statusCode >= 500 && statusCode < 600);
    }
}
