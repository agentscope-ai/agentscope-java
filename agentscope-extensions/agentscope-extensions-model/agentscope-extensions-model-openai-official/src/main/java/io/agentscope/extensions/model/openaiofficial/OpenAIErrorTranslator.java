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

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import java.util.concurrent.TimeoutException;

/**
 * Translates official OpenAI Java SDK exceptions into {@link OpenAIOfficialModelException}.
 *
 * <p>SDK exceptions are classified as follows:
 * <ul>
 *   <li>Typed {@code OpenAIServiceException} subclasses (e.g. {@code BadRequestException},
 *       {@code RateLimitException}, {@code InternalServerException}) → exception with
 *       HTTP status; retryability is determined by
 *       {@link OpenAIOfficialModelException#isRetryableHttpStatus()}.
 *   <li>408/409 from {@code UnexpectedStatusCodeException} or {@code SseException} →
 *       exception with HTTP status.
 *   <li>Any other {@code OpenAIServiceException} → exception with HTTP status,
 *       non-retryable by default.
 *   <li>{@code OpenAIIoException} / {@code OpenAIRetryableException} → exception
 *       without HTTP status.
 *   <li>{@code TimeoutException} in cause chain → exception without HTTP status.
 *   <li>{@code OpenAIInvalidDataException} → exception without HTTP status,
 *       non-retryable.
 * </ul>
 *
 * <p>Non-SDK exceptions (validation, refusal, unsupported input) are created at their
 * respective call sites, not by this translator.
 *
 * <p>The original SDK exception is always preserved as the cause so that the module
 * retryOn predicate can inspect headers and exception types in the cause chain.
 */
final class OpenAIErrorTranslator {

    private OpenAIErrorTranslator() {}

    /**
     * Translates a throwable into an {@link OpenAIOfficialModelException}.
     *
     * @param throwable the exception to translate (typically an SDK exception)
     * @param modelName the model name for context, or null if unknown
     * @return a normalized exception with provider id {@code openai-official}
     */
    static OpenAIOfficialModelException translate(Throwable throwable, String modelName) {
        if (throwable instanceof OpenAIOfficialModelException alreadyTranslated) {
            return alreadyTranslated;
        }

        // OpenAIServiceException covers all HTTP-status-bearing SDK errors
        if (throwable instanceof OpenAIServiceException serviceException) {
            int statusCode = serviceException.statusCode();
            String safeMessage = safeMessage(throwable, statusCode);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName, statusCode);
        }

        // Retryable transport-level exceptions (no HTTP status)
        if (throwable instanceof OpenAIIoException
                || throwable instanceof OpenAIRetryableException) {
            String safeMessage = safeMessage(throwable, null);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
        }

        // SDK response parsing/validation error (non-retryable, no HTTP status)
        if (throwable instanceof OpenAIInvalidDataException) {
            String safeMessage = safeMessage(throwable, null);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
        }

        // TimeoutException (direct or in cause chain) → retryable, no HTTP status
        if (hasTimeoutInCauseChain(throwable)) {
            String safeMessage = safeMessage(throwable, null);
            return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
        }

        // Fallback: wrap any other throwable as non-retryable
        String safeMessage = safeMessage(throwable, null);
        return new OpenAIOfficialModelException(safeMessage, throwable, modelName);
    }

    /**
     * Null-safe message extraction.
     *
     * <p>If {@code throwable.getMessage()} is non-null, uses it. Otherwise falls back to
     * {@code "OpenAI API error (HTTP {statusCode})"} when an HTTP status is known, or
     * the exception class name when no status is available.
     */
    private static String safeMessage(Throwable throwable, Integer statusCode) {
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (statusCode != null) {
            return "OpenAI API error (HTTP " + statusCode + ")";
        }
        return "OpenAI API error: " + throwable.getClass().getSimpleName();
    }

    /**
     * Checks whether a {@link TimeoutException} appears anywhere in the cause chain.
     */
    private static boolean hasTimeoutInCauseChain(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
