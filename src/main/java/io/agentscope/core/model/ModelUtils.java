/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.model;

import java.time.Duration;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/**
 * Utility class for common Model operations.
 *
 * <p>This class provides shared functionality used across different Model implementations,
 * including timeout and retry logic for model API calls.
 */
public final class ModelUtils {

    private ModelUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Applies timeout and retry configuration to a model response Flux.
     *
     * <p>This method wraps the original Flux with timeout and retry operators based on the
     * configuration in GenerateOptions. Both timeout and retry are optional and only applied if
     * configured.
     *
     * <p><b>Timeout Behavior:</b>
     * <ul>
     *   <li>If requestTimeout is configured, the entire request will fail if it exceeds the
     *       specified duration
     *   <li>Timeout triggers a ModelException with details about the timeout duration
     *   <li>If no timeout is configured, requests can run indefinitely
     * </ul>
     *
     * <p><b>Retry Behavior:</b>
     * <ul>
     *   <li>If RetryConfig is provided, failed requests will be retried with exponential backoff
     *   <li>Retries respect the maxAttempts, initialBackoff, and maxBackoff settings
     *   <li>Only errors matching the retryOn predicate will be retried
     *   <li>Each retry is logged with attempt number and failure reason
     * </ul>
     *
     * @param responseFlux the original response Flux to enhance
     * @param options generation options containing timeout and retry config (may be null)
     * @param defaultOptions default options to use if options is null
     * @param modelName the name of the model for error messages and logging
     * @param provider the provider name (e.g., "dashscope", "openai") for error messages
     * @param logger the logger instance for debug and warning messages
     * @return wrapped Flux with timeout and retry applied
     */
    public static Flux<ChatResponse> applyTimeoutAndRetry(
            Flux<ChatResponse> responseFlux,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            String modelName,
            String provider,
            Logger logger) {

        GenerateOptions effectiveOptions = options != null ? options : defaultOptions;

        // Apply timeout if configured
        Duration timeout = effectiveOptions.getRequestTimeout();
        if (timeout != null) {
            responseFlux =
                    responseFlux.timeout(
                            timeout,
                            Flux.error(
                                    new ModelException(
                                            "Model request timeout after " + timeout,
                                            modelName,
                                            provider)));
            logger.debug("Applied timeout: {} for model: {}", timeout, modelName);
        }

        // Apply retry if configured
        RetryConfig retryConfig = effectiveOptions.getRetryConfig();
        if (retryConfig != null) {
            Retry retrySpec =
                    Retry.backoff(retryConfig.getMaxAttempts() - 1, retryConfig.getInitialBackoff())
                            .maxBackoff(retryConfig.getMaxBackoff())
                            .jitter(0.5)
                            .filter(retryConfig.getRetryOn())
                            .doBeforeRetry(
                                    signal ->
                                            logger.warn(
                                                    "Retrying model request (attempt {}/{}) due to:"
                                                            + " {}",
                                                    signal.totalRetriesInARow() + 1,
                                                    retryConfig.getMaxAttempts() - 1,
                                                    signal.failure().getMessage()));

            responseFlux = responseFlux.retryWhen(retrySpec);
            logger.debug(
                    "Applied retry config: maxAttempts={}, initialBackoff={} for model: {}",
                    retryConfig.getMaxAttempts(),
                    retryConfig.getInitialBackoff(),
                    modelName);
        }

        return responseFlux;
    }
}
