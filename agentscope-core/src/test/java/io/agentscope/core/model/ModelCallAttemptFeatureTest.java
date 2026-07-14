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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ModelCallAttemptEndEvent;
import io.agentscope.core.event.ModelCallAttemptFailedEvent;
import io.agentscope.core.event.ModelCallAttemptFailureCategory;
import io.agentscope.core.event.ModelCallAttemptNextAction;
import io.agentscope.core.event.ModelCallAttemptRole;
import io.agentscope.core.event.ModelCallAttemptStartEvent;
import io.agentscope.core.event.ModelFallbackActivatedEvent;
import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Comprehensive tests for the model call attempt feature, covering nextAction logic,
 * AttemptEventContext integration, usage extraction, backward compatibility, JSON round-trips,
 * and 5-arg applyTimeoutAndRetry behavior.
 */
class ModelCallAttemptFeatureTest {

    private static final String REPLY = "r1";
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4o";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ========================================================================
    // 1. nextAction=RETRY when retryable and attempts remaining
    // ========================================================================

    @Test
    void nextActionRetry_whenRetryableAndAttemptsRemaining() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.error(new HttpTransportException("rate limited", 429, ""));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY,
                        false);

        StepVerifier.create(tracked).verifyError();

        assertEquals(2, events.size());
        ModelCallAttemptFailedEvent failed = (ModelCallAttemptFailedEvent) events.get(1);
        assertEquals(ModelCallAttemptNextAction.RETRY, failed.getNextAction());
        assertEquals(1, failed.getAttemptIndex());
        assertEquals(3, failed.getMaxAttempts());
        assertTrue(failed.isRetryable());
    }

    // ========================================================================
    // 2. nextAction=FALLBACK when retries exhausted but fallback available
    // ========================================================================

    @Test
    void nextActionFallback_whenRetriesExhaustedAndFallbackAvailable() {
        List<AgentEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            calls.incrementAndGet();
                            return Flux.error(new HttpTransportException("rate limited", 429, ""));
                        });

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY,
                        true);

        // Apply retry to reach maxAttempts
        Flux<ChatResponse> withRetry =
                tracked.retryWhen(
                        reactor.util.retry.Retry.backoff(2, Duration.ofMillis(10))
                                .maxBackoff(Duration.ofMillis(50))
                                .filter(e -> true));

        StepVerifier.create(withRetry).verifyError();

        // Find the last failed event (attempt 3 = maxAttempts)
        List<ModelCallAttemptFailedEvent> failedEvents =
                events.stream()
                        .filter(e -> e instanceof ModelCallAttemptFailedEvent)
                        .map(e -> (ModelCallAttemptFailedEvent) e)
                        .toList();

        ModelCallAttemptFailedEvent lastFailed = failedEvents.get(failedEvents.size() - 1);
        assertEquals(3, lastFailed.getAttemptIndex());
        assertEquals(ModelCallAttemptNextAction.FALLBACK, lastFailed.getNextAction());
    }

    // ========================================================================
    // 3. nextAction=FAIL when retries exhausted and no fallback
    // ========================================================================

    @Test
    void nextActionFail_whenRetriesExhaustedAndNoFallback() {
        List<AgentEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            calls.incrementAndGet();
                            return Flux.error(new HttpTransportException("rate limited", 429, ""));
                        });

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY,
                        false);

        Flux<ChatResponse> withRetry =
                tracked.retryWhen(
                        reactor.util.retry.Retry.backoff(2, Duration.ofMillis(10))
                                .maxBackoff(Duration.ofMillis(50))
                                .filter(e -> true));

        StepVerifier.create(withRetry).verifyError();

        List<ModelCallAttemptFailedEvent> failedEvents =
                events.stream()
                        .filter(e -> e instanceof ModelCallAttemptFailedEvent)
                        .map(e -> (ModelCallAttemptFailedEvent) e)
                        .toList();

        ModelCallAttemptFailedEvent lastFailed = failedEvents.get(failedEvents.size() - 1);
        assertEquals(3, lastFailed.getAttemptIndex());
        assertEquals(ModelCallAttemptNextAction.FAIL, lastFailed.getNextAction());
    }

    // ========================================================================
    // 4. nextAction=FAIL for non-retryable errors
    // ========================================================================

    @Test
    void nextActionFail_forNonRetryableErrors() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.error(new HttpTransportException("unauthorized", 401, ""));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY,
                        false);

        StepVerifier.create(tracked).verifyError();

        ModelCallAttemptFailedEvent failed = (ModelCallAttemptFailedEvent) events.get(1);
        assertEquals(1, failed.getAttemptIndex());
        assertFalse(failed.isRetryable());
        assertEquals(ModelCallAttemptNextAction.FAIL, failed.getNextAction());
    }

    // ========================================================================
    // 5. AttemptEventContext integration via ExecutionConfig
    // ========================================================================

    @Test
    void attemptEventContextIntegrationViaExecutionConfig() {
        List<AgentEvent> events = new ArrayList<>();

        AttemptEventContext ctx =
                new AttemptEventContext(events::add, REPLY, ModelCallAttemptRole.PRIMARY);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(1)
                                        .attemptEventContext(ctx)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(raw, opts, null, MODEL, PROVIDER);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        assertFalse(events.isEmpty(), "Events must be emitted via AttemptEventContext");
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, events.get(0).getType());
    }

    // ========================================================================
    // 6. AttemptEventContext.isComplete()
    // ========================================================================

    @Test
    void attemptEventContext_isComplete_withNullFields() {
        AttemptEventContext nullEmitter =
                new AttemptEventContext(null, REPLY, ModelCallAttemptRole.PRIMARY);
        assertFalse(nullEmitter.isComplete(), "Should not be complete with null emitter");

        AttemptEventContext nullReplyId =
                new AttemptEventContext(events -> {}, null, ModelCallAttemptRole.PRIMARY);
        assertFalse(nullReplyId.isComplete(), "Should not be complete with null replyId");

        AttemptEventContext nullRole = new AttemptEventContext(events -> {}, REPLY, null);
        assertFalse(nullRole.isComplete(), "Should not be complete with null role");
    }

    @Test
    void attemptEventContext_isComplete_withAllFields() {
        AttemptEventContext complete =
                new AttemptEventContext(events -> {}, REPLY, ModelCallAttemptRole.PRIMARY);
        assertTrue(complete.isComplete(), "Should be complete with all fields set");
    }

    // ========================================================================
    // 7. Usage extraction in ModelCallAttemptEndEvent
    // ========================================================================

    @Test
    void usageExtractionInModelCallAttemptEndEvent() {
        List<AgentEvent> events = new ArrayList<>();
        ChatUsage usage = new ChatUsage(100, 50, 1.5);

        Flux<ChatResponse> source =
                Flux.just(new ChatResponse("id-1", List.of(), usage, null, "stop"));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        1,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(tracked).expectNextCount(1).verifyComplete();

        assertEquals(2, events.size());
        ModelCallAttemptEndEvent end = (ModelCallAttemptEndEvent) events.get(1);
        assertNotNull(end.getUsage(), "Usage must be extracted from ChatResponse");
        assertEquals(100, end.getUsage().getInputTokens());
        assertEquals(50, end.getUsage().getOutputTokens());
        assertEquals(1.5, end.getUsage().getTime(), 0.001);
    }

    // ========================================================================
    // 8. Backward compat: 7-arg wrap without hasFallback
    // ========================================================================

    @Test
    void backwardCompat_sevenArgWrap_withoutHasFallback() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.error(new HttpTransportException("rate limited", 429, ""));

        // Use the 7-arg wrap (no hasFallback parameter)
        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(tracked).verifyError();

        ModelCallAttemptFailedEvent failed = (ModelCallAttemptFailedEvent) events.get(1);
        // With hasFallback defaulting to false, a retryable error with attempts remaining
        // still produces RETRY nextAction (because attemptIndex=1 < maxAttempts=3)
        assertEquals(ModelCallAttemptNextAction.RETRY, failed.getNextAction());

        // When retried to exhaustion, should produce FAIL (not FALLBACK)
        List<AgentEvent> events2 = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        Flux<ChatResponse> source2 =
                Flux.defer(
                        () -> {
                            calls.incrementAndGet();
                            return Flux.error(new HttpTransportException("rate limited", 429, ""));
                        });

        Flux<ChatResponse> tracked2 =
                ModelCallAttemptTracker.wrap(
                        source2,
                        events2::add,
                        REPLY,
                        2,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        Flux<ChatResponse> withRetry =
                tracked2.retryWhen(
                        reactor.util.retry.Retry.backoff(1, Duration.ofMillis(10))
                                .maxBackoff(Duration.ofMillis(50))
                                .filter(e -> true));

        StepVerifier.create(withRetry).verifyError();

        List<ModelCallAttemptFailedEvent> failedEvents =
                events2.stream()
                        .filter(e -> e instanceof ModelCallAttemptFailedEvent)
                        .map(e -> (ModelCallAttemptFailedEvent) e)
                        .toList();

        // Last attempt exhausted → FAIL because hasFallback defaults to false
        ModelCallAttemptFailedEvent lastFailed = failedEvents.get(failedEvents.size() - 1);
        assertEquals(ModelCallAttemptNextAction.FAIL, lastFailed.getNextAction());
    }

    // ========================================================================
    // 9. Failed event JSON round-trip with nextAction and maxAttempts
    // ========================================================================

    @Test
    void failedEventJsonRoundTrip_withNextActionAndMaxAttempts() throws Exception {
        ModelCallAttemptFailedEvent original =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        2,
                        3,
                        ModelCallAttemptFailureCategory.RATE_LIMIT,
                        true,
                        ModelCallAttemptNextAction.RETRY,
                        "HTTP_429",
                        "rate limited",
                        ModelCallAttemptRole.PRIMARY);

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertSame(ModelCallAttemptFailedEvent.class, deserialized.getClass());
        ModelCallAttemptFailedEvent rt = (ModelCallAttemptFailedEvent) deserialized;
        assertEquals(REPLY, rt.getReplyId());
        assertEquals(2, rt.getAttemptIndex());
        assertEquals(3, rt.getMaxAttempts());
        assertEquals(ModelCallAttemptFailureCategory.RATE_LIMIT, rt.getFailureCategory());
        assertTrue(rt.isRetryable());
        assertEquals(ModelCallAttemptNextAction.RETRY, rt.getNextAction());
        assertEquals("HTTP_429", rt.getErrorCode());
        assertEquals("rate limited", rt.getErrorMessage());
        assertEquals(ModelCallAttemptRole.PRIMARY, rt.getRole());
    }

    // ========================================================================
    // 10. ModelFallbackActivatedEvent with correct replyId and failedAttemptCount
    // ========================================================================

    @Test
    void modelFallbackActivatedEvent_jsonRoundTrip() throws Exception {
        ModelFallbackActivatedEvent original =
                new ModelFallbackActivatedEvent(REPLY, 3, "gpt-4o", "claude-sonnet");

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertSame(ModelFallbackActivatedEvent.class, deserialized.getClass());
        ModelFallbackActivatedEvent rt = (ModelFallbackActivatedEvent) deserialized;
        assertEquals(REPLY, rt.getReplyId());
        assertEquals(3, rt.getFailedAttemptCount());
        assertEquals("gpt-4o", rt.getFromModel());
        assertEquals("claude-sonnet", rt.getToModel());
    }

    // ========================================================================
    // 11. Fallback model attempt events have FALLBACK role
    // ========================================================================

    @Test
    void fallbackModelAttemptEvents_haveFallbackRole() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.FALLBACK,
                        false);

        StepVerifier.create(tracked).expectNextCount(1).verifyComplete();

        ModelCallAttemptStartEvent start = (ModelCallAttemptStartEvent) events.get(0);
        assertEquals(ModelCallAttemptRole.FALLBACK, start.getRole());

        ModelCallAttemptEndEvent end = (ModelCallAttemptEndEvent) events.get(1);
        assertEquals(ModelCallAttemptRole.FALLBACK, end.getRole());
    }

    // ========================================================================
    // 12. computeNextAction utility method tests
    // ========================================================================

    @Test
    void computeNextAction_retryableWithAttemptsRemaining_returnsRetry() {
        assertEquals(
                ModelCallAttemptNextAction.RETRY,
                ModelCallAttemptTracker.computeNextAction(
                        1, 3, true, ModelCallAttemptRole.PRIMARY, false));
    }

    @Test
    void computeNextAction_retryableExhaustedWithFallback_returnsFallback() {
        assertEquals(
                ModelCallAttemptNextAction.FALLBACK,
                ModelCallAttemptTracker.computeNextAction(
                        3, 3, true, ModelCallAttemptRole.PRIMARY, true));
    }

    @Test
    void computeNextAction_nonRetryablePrimaryWithFallback_returnsFallback() {
        assertEquals(
                ModelCallAttemptNextAction.FALLBACK,
                ModelCallAttemptTracker.computeNextAction(
                        1, 3, false, ModelCallAttemptRole.PRIMARY, true));
    }

    @Test
    void computeNextAction_nonRetryableFallbackRoleWithFallback_returnsFail() {
        assertEquals(
                ModelCallAttemptNextAction.FAIL,
                ModelCallAttemptTracker.computeNextAction(
                        1, 3, false, ModelCallAttemptRole.FALLBACK, true));
    }

    @Test
    void computeNextAction_retryableMidAttempt_returnsRetry() {
        assertEquals(
                ModelCallAttemptNextAction.RETRY,
                ModelCallAttemptTracker.computeNextAction(
                        2, 3, true, ModelCallAttemptRole.PRIMARY, false));
    }

    @Test
    void computeNextAction_retryableExhaustedNoFallback_returnsFail() {
        assertEquals(
                ModelCallAttemptNextAction.FAIL,
                ModelCallAttemptTracker.computeNextAction(
                        3, 3, true, ModelCallAttemptRole.PRIMARY, false));
    }

    // ========================================================================
    // 13. 5-arg applyTimeoutAndRetry reads AttemptEventContext from config
    // ========================================================================

    @Test
    void fiveArgApplyTimeoutAndRetry_readsAttemptEventContextFromConfig() {
        List<AgentEvent> events = new ArrayList<>();

        AttemptEventContext ctx =
                new AttemptEventContext(events::add, REPLY, ModelCallAttemptRole.PRIMARY);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(1)
                                        .attemptEventContext(ctx)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(raw, opts, null, MODEL, PROVIDER);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        assertFalse(events.isEmpty(), "Attempt events must be emitted");
        assertTrue(
                events.stream().anyMatch(e -> e.getType() == AgentEventType.MODEL_ATTEMPT_START),
                "Must have MODEL_ATTEMPT_START event");
        assertTrue(
                events.stream().anyMatch(e -> e.getType() == AgentEventType.MODEL_ATTEMPT_END),
                "Must have MODEL_ATTEMPT_END event");

        ModelCallAttemptStartEvent start =
                events.stream()
                        .filter(e -> e instanceof ModelCallAttemptStartEvent)
                        .map(e -> (ModelCallAttemptStartEvent) e)
                        .findFirst()
                        .orElseThrow();
        assertEquals(REPLY, start.getReplyId());
        assertEquals(ModelCallAttemptRole.PRIMARY, start.getRole());
    }

    // ========================================================================
    // 14. 5-arg applyTimeoutAndRetry without AttemptEventContext produces no attempt events
    // ========================================================================

    @Test
    void fiveArgApplyTimeoutAndRetry_withoutAttemptEventContext_noAttemptEvents() {
        List<AgentEvent> events = new ArrayList<>();

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(1)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(raw, opts, null, MODEL, PROVIDER);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        assertTrue(
                events.isEmpty(),
                "No attempt events should be emitted without AttemptEventContext");
    }

    // ========================================================================
    // Additional: 5-arg applyTimeoutAndRetry with retry and AttemptEventContext
    // ========================================================================

    @Test
    void fiveArgApplyTimeoutAndRetry_withRetryAndContext_emitsEventsPerAttempt() {
        List<AgentEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        AttemptEventContext ctx =
                new AttemptEventContext(events::add, REPLY, ModelCallAttemptRole.PRIMARY);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .attemptEventContext(ctx)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            if (calls.incrementAndGet() <= 2) {
                                return Flux.error(
                                        new HttpTransportException("rate limited", 429, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(raw, opts, null, MODEL, PROVIDER);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        // Attempt 1: START + FAILED, Attempt 2: START + FAILED, Attempt 3: START + END
        assertEquals(6, events.size());
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, events.get(0).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_FAILED, events.get(1).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, events.get(2).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_FAILED, events.get(3).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, events.get(4).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_END, events.get(5).getType());
    }

    // ========================================================================
    // Additional: 5-arg applyTimeoutAndRetry with FALLBACK role in context
    // ========================================================================

    @Test
    void fiveArgApplyTimeoutAndRetry_withFallbackRole_emitsEventsWithFallbackRole() {
        List<AgentEvent> events = new ArrayList<>();

        AttemptEventContext ctx =
                new AttemptEventContext(events::add, REPLY, ModelCallAttemptRole.FALLBACK);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(1)
                                        .attemptEventContext(ctx)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(raw, opts, null, MODEL, PROVIDER);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ModelCallAttemptStartEvent start =
                events.stream()
                        .filter(e -> e instanceof ModelCallAttemptStartEvent)
                        .map(e -> (ModelCallAttemptStartEvent) e)
                        .findFirst()
                        .orElseThrow();
        assertEquals(ModelCallAttemptRole.FALLBACK, start.getRole());

        ModelCallAttemptEndEvent end =
                events.stream()
                        .filter(e -> e instanceof ModelCallAttemptEndEvent)
                        .map(e -> (ModelCallAttemptEndEvent) e)
                        .findFirst()
                        .orElseThrow();
        assertEquals(ModelCallAttemptRole.FALLBACK, end.getRole());
    }

    // ========================================================================
    // Additional: nextAction=FALLBACK for non-retryable PRIMARY with hasFallback
    // (end-to-end via wrap)
    // ========================================================================

    @Test
    void nextActionFallback_nonRetryablePrimaryWithFallbackAvailable() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.error(new HttpTransportException("unauthorized", 401, ""));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY,
                        true);

        StepVerifier.create(tracked).verifyError();

        ModelCallAttemptFailedEvent failed = (ModelCallAttemptFailedEvent) events.get(1);
        assertFalse(failed.isRetryable());
        // Non-retryable error with PRIMARY role and fallback available → FALLBACK
        assertEquals(ModelCallAttemptNextAction.FALLBACK, failed.getNextAction());
    }

    // ========================================================================
    // Additional: nextAction=FAIL for non-retryable FALLBACK role even with hasFallback
    // (end-to-end via wrap)
    // ========================================================================

    @Test
    void nextActionFail_nonRetryableFallbackRoleEvenWithHasFallback() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.error(new HttpTransportException("unauthorized", 401, ""));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.FALLBACK,
                        true);

        StepVerifier.create(tracked).verifyError();

        ModelCallAttemptFailedEvent failed = (ModelCallAttemptFailedEvent) events.get(1);
        assertFalse(failed.isRetryable());
        // Non-retryable error with FALLBACK role → FAIL regardless of hasFallback
        assertEquals(ModelCallAttemptNextAction.FAIL, failed.getNextAction());
    }

    // ========================================================================
    // Additional: Failed event JSON round-trip with FALLBACK nextAction
    // ========================================================================

    @Test
    void failedEventJsonRoundTrip_withFallbackNextAction() throws Exception {
        ModelCallAttemptFailedEvent original =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        3,
                        3,
                        ModelCallAttemptFailureCategory.RATE_LIMIT,
                        true,
                        ModelCallAttemptNextAction.FALLBACK,
                        "HTTP_429",
                        "rate limited",
                        ModelCallAttemptRole.PRIMARY);

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        ModelCallAttemptFailedEvent rt = (ModelCallAttemptFailedEvent) deserialized;
        assertEquals(ModelCallAttemptNextAction.FALLBACK, rt.getNextAction());
        assertEquals(3, rt.getMaxAttempts());
    }

    // ========================================================================
    // Additional: Failed event JSON round-trip with FAIL nextAction
    // ========================================================================

    @Test
    void failedEventJsonRoundTrip_withFailNextAction() throws Exception {
        ModelCallAttemptFailedEvent original =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        1,
                        3,
                        ModelCallAttemptFailureCategory.AUTHENTICATION,
                        false,
                        ModelCallAttemptNextAction.FAIL,
                        "HTTP_401",
                        "unauthorized",
                        ModelCallAttemptRole.PRIMARY);

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        ModelCallAttemptFailedEvent rt = (ModelCallAttemptFailedEvent) deserialized;
        assertEquals(ModelCallAttemptNextAction.FAIL, rt.getNextAction());
        assertEquals(1, rt.getAttemptIndex());
        assertEquals(3, rt.getMaxAttempts());
        assertEquals(ModelCallAttemptFailureCategory.AUTHENTICATION, rt.getFailureCategory());
    }

    // ========================================================================
    // Additional: ModelFallbackActivatedEvent with null replyId round-trip
    // ========================================================================

    @Test
    void modelFallbackActivatedEvent_nullReplyId_roundTrip() throws Exception {
        ModelFallbackActivatedEvent original =
                new ModelFallbackActivatedEvent(null, 2, "gpt-4o", "claude-sonnet");

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        ModelFallbackActivatedEvent rt = (ModelFallbackActivatedEvent) deserialized;
        assertNull(rt.getReplyId());
        assertEquals(2, rt.getFailedAttemptCount());
        assertEquals("gpt-4o", rt.getFromModel());
        assertEquals("claude-sonnet", rt.getToModel());
    }

    // ========================================================================
    // Additional: Usage extraction with ChatUsage(int, int, int, double)
    // ========================================================================

    @Test
    void usageExtraction_withFourArgChatUsage() {
        List<AgentEvent> events = new ArrayList<>();
        ChatUsage usage = new ChatUsage(200, 100, 50, 2.5);

        Flux<ChatResponse> source =
                Flux.just(new ChatResponse("id-2", List.of(), usage, null, "stop"));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        1,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(tracked).expectNextCount(1).verifyComplete();

        ModelCallAttemptEndEvent end = (ModelCallAttemptEndEvent) events.get(1);
        assertNotNull(end.getUsage());
        assertEquals(200, end.getUsage().getInputTokens());
        assertEquals(100, end.getUsage().getOutputTokens());
        assertEquals(50, end.getUsage().getCachedTokens());
        assertEquals(2.5, end.getUsage().getTime(), 0.001);
    }

    // ========================================================================
    // Additional: End-to-end nextAction flow with all 3 outcomes
    // ========================================================================

    @Test
    void nextAction_allThreeOutcomes_inRetrySequence() {
        // Simulate: attempt 1 (RETRY), attempt 2 (RETRY), attempt 3 (FAIL)
        List<AgentEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            calls.incrementAndGet();
                            return Flux.error(new HttpTransportException("rate limited", 429, ""));
                        });

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY,
                        false);

        Flux<ChatResponse> withRetry =
                tracked.retryWhen(
                        reactor.util.retry.Retry.backoff(2, Duration.ofMillis(10))
                                .maxBackoff(Duration.ofMillis(50))
                                .filter(e -> true));

        StepVerifier.create(withRetry).verifyError();

        List<ModelCallAttemptFailedEvent> failedEvents =
                events.stream()
                        .filter(e -> e instanceof ModelCallAttemptFailedEvent)
                        .map(e -> (ModelCallAttemptFailedEvent) e)
                        .toList();

        assertEquals(3, failedEvents.size());
        assertEquals(ModelCallAttemptNextAction.RETRY, failedEvents.get(0).getNextAction());
        assertEquals(ModelCallAttemptNextAction.RETRY, failedEvents.get(1).getNextAction());
        assertEquals(ModelCallAttemptNextAction.FAIL, failedEvents.get(2).getNextAction());
    }

    // ========================================================================
    // Additional: Deprecated backward-compat constructor for FailedEvent
    // ========================================================================

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedFailedEventConstructor_setsNextActionFromRetryable() {
        // retryable=true → RETRY
        ModelCallAttemptFailedEvent retryableEvent =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        1,
                        ModelCallAttemptFailureCategory.RATE_LIMIT,
                        true,
                        "HTTP_429",
                        "rate limited",
                        ModelCallAttemptRole.PRIMARY);
        assertEquals(ModelCallAttemptNextAction.RETRY, retryableEvent.getNextAction());
        assertEquals(0, retryableEvent.getMaxAttempts());

        // retryable=false → FAIL
        ModelCallAttemptFailedEvent nonRetryableEvent =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        1,
                        ModelCallAttemptFailureCategory.AUTHENTICATION,
                        false,
                        "HTTP_401",
                        "unauthorized",
                        ModelCallAttemptRole.PRIMARY);
        assertEquals(ModelCallAttemptNextAction.FAIL, nonRetryableEvent.getNextAction());
        assertEquals(0, nonRetryableEvent.getMaxAttempts());
    }
}
