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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallAttemptEndEvent;
import io.agentscope.core.event.ModelCallAttemptFailedEvent;
import io.agentscope.core.event.ModelCallAttemptNextAction;
import io.agentscope.core.event.ModelCallAttemptRole;
import io.agentscope.core.event.ModelCallAttemptStartEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;

/**
 * Wraps a {@link Flux}{@code <}{@link ChatResponse}{@code >} with attempt lifecycle tracking
 * that emits typed {@link AgentEvent}s.
 *
 * <p>This tracker is designed to be applied <b>between</b> the source flux and the retry operator
 * ({@code Retry.backoff} / {@code Retry.fixedDelay}). When the retry operator resubscribes after
 * a failure, the {@code doOnSubscribe}/{@code doOnError}/{@code doOnComplete} operators in this
 * wrapper fire again, producing a new set of lifecycle events for each attempt.
 *
 * <p>The events emitted are:
 * <ul>
 *   <li>{@link ModelCallAttemptStartEvent} — before each attempt subscription
 *   <li>{@link ModelCallAttemptFailedEvent} — when an attempt fails (error signal)
 *   <li>{@link ModelCallAttemptEndEvent} — when an attempt succeeds (complete signal)
 * </ul>
 *
 * <p><b>Placement:</b> Apply this tracker after timeout but before retry:
 * <pre>{@code
 * flux.timeout(timeout, ...)
 *    // tracker goes here — inside the retry pipeline
 *    .transform(f -> ModelCallAttemptTracker.wrap(f, emitter, replyId, ...))
 *    .retryWhen(retrySpec)
 * }</pre>
 */
public final class ModelCallAttemptTracker {

    private ModelCallAttemptTracker() {}

    /**
     * Wraps a response flux with attempt lifecycle tracking.
     *
     * @param source the raw response flux (with timeout already applied, before retry)
     * @param emitter the event emitter to receive attempt lifecycle events
     * @param replyId the logical call/reply identifier
     * @param maxAttempts the configured maximum attempts for this call
     * @param provider the provider name (e.g., "openai", "anthropic")
     * @param modelName the model name
     * @param role primary or fallback role
     * @param hasFallback whether a fallback model is configured
     * @return a wrapped flux that emits attempt lifecycle side-effects
     */
    public static Flux<ChatResponse> wrap(
            Flux<ChatResponse> source,
            Consumer<AgentEvent> emitter,
            String replyId,
            int maxAttempts,
            String provider,
            String modelName,
            ModelCallAttemptRole role,
            boolean hasFallback) {

        AtomicInteger attemptCounter = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong();
        AtomicReference<ChatUsage> lastUsage = new AtomicReference<>();

        return source.doOnSubscribe(
                        sub -> {
                            int attempt = attemptCounter.incrementAndGet();
                            startTime.set(System.currentTimeMillis());
                            emitter.accept(
                                    new ModelCallAttemptStartEvent(
                                            replyId,
                                            attempt,
                                            maxAttempts,
                                            provider,
                                            modelName,
                                            role));
                        })
                .doOnNext(
                        chunk -> {
                            if (chunk.getUsage() != null) {
                                lastUsage.set(chunk.getUsage());
                            }
                        })
                .doOnError(
                        error -> {
                            int attempt = attemptCounter.get();
                            boolean retryable = ModelCallFailureClassifier.isRetryable(error);
                            ModelCallAttemptNextAction nextAction =
                                    computeNextAction(
                                            attempt, maxAttempts, retryable, role, hasFallback);
                            emitter.accept(
                                    new ModelCallAttemptFailedEvent(
                                            replyId,
                                            attempt,
                                            maxAttempts,
                                            ModelCallFailureClassifier.classify(error),
                                            retryable,
                                            nextAction,
                                            ModelCallFailureClassifier.sanitizeErrorCode(error),
                                            ModelCallFailureClassifier.sanitizeMessage(error),
                                            role));
                        })
                .doOnComplete(
                        () -> {
                            int attempt = attemptCounter.get();
                            long latency = System.currentTimeMillis() - startTime.get();
                            emitter.accept(
                                    new ModelCallAttemptEndEvent(
                                            replyId,
                                            attempt,
                                            true,
                                            lastUsage.get(),
                                            latency,
                                            role));
                        });
    }

    /**
     * Backward-compatible wrap without hasFallback (assumes no fallback).
     */
    public static Flux<ChatResponse> wrap(
            Flux<ChatResponse> source,
            Consumer<AgentEvent> emitter,
            String replyId,
            int maxAttempts,
            String provider,
            String modelName,
            ModelCallAttemptRole role) {
        return wrap(source, emitter, replyId, maxAttempts, provider, modelName, role, false);
    }

    /**
     * Computes the next action after a failed attempt.
     *
     * <p>Logic:
     * <ul>
     *   <li>If the error is retryable and there are remaining attempts → RETRY</li>
     *   <li>If retries exhausted but fallback is available → FALLBACK</li>
     *   <li>Otherwise → FAIL</li>
     * </ul>
     */
    static ModelCallAttemptNextAction computeNextAction(
            int attemptIndex,
            int maxAttempts,
            boolean retryable,
            ModelCallAttemptRole role,
            boolean hasFallback) {
        if (retryable && attemptIndex < maxAttempts) {
            return ModelCallAttemptNextAction.RETRY;
        }
        if (role == ModelCallAttemptRole.PRIMARY && hasFallback) {
            return ModelCallAttemptNextAction.FALLBACK;
        }
        return ModelCallAttemptNextAction.FAIL;
    }
}
