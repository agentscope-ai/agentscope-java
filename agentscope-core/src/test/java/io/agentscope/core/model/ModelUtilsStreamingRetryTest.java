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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Retry semantics for streaming model responses in {@link ModelUtils#applyTimeoutAndRetry}.
 *
 * <p>{@code retryWhen} re-subscribes its upstream. For a streaming response that has already handed
 * chunks to downstream consumers (middlewares, memory, UI), re-subscribing makes the model
 * regenerate the whole response, so the same content is delivered twice — a correctness problem, not
 * merely a latency one. It also inflates end-to-end latency by however long a full regeneration
 * takes, which is significant for long/thinking-mode responses.
 *
 * <p>Retrying is still valuable <em>before</em> the first chunk (connection setup failures, HTTP 429
 * ahead of the first token), so the guard is "stop retrying once anything has been emitted" rather
 * than "never retry".
 */
@DisplayName("ModelUtils streaming retry semantics")
class ModelUtilsStreamingRetryTest {

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);

    private static ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static String textOf(ChatResponse response) {
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    /** Mirrors the retry shape of {@code MODEL_DEFAULTS} with a 1 ms backoff to keep tests fast. */
    private static GenerateOptions retryOptions() {
        return GenerateOptions.builder()
                .executionConfig(
                        ExecutionConfig.builder()
                                .maxAttempts(3)
                                .initialBackoff(Duration.ofMillis(1))
                                .maxBackoff(Duration.ofMillis(2))
                                .backoffMultiplier(2.0)
                                .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                                .build())
                .build();
    }

    private static List<String> collect(Flux<ChatResponse> upstream, GenerateOptions options) {
        List<String> delivered =
                ModelUtils.applyTimeoutAndRetry(upstream, options, options, "test-model", "test")
                        .map(ModelUtilsStreamingRetryTest::textOf)
                        .onErrorResume(error -> Flux.empty())
                        .collectList()
                        .block(BLOCK_TIMEOUT);
        assertNotNull(delivered);
        return delivered;
    }

    @Test
    @DisplayName("mid-stream retryable error: already-delivered chunks are not replayed")
    void midStreamRetryableError_doesNotDuplicateDeliveredChunks() {
        AtomicInteger subscriptions = new AtomicInteger();

        // A model streams part of its answer, then the connection drops. IOException is classified
        // retryable by ExecutionConfig.RETRYABLE_ERRORS.
        Flux<ChatResponse> upstream =
                Flux.defer(
                        () -> {
                            subscriptions.incrementAndGet();
                            return Flux.concat(
                                    Flux.just(chunk("Hello"), chunk(" world")),
                                    Flux.error(new IOException("connection reset by peer")));
                        });

        List<String> delivered = collect(upstream, retryOptions());

        assertEquals(
                List.of("Hello", " world"),
                delivered,
                "chunks already delivered downstream must not be replayed — a retry here makes the"
                        + " model regenerate the whole response and the user sees duplicated"
                        + " content");
        assertEquals(
                1,
                subscriptions.get(),
                "upstream must not be re-subscribed after chunks were emitted");
    }

    @Test
    @DisplayName("failure before the first chunk: retry still happens")
    void failureBeforeFirstChunk_stillRetries() {
        AtomicInteger subscriptions = new AtomicInteger();

        // Fails on the first two attempts before emitting anything, then succeeds. Nothing has
        // reached downstream yet, so retrying is safe and desirable.
        Flux<ChatResponse> upstream =
                Flux.defer(
                        () -> {
                            int attempt = subscriptions.incrementAndGet();
                            if (attempt < 3) {
                                return Flux.error(new IOException("connect timed out"));
                            }
                            return Flux.just(chunk("recovered"));
                        });

        List<String> delivered = collect(upstream, retryOptions());

        assertEquals(
                List.of("recovered"),
                delivered,
                "a failure before the first chunk must still be retried");
        assertEquals(3, subscriptions.get(), "expected two retries before the successful attempt");
    }

    @Test
    @DisplayName("non-retryable mid-stream error: no retry, delivered chunks kept")
    void nonRetryableMidStreamError_noRetry() {
        AtomicInteger subscriptions = new AtomicInteger();

        // IllegalStateException is not in RETRYABLE_ERRORS.
        Flux<ChatResponse> upstream =
                Flux.defer(
                        () -> {
                            subscriptions.incrementAndGet();
                            return Flux.concat(
                                    Flux.just(chunk("partial")),
                                    Flux.error(new IllegalStateException("bad response shape")));
                        });

        List<String> delivered = collect(upstream, retryOptions());

        assertEquals(List.of("partial"), delivered);
        assertEquals(1, subscriptions.get(), "non-retryable errors must not trigger a retry");
    }

    @Test
    @DisplayName("clean stream: retry config does not alter delivery")
    void cleanStream_unaffectedByRetryConfig() {
        AtomicInteger subscriptions = new AtomicInteger();

        Flux<ChatResponse> upstream =
                Flux.defer(
                        () -> {
                            subscriptions.incrementAndGet();
                            return Flux.just(chunk("a"), chunk("b"), chunk("c"));
                        });

        List<String> delivered = collect(upstream, retryOptions());

        assertEquals(List.of("a", "b", "c"), delivered);
        assertEquals(1, subscriptions.get());
    }

    @Test
    @DisplayName("emitted state is per-subscription, not shared across subscribers")
    void emittedFlagIsPerSubscription() {
        // Every subscription emits one chunk and then fails with a retryable error. The first
        // subscription therefore ends with "something was emitted". If that state were shared
        // across subscriptions (allocated once outside Flux.defer), the second subscriber would
        // inherit it and behave differently from the first.
        Flux<ChatResponse> upstream =
                Flux.concat(
                        Flux.just(chunk("chunk")),
                        Flux.error(new IOException("connection reset by peer")));

        GenerateOptions options = retryOptions();
        Flux<ChatResponse> wrapped =
                ModelUtils.applyTimeoutAndRetry(upstream, options, options, "test-model", "test");

        List<String> first =
                wrapped.map(ModelUtilsStreamingRetryTest::textOf)
                        .onErrorResume(error -> Flux.empty())
                        .collectList()
                        .block(BLOCK_TIMEOUT);
        List<String> second =
                wrapped.map(ModelUtilsStreamingRetryTest::textOf)
                        .onErrorResume(error -> Flux.empty())
                        .collectList()
                        .block(BLOCK_TIMEOUT);

        assertEquals(List.of("chunk"), first, "first subscription should deliver one chunk");
        assertEquals(
                first,
                second,
                "a second subscription must behave identically — retry state leaking across"
                        + " subscribers would make it diverge from the first");
    }
}
