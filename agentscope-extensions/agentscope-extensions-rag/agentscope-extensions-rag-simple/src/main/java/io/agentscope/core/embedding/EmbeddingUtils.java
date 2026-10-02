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
package io.agentscope.core.embedding;

import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Utility class for common EmbeddingModel operations.
 *
 * <p>This class provides shared functionality used across different EmbeddingModel implementations,
 * including timeout and retry logic for embedding API calls, and conversion utilities.
 */
public final class EmbeddingUtils {

    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);

    private EmbeddingUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Applies timeout and retry configuration to an embedding Mono.
     *
     * <p>This method wraps the original Mono with timeout and retry operators based on the
     * configuration in ExecutionConfig. Both timeout and retry are optional and only applied if
     * configured.
     *
     * <p><b>Timeout Behavior:</b>
     * <ul>
     *   <li>If timeout is configured, the entire request will fail if it exceeds the
     *       specified duration
     *   <li>Timeout triggers an EmbeddingException with details about the timeout duration
     *   <li>If no timeout is configured, requests can run indefinitely
     * </ul>
     *
     * <p><b>Retry Behavior:</b>
     * <ul>
     *   <li>If ExecutionConfig is provided with maxAttempts > 1, failed requests will be retried
     *       with exponential backoff
     *   <li>Retries respect the maxAttempts, initialBackoff, and maxBackoff settings
     *   <li>Only errors matching the retryOn predicate will be retried
     *   <li>Each retry is logged with attempt number and failure reason
     * </ul>
     *
     * @param embeddingMono the original embedding Mono to enhance
     * @param config execution config containing timeout and retry settings (may be null)
     * @param modelName the name of the model for error messages and logging
     * @param provider the provider name (e.g., "dashscope", "openai") for error messages
     * @param logger the logger instance for debug and warning messages
     * @param operationType the operation type description (e.g., "embedding", "batch embedding")
     * @param <T> the type of the embedding result
     * @return wrapped Mono with timeout and retry applied
     */
    public static <T> Mono<T> applyTimeoutAndRetry(
            Mono<T> embeddingMono,
            ExecutionConfig config,
            String modelName,
            String provider,
            Logger logger,
            String operationType) {

        if (config == null) {
            return embeddingMono;
        }

        // Apply timeout if configured
        Duration timeout = config.getTimeout();
        if (timeout != null) {
            embeddingMono =
                    embeddingMono.timeout(
                            timeout,
                            Mono.error(
                                    new EmbeddingException(
                                            operationType + " request timeout after " + timeout,
                                            modelName,
                                            provider)));
            logger.debug("Applied timeout: {} for {} model: {}", timeout, operationType, modelName);
        }

        // Apply retry if configured (maxAttempts > 1 means retry is enabled)
        Integer maxAttempts = config.getMaxAttempts();
        if (maxAttempts != null && maxAttempts > 1) {
            Duration initialBackoff = config.getInitialBackoff();
            Duration maxBackoff = config.getMaxBackoff();
            Predicate<Throwable> retryOn = config.getRetryOn();

            // Use defaults if not specified
            if (initialBackoff == null) {
                initialBackoff = DEFAULT_INITIAL_BACKOFF;
            }
            if (maxBackoff == null) {
                maxBackoff = DEFAULT_MAX_BACKOFF;
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
                                                    "Retrying {} request (attempt {}/{}) due to:"
                                                            + " {}",
                                                    operationType,
                                                    signal.totalRetriesInARow() + 1,
                                                    maxAttempts - 1,
                                                    signal.failure().getMessage()));

            embeddingMono = embeddingMono.retryWhen(retrySpec);
            logger.debug(
                    "Applied retry config: maxAttempts={}, initialBackoff={} for {} model: {}",
                    maxAttempts,
                    initialBackoff,
                    operationType,
                    modelName);
        }

        return embeddingMono;
    }

    /**
     * Applies timeout and retry configuration to a single embedding Mono.
     *
     * <p>This is a convenience method that delegates to the generic
     * {@link #applyTimeoutAndRetry(Mono, ExecutionConfig, String, String, Logger, String)}
     * with "embedding" as the operation type.
     *
     * @param embeddingMono the original embedding Mono to enhance
     * @param config execution config containing timeout and retry settings (may be null)
     * @param modelName the name of the model for error messages and logging
     * @param provider the provider name (e.g., "dashscope", "openai") for error messages
     * @param logger the logger instance for debug and warning messages
     * @return wrapped Mono with timeout and retry applied
     */
    public static Mono<double[]> applyTimeoutAndRetry(
            Mono<double[]> embeddingMono,
            ExecutionConfig config,
            String modelName,
            String provider,
            Logger logger) {
        return applyTimeoutAndRetry(
                embeddingMono, config, modelName, provider, logger, "Embedding");
    }

    /**
     * Applies timeout and retry configuration to a batch embedding Mono.
     *
     * <p>This is a convenience method that delegates to the generic
     * {@link #applyTimeoutAndRetry(Mono, ExecutionConfig, String, String, Logger, String)}
     * with "batch embedding" as the operation type.
     *
     * @param batchEmbeddingMono the original batch embedding Mono to enhance
     * @param config execution config containing timeout and retry settings (may be null)
     * @param modelName the name of the model for error messages and logging
     * @param provider the provider name (e.g., "dashscope", "openai") for error messages
     * @param logger the logger instance for debug and warning messages
     * @return wrapped Mono with timeout and retry applied
     */
    public static Mono<List<double[]>> applyTimeoutAndRetryBatch(
            Mono<List<double[]>> batchEmbeddingMono,
            ExecutionConfig config,
            String modelName,
            String provider,
            Logger logger) {
        return applyTimeoutAndRetry(
                batchEmbeddingMono, config, modelName, provider, logger, "Batch embedding");
    }

    /**
     * Ensures ExecutionConfig has MODEL_DEFAULTS applied.
     *
     * <p>This method applies the standard default execution configuration:
     * <ul>
     *   <li>If config is null, returns MODEL_DEFAULTS
     *   <li>Otherwise returns config unchanged (caller should merge if needed)
     * </ul>
     *
     * <p>This is used by embedding model builders to ensure that all models have proper default
     * timeout and retry configuration applied.
     *
     * @param config the original config (may be null)
     * @return config with MODEL_DEFAULTS applied as needed, or MODEL_DEFAULTS if config is null
     */
    public static ExecutionConfig ensureDefaultExecutionConfig(ExecutionConfig config) {
        if (config == null) {
            return ExecutionConfig.MODEL_DEFAULTS;
        }
        // If config is provided, return as-is (caller can merge if needed)
        return config;
    }

    /**
     * Converts a List of Double values to a double array.
     *
     * <p>This method is used to convert embedding vectors returned by DashScope SDK
     * (which uses List&lt;Double&gt;) to the standard double[] format used by EmbeddingModel.
     *
     * @param values the list of Double values
     * @return the double array
     */
    public static double[] convertDoubleListToArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * Converts a List of Float values to a double array.
     *
     * <p>This method is used to convert embedding vectors returned by OpenAI SDK
     * (which uses List&lt;Float&gt;) to the standard double[] format used by EmbeddingModel.
     *
     * @param values the list of Float values
     * @return the double array
     */
    public static double[] convertFloatListToDoubleArray(List<Float> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * Converts a float array to a double array.
     *
     * <p>This method is used to convert embedding vectors returned by Ollama SDK
     * (which uses float[]) to the standard double[] format used by EmbeddingModel.
     *
     * @param values the float array
     * @return the double array
     */
    public static double[] convertFloatArrayToDoubleArray(float[] values) {
        double[] array = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            array[i] = values[i];
        }
        return array;
    }
}
