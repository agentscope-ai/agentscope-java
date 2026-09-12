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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

/**
 * Unit tests for Model timeout and retry functionality.
 */
@DisplayName("Model Timeout and Retry Tests")
class ModelTimeoutRetryTest {

    @Test
    @DisplayName("Should timeout when model request exceeds configured duration")
    void testModelRequestTimeout() {
        // Create a mock model that delays response
        Model slowModel = createSlowModel(Duration.ofSeconds(5));

        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should timeout
        StepVerifier.create(slowModel.stream(List.of(testMsg), null, options))
                .expectError(ModelException.class)
                .verify();
    }

    @Test
    @DisplayName("Should complete successfully when request is within timeout")
    void testModelRequestWithinTimeout() {
        Model fastModel = createFastModel();

        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(10)).build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should complete successfully
        StepVerifier.create(fastModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should not apply timeout when executionConfig is null")
    void testNoTimeoutWhenNull() {
        Model slowModel = createSlowModel(Duration.ofMillis(500));

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should complete successfully even if slow (no timeout configured)
        StepVerifier.create(slowModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should retry failed requests according to ExecutionConfig")
    void testModelRetry() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Create a model that fails twice then succeeds
        Model flakyModel = createFlakyModel(attemptCount, 2);

        ExecutionConfig executionConfig = ExecutionConfig.builder().maxAttempts(3).build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should succeed after retries
        StepVerifier.create(flakyModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();

        // Verify it retried (3 attempts total: 1 original + 2 retries)
        assertEquals(3, attemptCount.get());
    }

    @Test
    @DisplayName("Should fail when all retry attempts are exhausted")
    void testRetryExhaustion() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Create a model that always fails
        Model alwaysFailModel = createFlakyModel(attemptCount, Integer.MAX_VALUE);

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should fail after all retries
        StepVerifier.create(alwaysFailModel.stream(List.of(testMsg), null, options))
                .expectError(RuntimeException.class)
                .verify();

        // Verify it tried maxAttempts times
        assertEquals(3, attemptCount.get());
    }

    @Test
    @DisplayName("Should respect retryOn predicate filter")
    void testRetryOnPredicate() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Create a model that throws IllegalArgumentException
        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        attemptCount.incrementAndGet();
                        return Flux.error(new IllegalArgumentException("test error"));
                    }

                    @Override
                    public String getModelName() {
                        return "test";
                    }
                };

        // Only retry on ModelException (not IllegalArgumentException)
        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(5)
                        .retryOn(error -> error instanceof ModelException)
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should fail immediately without retry
        StepVerifier.create(model.stream(List.of(testMsg), null, options))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Should only attempt once (no retries because error doesn't match predicate)
        assertEquals(1, attemptCount.get());
    }

    @Test
    @DisplayName("Should not retry when executionConfig is null")
    void testNoRetryWhenNull() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        Model flakyModel = createFlakyModel(attemptCount, 1);

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should fail immediately without retry
        StepVerifier.create(flakyModel.stream(List.of(testMsg), null, options))
                .expectError(RuntimeException.class)
                .verify();

