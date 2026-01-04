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
import java.util.function.Predicate;
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

        // Merge options: per-request options (primary) override default options (fallback)
        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, defaultOptions);

        // Extract execution config
        ExecutionConfig execConfig = effectiveOptions.getExecutionConfig();
        if (execConfig != null) {
            // Apply timeout if configured
            Duration timeout = execConfig.getTimeout();
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

            // Apply retry if configured (maxAttempts > 1 means retry is enabled)
            Integer maxAttempts = execConfig.getMaxAttempts();
            if (maxAttempts != null && maxAttempts > 1) {
                Duration initialBackoff = execConfig.getInitialBackoff();
                Duration maxBackoff = execConfig.getMaxBackoff();
                Predicate<Throwable> retryOn = execConfig.getRetryOn();

                // Use defaults if not specified
                if (initialBackoff == null) {
                    initialBackoff = Duration.ofSeconds(1);
                }
                if (maxBackoff == null) {
                    maxBackoff = Duration.ofSeconds(10);
                }
                if (retryOn == null) {
                    retryOn = error -> true; // retry all errors by default
                }

                Retry retrySpec =
                        Retry.backoff(maxAttempts - 1, initialBackoff)
                                .maxBackoff(maxBackoff)
                                .jitter(0.5)
                                .filter(retryOn)
                                .doBeforeRetry(
                                        signal ->
                                                logger.warn(
                                                        "Retrying model request (attempt {}/{}) due"
                                                                + " to: {}",
                                                        signal.totalRetriesInARow() + 1,
                                                        maxAttempts - 1,
                                                        signal.failure().getMessage()));

                responseFlux = responseFlux.retryWhen(retrySpec);
                logger.debug(
                        "Applied retry config: maxAttempts={}, initialBackoff={} for model: {}",
                        maxAttempts,
                        initialBackoff,
                        modelName);
            }
        }

        return responseFlux;
    }

    /**
     * Ensures GenerateOptions has MODEL_DEFAULTS for executionConfig applied.
     *
     * <p>This method applies the standard default execution configuration to GenerateOptions:
     * <ul>
     *   <li>If options is null, creates new options with MODEL_DEFAULTS
     *   <li>If options has null executionConfig, merges with MODEL_DEFAULTS
     *   <li>Otherwise returns options unchanged
     * </ul>
     *
     * <p>This is used by model builders to ensure that all models have proper default timeout
     * and retry configuration applied, even when users provide custom GenerateOptions without
     * executionConfig.
     *
     * @param options the original options (may be null)
     * @return options with MODEL_DEFAULTS applied as needed
     */
    public static GenerateOptions ensureDefaultExecutionConfig(GenerateOptions options) {
        if (options == null) {
            // No options provided, use MODEL_DEFAULTS
            return GenerateOptions.builder()
                    .executionConfig(ExecutionConfig.MODEL_DEFAULTS)
                    .build();
        }

        if (options.getExecutionConfig() == null) {
            // Options provided but no executionConfig, merge with MODEL_DEFAULTS
            GenerateOptions withDefaults =
                    GenerateOptions.builder()
                            .executionConfig(ExecutionConfig.MODEL_DEFAULTS)
                            .build();
            return GenerateOptions.mergeOptions(options, withDefaults);
        }

        // executionConfig already present, return as-is
        return options;
    }
}
