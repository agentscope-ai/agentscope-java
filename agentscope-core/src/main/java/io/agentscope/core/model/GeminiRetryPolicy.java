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
package io.agentscope.core.model;

import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import reactor.util.retry.Retry;

/**
 * Encapsulates Gemini retry policy configuration and retryable error detection.
 */
final class GeminiRetryPolicy {

    // Retry configuration aligned with Google GenAI SDK
    // See: java-genai/src/main/java/com/google/genai/RetryInterceptor.java
    private static final int RETRY_MAX_ATTEMPTS = 5;
    private static final long RETRY_MAX_DELAY_SECONDS = 60;
    private static final double RETRY_JITTER_FACTOR = 0.5;
    private static final Set<Integer> RETRYABLE_HTTP_STATUS_CODES =
            Set.of(
                    408, // Request Timeout
                    429, // Too Many Requests
                    500, // Internal Server Error
                    502, // Bad Gateway
                    503, // Service Unavailable
                    504 // Gateway Timeout
                    );

    private final String modelName;
    private final Logger log;

    GeminiRetryPolicy(String modelName, Logger log) {
        this.modelName = modelName;
        this.log = log;
    }

    Retry build() {
        return Retry.backoff(RETRY_MAX_ATTEMPTS, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(RETRY_MAX_DELAY_SECONDS))
                .jitter(RETRY_JITTER_FACTOR)
                .filter(this::shouldRetry)
                .doBeforeRetry(
                        retrySignal ->
                                log.debug(
                                        "Retrying Gemini request (attempt {}/{}): {}",
                                        retrySignal.totalRetries() + 1,
                                        RETRY_MAX_ATTEMPTS,
                                        retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow(
                        (retryBackoffSpec, retrySignal) ->
                                new ModelException(
                                        "Gemini request failed after "
                                                + retrySignal.totalRetries()
                                                + " retries: "
                                                + retrySignal.failure().getMessage(),
                                        retrySignal.failure()));
    }

    private boolean shouldRetry(Throwable throwable) {
        // Retry on retryable HTTP status codes
        // Aligned with Google GenAI SDK:
        // 408 (Request Timeout), 429 (Too Many Requests),
        // 500 (Internal Server Error), 502 (Bad Gateway),
        // 503 (Service Unavailable), 504 (Gateway Timeout)
        if (throwable instanceof GeminiApiException) {
            int code = ((GeminiApiException) throwable).getStatusCode();
            boolean isRetryable = RETRYABLE_HTTP_STATUS_CODES.contains(code);
            if (isRetryable) {
                log.debug("Retryable HTTP status code: {}", code);
            }
            return isRetryable;
        }

        // Retry on empty content errors for Gemini 3 models
        // This covers MALFORMED_FUNCTION_CALL, empty video responses, etc.
        if (throwable instanceof ModelException && modelName.toLowerCase().contains("gemini-3")) {
            String errorMsg = throwable.getMessage();
            if (errorMsg != null && errorMsg.contains("empty content")) {
                log.warn("Detected Gemini 3 empty content error, retrying...");
                return true;
            }
        }
        return false;
    }
}