        // Should only attempt once
        assertEquals(1, attemptCount.get());
    }

    // Helper methods to create test models

    /**
     * Creates a mock model that delays responses by the specified duration.
     * Useful for testing timeout behavior.
     *
     * @param delay the delay duration before responding
     * @return a Model instance that delays responses
     */
    private Model createSlowModel(Duration delay) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                Flux<ChatResponse> responseFlux =
                        Flux.just(createMockResponse()).delayElements(delay).next().flux();
                return applyTimeoutAndRetry(responseFlux, options);
            }

            @Override
            public String getModelName() {
                return "slow-model";
            }
        };
    }

    /**
     * Creates a mock model that responds immediately without delay.
     * Useful for testing successful operations within timeout limits.
     *
     * @return a Model instance that responds immediately
     */
    private Model createFastModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                Flux<ChatResponse> responseFlux = Flux.just(createMockResponse());
                return applyTimeoutAndRetry(responseFlux, options);
            }

            @Override
            public String getModelName() {
                return "fast-model";
            }
        };
    }

    /**
     * Creates a mock model that fails a specified number of times before succeeding.
     * Useful for testing retry behavior and retry exhaustion scenarios.
     *
     * @param attemptCount atomic counter tracking the number of attempts made
     * @param failCount the number of times the model should fail before succeeding
     * @return a Model instance that fails for the first failCount attempts, then succeeds
     */
    private Model createFlakyModel(AtomicInteger attemptCount, int failCount) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                Flux<ChatResponse> responseFlux =
                        Flux.defer(
                                () -> {
                                    int attempt = attemptCount.incrementAndGet();
                                    if (attempt <= failCount) {
                                        return Flux.error(
                                                new RuntimeException(
                                                        "Simulated failure " + attempt));
                                    }
                                    return Flux.just(createMockResponse());
                                });
                return applyTimeoutAndRetry(responseFlux, options);
            }

            @Override
            public String getModelName() {
                return "flaky-model";
            }
        };
    }

    /**
     * Helper method that applies timeout and retry logic to a response Flux, mimicking the
     * behavior in provider extension models.
     */
    private Flux<ChatResponse> applyTimeoutAndRetry(
            Flux<ChatResponse> responseFlux, GenerateOptions options) {
        if (options == null) {
            return responseFlux;
        }

        ExecutionConfig executionConfig = options.getExecutionConfig();
        if (executionConfig == null) {
            return responseFlux;
        }

        // Apply timeout if configured
        Duration timeout = executionConfig.getTimeout();
        if (timeout != null) {
            responseFlux =
                    responseFlux.timeout(
                            timeout,
                            Flux.error(
                                    new ModelException(
                                            "Model request timeout after " + timeout,
                                            "test-model",
                                            "test")));
        }

        // Apply retry if configured
        Integer maxAttempts = executionConfig.getMaxAttempts();
        if (maxAttempts != null && maxAttempts > 1) {
            Duration initialBackoff = executionConfig.getInitialBackoff();
            Duration maxBackoff = executionConfig.getMaxBackoff();
            Predicate<Throwable> retryOn = executionConfig.getRetryOn();

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
                            .filter(retryOn);
            responseFlux = responseFlux.retryWhen(retrySpec);
        }

        return responseFlux;
    }

    private ChatResponse createMockResponse() {
        return new ChatResponse(
                "test-id",
                List.of(TextBlock.builder().text("test response").build()),
                null,
                null,
                null);
    }

    /**
     * Creates a mock model backed by the supplied flux supplier, wrapped with the shared
     * timeout/retry/empty-completion handling. The supplier is re-invoked on every subscription,
     * so retry attempts can vary their behavior.
     *
     * @param responseFlux supplies the response flux for each subscription
     * @param modelName the model name for error messages
     * @return a Model instance backed by the supplier
     */
    private Model createModelReturning(
            Supplier<Flux<ChatResponse>> responseFlux, String modelName) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return ModelUtils.applyTimeoutAndRetry(
                        Flux.defer(responseFlux), options, null, modelName, "test");
            }

            @Override
            public String getModelName() {
                return modelName;
            }
        };
    }

    // ==================== Empty-completion detection (issue #2962) ====================

    @Test
    @DisplayName("Should fail when the model returns zero chunks")
    void testEmptyCompletionZeroChunks() {
        // Zero chunks — the [DONE]-only stream case
        Model emptyModel = createModelReturning(() -> Flux.empty(), "empty-model");

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Empty-completion detection is unconditional (no ExecutionConfig needed)
        StepVerifier.create(emptyModel.stream(List.of(testMsg), null, null))
                .expectErrorMatches(
                        error ->
                                error instanceof ModelException
                                        && error.getMessage().contains("empty completion"))
                .verify();
    }

    @Test
    @DisplayName("Should fail when all chunks have null or empty content")
    void testEmptyCompletionAllChunksEmpty() {
        // Two chunks, both contentless — the role-only/usage-only chunks of a well-formed but
        // empty gateway response
        Model emptyContentModel =
                createModelReturning(
                        () ->
                                Flux.just(
                                        new ChatResponse("chunk-1", List.of(), null, null, null),
                                        new ChatResponse("chunk-2", null, null, null, null)),
                        "empty-content-model");

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Contentless chunks are withheld, so the error must be the first downstream signal:
        // any emitted chunk would commit a switchOnFirst-based fallback to the primary model
        StepVerifier.create(emptyContentModel.stream(List.of(testMsg), null, null))
                .expectErrorMatches(
                        error ->
                                error instanceof ModelException
                                        && error.getMessage().contains("empty completion"))
                .verify();
    }

    @Test
    @DisplayName("Empty completion should trigger retry when maxAttempts > 1")
    void testEmptyCompletionRetriesAndSucceeds() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        Model retryingEmptyModel =
                createModelReturning(
                        () ->
                                attemptCount.incrementAndGet() < 2
                                        ? Flux.empty() // First attempt: empty
                                        : Flux.just(createMockResponse()), // Then: success
                        "retrying-empty-model");

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Should retry the empty completion and succeed on second attempt
        StepVerifier.create(retryingEmptyModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(2, attemptCount.get(), "Should have made exactly 2 attempts");
    }

    @Test
    @DisplayName(
            "Empty completion on retry must fail even if a content-bearing attempt errored first")
    void testEmptyCompletionOnRetryAfterContentBearingFailure() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        Model model =
                createModelReturning(
                        () -> {
                            if (attemptCount.incrementAndGet() == 1) {
                                // First attempt: content arrives, then the stream fails
                                // mid-flight
                                return Flux.concat(
                                        Flux.just(createMockResponse()),
                                        Flux.error(new RuntimeException("boom")));
                            }
                            // Retry: empty completion must still fail
                            return Flux.empty();
                        },
                        "retry-after-content-model");

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // The empty completion on the second attempt must surface as ModelException; the chunk
        // seen on the first attempt must not satisfy the check for the retried attempt.
        // Retry.backoff wraps the last failure in RetryExhaustedException when retries run out
        StepVerifier.create(model.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .expectErrorMatches(
                        error ->
                                error.getCause() instanceof ModelException
                                        && error.getCause()
                                                .getMessage()
                                                .contains("empty completion"))
                .verify();

        assertEquals(2, attemptCount.get(), "Should have made exactly 2 attempts");
    }

    @Test
    @DisplayName("Multimodal-only chunks (image blocks) are not empty completions")
    void testMultimodalOnlyCompletionIsNotEmpty() {
        ChatResponse imageChunk =
                new ChatResponse(
                        "chunk-1",
                        List.of(
                                ImageBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/image.jpg")
                                                        .build())
                                        .build()),
                        null,
                        null,
                        null);
        Model multimodalModel =
                createModelReturning(() -> Flux.just(imageChunk), "multimodal-model");

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // Image-only chunks carry content; the stream must complete normally
        StepVerifier.create(multimodalModel.stream(List.of(testMsg), null, null))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should release withheld contentless chunks in order once content arrives")
    void testLeadingContentlessChunksReleasedWithFirstContentChunk() {
        Model model =
                createModelReturning(
                        () ->
                                Flux.just(
                                        new ChatResponse("role-chunk", List.of(), null, null, null),
                                        createMockResponse(),
                                        new ChatResponse("usage-chunk", null, null, null, null)),
                        "leading-contentless-model");

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // The withheld chunks stream out together with the first content-bearing chunk, and
        // trailing contentless chunks pass through untouched
        StepVerifier.create(model.stream(List.of(testMsg), null, null))
                .expectNextMatches(chunk -> "role-chunk".equals(chunk.getId()))
                .expectNextMatches(
                        chunk ->
                                "test-id".equals(chunk.getId())
                                        && chunk.getContent() != null
                                        && !chunk.getContent().isEmpty())
                .expectNextMatches(chunk -> "usage-chunk".equals(chunk.getId()))
                .verifyComplete();
    }
}
