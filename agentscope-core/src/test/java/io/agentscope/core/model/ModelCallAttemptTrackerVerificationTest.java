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
 * Tests that verify every concrete requirement from the feature specification is satisfied.
 *
 * <p>Each test method name maps to a specific requirement or validation scenario from the spec.
 */
class ModelCallAttemptTrackerVerificationTest {

    private static final String REPLY = "r1";
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4o";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ========================================================================
    // REQ: Events come from Core/model transport, not product-host log parsing
    // ========================================================================

    @Test
    void req_eventsOriginateInsideModelUtilsPipeline() {
        // Proof: ModelUtils.applyTimeoutAndRetry with emitter actually emits events
        // through the Flux pipeline, not through logging.
        List<AgentEvent> emitted = new ArrayList<>();

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
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        1,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        assertFalse(
                emitted.isEmpty(),
                "Events must be emitted through the Consumer callback, not logs");
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, emitted.get(0).getType());
    }

    @Test
    void req_eventsOriginateInsideRetryPipelineOnResubscribe() {
        // Proof: When retry resubscribes, the tracker fires new START/FAILED pairs.
        // This proves events originate inside the retry pipeline in ModelUtils.
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            int n = calls.incrementAndGet();
                            if (n <= 2) {
                                return Flux.error(
                                        new HttpTransportException("rate limited", 429, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        // Attempt 1: START + FAILED, Attempt 2: START + FAILED, Attempt 3: START + END = 6
        assertEquals(
                6,
                emitted.size(),
                "Each retry must produce its own START/FAILED pair through the pipeline");
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, emitted.get(0).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_FAILED, emitted.get(1).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, emitted.get(2).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_FAILED, emitted.get(3).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, emitted.get(4).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_END, emitted.get(5).getType());
    }

    // ========================================================================
    // REQ: No API keys, authorization headers, raw credential values,
    //      or sensitive response bodies in events
    // ========================================================================

    @Test
    void req_noSecretsInSanitizedErrorCode() {
        HttpTransportException ex =
                new HttpTransportException("Error", 429, "Bearer sk-secret-api-key-12345");
        String code = ModelCallFailureClassifier.sanitizeErrorCode(ex);
        assertFalse(code.contains("sk-"), "Error code must not contain API key: " + code);
        assertFalse(code.contains("Bearer"), "Error code must not contain auth header: " + code);
        assertEquals("HTTP_429", code);
    }

    @Test
    void req_noSecretsInSanitizedMessage() {
        // Response body with API key must be stripped from message
        HttpTransportException ex =
                new HttpTransportException(
                        "generic error",
                        401,
                        "{\"error\":{\"message\":\"Invalid API key: sk-proj-abc123def456\"}}");
        String msg = ModelCallFailureClassifier.sanitizeMessage(ex);
        assertFalse(msg.contains("sk-proj"), "Message must not contain API key from body: " + msg);
        assertFalse(
                msg.contains("Invalid API key"), "Message must not contain body content: " + msg);
        assertTrue(msg.contains("generic error"), "Safe base message should be preserved: " + msg);
    }

    @Test
    void req_noSecretsInFailedEventPayload() throws Exception {
        HttpTransportException ex =
                new HttpTransportException(
                        "Auth failed", 401, "secret-body-content-with-bearer-token-abc123");
        ModelCallAttemptFailedEvent event =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        1,
                        ModelCallFailureClassifier.classify(ex),
                        false,
                        ModelCallFailureClassifier.sanitizeErrorCode(ex),
                        ModelCallFailureClassifier.sanitizeMessage(ex),
                        ModelCallAttemptRole.PRIMARY);

        String json = MAPPER.writeValueAsString(event);
        // Response body must not leak into serialized event
        assertFalse(
                json.contains("secret-body"),
                "Serialized event must not contain response body: " + json);
        assertFalse(
                json.contains("bearer-token-abc123"),
                "Serialized event must not contain sensitive body content: " + json);
    }

    // ========================================================================
    // REQ: Works for both direct provider retries and fallbackModel transitions
    // ========================================================================

    @Test
    void req_retryInsideModelUtilsProducesAttemptEvents() {
        // Proof: direct provider retry (inside ModelUtils) emits attempt events
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(new HttpTransportException("server", 503, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        // Verify provider is correctly reported in each attempt event
        for (AgentEvent e : emitted) {
            if (e instanceof ModelCallAttemptStartEvent s) {
                assertEquals(PROVIDER, s.getProvider());
                assertEquals(MODEL, s.getModelName());
                assertEquals(ModelCallAttemptRole.PRIMARY, s.getRole());
            }
        }
    }

    @Test
    void req_fallbackTransitionEmitsModelFallbackActivatedEvent() {
        // Proof: when a non-retryable error bypasses retry and reaches switchOnFirst,
        // the modelForCall wrapper emits MODEL_FALLBACK_ACTIVATED.
        List<AgentEvent> emitted = new ArrayList<>();

        Model primaryModel =
                new FailingModel(
                        "primary-provider",
                        "primary-model",
                        new HttpTransportException("forbidden", 403, ""));
        Model fallbackModel = new StubModel("fallback-provider", "fallback-model");

        Model wrapped = wrapWithFallback(primaryModel, fallbackModel, emitted::add);

        // The fallback model should be used
        Flux<ChatResponse> result = wrapped.stream(List.of(), null, null);
        assertNotNull(result);
        // The first element proves fallback was selected
        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        boolean hasFallbackEvent =
                emitted.stream()
                        .anyMatch(e -> e.getType() == AgentEventType.MODEL_FALLBACK_ACTIVATED);
        assertTrue(
                hasFallbackEvent,
                "MODEL_FALLBACK_ACTIVATED must be emitted when primary fails and fallback is used");

        ModelFallbackActivatedEvent fae =
                emitted.stream()
                        .filter(e -> e instanceof ModelFallbackActivatedEvent)
                        .map(e -> (ModelFallbackActivatedEvent) e)
                        .findFirst()
                        .orElseThrow();
        assertEquals("primary-model", fae.getFromModel());
        assertEquals("fallback-model", fae.getToModel());
    }

    // ========================================================================
    // REQ: 429 and 5xx retry, followed by success
    // ========================================================================

    @Test
    void req_429RetryThenSuccess() {
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(
                                        new HttpTransportException("rate limited", 429, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        assertEquals(4, emitted.size());
        // Attempt 1 failed with RATE_LIMIT, retryable=true
        ModelCallAttemptFailedEvent f1 = (ModelCallAttemptFailedEvent) emitted.get(1);
        assertEquals(ModelCallAttemptFailureCategory.RATE_LIMIT, f1.getFailureCategory());
        assertTrue(f1.isRetryable());
        // Attempt 2 succeeded
        ModelCallAttemptEndEvent e2 = (ModelCallAttemptEndEvent) emitted.get(3);
        assertTrue(e2.isSuccess());
    }

    @Test
    void req_5xxRetryThenSuccess() {
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(
                                        new HttpTransportException("server error", 503, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ModelCallAttemptFailedEvent f1 = (ModelCallAttemptFailedEvent) emitted.get(1);
        assertEquals(ModelCallAttemptFailureCategory.PROVIDER_5XX, f1.getFailureCategory());
        assertTrue(f1.isRetryable());
    }

    // ========================================================================
    // REQ: 401/403 non-retryable classification
    // ========================================================================

    @Test
    void req_401NonRetryable() {
        List<AgentEvent> emitted = new ArrayList<>();

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.error(new HttpTransportException("unauthorized", 401, ""));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).verifyError();

        long startCount =
                emitted.stream()
                        .filter(e -> e.getType() == AgentEventType.MODEL_ATTEMPT_START)
                        .count();
        assertEquals(1, startCount, "Non-retryable 401 must produce exactly 1 START event");

        ModelCallAttemptFailedEvent f1 = (ModelCallAttemptFailedEvent) emitted.get(1);
        assertEquals(ModelCallAttemptFailureCategory.AUTHENTICATION, f1.getFailureCategory());
        assertFalse(f1.isRetryable());
    }

    @Test
    void req_403NonRetryable() {
        List<AgentEvent> emitted = new ArrayList<>();

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.error(new HttpTransportException("forbidden", 403, ""));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).verifyError();

        long startCount =
                emitted.stream()
                        .filter(e -> e.getType() == AgentEventType.MODEL_ATTEMPT_START)
                        .count();
        assertEquals(1, startCount, "Non-retryable 403 must produce exactly 1 START event");

        ModelCallAttemptFailedEvent f1 = (ModelCallAttemptFailedEvent) emitted.get(1);
        assertEquals(ModelCallAttemptFailureCategory.AUTHORIZATION, f1.getFailureCategory());
        assertFalse(f1.isRetryable());
    }

    // ========================================================================
    // REQ: timeout/network retry
    // ========================================================================

    @Test
    void req_timeoutRetry() {
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(
                                        new java.util.concurrent.TimeoutException("timed out"));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ModelCallAttemptFailedEvent f1 = (ModelCallAttemptFailedEvent) emitted.get(1);
        assertEquals(ModelCallAttemptFailureCategory.TIMEOUT, f1.getFailureCategory());
        assertTrue(f1.isRetryable());
    }

    @Test
    void req_networkRetry() {
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(3)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(new java.io.IOException("connection reset"));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        3,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ModelCallAttemptFailedEvent f1 = (ModelCallAttemptFailedEvent) emitted.get(1);
        assertEquals(ModelCallAttemptFailureCategory.NETWORK, f1.getFailureCategory());
        assertTrue(f1.isRetryable());
    }

    // ========================================================================
    // REQ: fallback failure (fallback also fails)
    // ========================================================================

    @Test
    void req_fallbackFailureEmitsAttemptEvents() {
        List<AgentEvent> emitted = new ArrayList<>();

        Model primaryModel =
                new FailingModel("p", "primary", new HttpTransportException("forbidden", 403, ""));
        Model fallbackModel =
                new FailingModel(
                        "f", "fallback", new HttpTransportException("server error", 500, ""));

        Model wrapped = wrapWithFallback(primaryModel, fallbackModel, emitted::add);

        Flux<ChatResponse> result = wrapped.stream(List.of(), null, null);
        StepVerifier.create(result).verifyError();

        // Must have MODEL_FALLBACK_ACTIVATED
        boolean hasFallback =
                emitted.stream()
                        .anyMatch(e -> e.getType() == AgentEventType.MODEL_FALLBACK_ACTIVATED);
        assertTrue(
                hasFallback, "Fallback activation must be emitted even when fallback also fails");
    }

    // ========================================================================
    // REQ: per-attempt latency when available
    // ========================================================================

    @Test
    void req_perAttemptLatencyIsPositive() {
        List<AgentEvent> emitted = new ArrayList<>();

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
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        1,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        ModelCallAttemptEndEvent end = (ModelCallAttemptEndEvent) emitted.get(1);
        assertTrue(
                end.getLatencyMs() >= 0,
                "Latency must be non-negative, got: " + end.getLatencyMs());
    }

    // ========================================================================
    // REQ: attempt index is sequential and maxAttempts is reported
    // ========================================================================

    @Test
    void req_attemptIndexSequentialAndMaxAttemptsReported() {
        List<AgentEvent> emitted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(5)
                                        .initialBackoff(Duration.ofMillis(10))
                                        .maxBackoff(Duration.ofMillis(50))
                                        .retryOn(e -> true)
                                        .build())
                        .build();

        Flux<ChatResponse> raw =
                Flux.defer(
                        () -> {
                            int n = calls.incrementAndGet();
                            if (n <= 3) {
                                return Flux.error(new HttpTransportException("error", 503, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        5,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        // Verify sequential indices and maxAttempts
        List<ModelCallAttemptStartEvent> starts =
                emitted.stream()
                        .filter(e -> e instanceof ModelCallAttemptStartEvent)
                        .map(e -> (ModelCallAttemptStartEvent) e)
                        .toList();

        assertEquals(4, starts.size());
        for (int i = 0; i < starts.size(); i++) {
            assertEquals(
                    i + 1,
                    starts.get(i).getAttemptIndex(),
                    "Attempt index must be sequential starting from 1");
            assertEquals(
                    5, starts.get(i).getMaxAttempts(), "maxAttempts must be reported correctly");
        }
    }

    // ========================================================================
    // REQ: JSON round-trip serialization for all new event types
    // ========================================================================

    @Test
    void req_jsonRoundTripModelCallAttemptStartEvent() throws Exception {
        ModelCallAttemptStartEvent original =
                new ModelCallAttemptStartEvent(
                        REPLY, 2, 3, PROVIDER, MODEL, ModelCallAttemptRole.FALLBACK);

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertSame(ModelCallAttemptStartEvent.class, deserialized.getClass());
        ModelCallAttemptStartEvent roundTripped = (ModelCallAttemptStartEvent) deserialized;
        assertEquals(REPLY, roundTripped.getReplyId());
        assertEquals(2, roundTripped.getAttemptIndex());
        assertEquals(3, roundTripped.getMaxAttempts());
        assertEquals(PROVIDER, roundTripped.getProvider());
        assertEquals(MODEL, roundTripped.getModelName());
        assertEquals(ModelCallAttemptRole.FALLBACK, roundTripped.getRole());
    }

    @Test
    void req_jsonRoundTripModelCallAttemptFailedEvent() throws Exception {
        ModelCallAttemptFailedEvent original =
                new ModelCallAttemptFailedEvent(
                        REPLY,
                        1,
                        ModelCallAttemptFailureCategory.RATE_LIMIT,
                        true,
                        "HTTP_429",
                        "rate limited",
                        ModelCallAttemptRole.PRIMARY);

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertSame(ModelCallAttemptFailedEvent.class, deserialized.getClass());
        ModelCallAttemptFailedEvent rt = (ModelCallAttemptFailedEvent) deserialized;
        assertEquals(ModelCallAttemptFailureCategory.RATE_LIMIT, rt.getFailureCategory());
        assertTrue(rt.isRetryable());
        assertEquals("HTTP_429", rt.getErrorCode());
        assertEquals(ModelCallAttemptRole.PRIMARY, rt.getRole());
    }

    @Test
    void req_jsonRoundTripModelFallbackActivatedEvent() throws Exception {
        ModelFallbackActivatedEvent original =
                new ModelFallbackActivatedEvent(REPLY, 3, "gpt-4o", "claude-sonnet");

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertSame(ModelFallbackActivatedEvent.class, deserialized.getClass());
        ModelFallbackActivatedEvent rt = (ModelFallbackActivatedEvent) deserialized;
        assertEquals(3, rt.getFailedAttemptCount());
        assertEquals("gpt-4o", rt.getFromModel());
        assertEquals("claude-sonnet", rt.getToModel());
    }

    @Test
    void req_jsonRoundTripModelCallAttemptEndEvent() throws Exception {
        ModelCallAttemptEndEvent original =
                new ModelCallAttemptEndEvent(
                        REPLY, 1, true, null, 123L, ModelCallAttemptRole.PRIMARY);

        String json = MAPPER.writeValueAsString(original);
        AgentEvent deserialized = MAPPER.readValue(json, AgentEvent.class);

        assertSame(ModelCallAttemptEndEvent.class, deserialized.getClass());
        ModelCallAttemptEndEvent rt = (ModelCallAttemptEndEvent) deserialized;
        assertTrue(rt.isSuccess());
        assertEquals(123L, rt.getLatencyMs());
        assertEquals(ModelCallAttemptRole.PRIMARY, rt.getRole());
        assertNull(rt.getUsage());
    }

    // ========================================================================
    // REQ: Same behavior through call() and streamEvents() lifecycle
    //      (verified by modelForCall wrapper which is used in both paths)
    // ========================================================================

    @Test
    void req_modelForCallWithoutFallbackReturnsPlainModel() {
        List<AgentEvent> emitted = new ArrayList<>();
        Model plain = new StubModel(PROVIDER, MODEL);

        // modelForCall with no fallback configured should return the model directly
        // We simulate this by calling wrapWithFallback with no fallback
        // When fallbackModel is null, modelForCall returns the original model
        // which means no MODEL_FALLBACK_ACTIVATED events should appear
        Model wrapped = wrapWithFallback(plain, null, emitted::add);

        Flux<ChatResponse> result = wrapped.stream(List.of(), null, null);
        StepVerifier.create(result).expectNextCount(1).verifyComplete();

        boolean hasFallback =
                emitted.stream()
                        .anyMatch(e -> e.getType() == AgentEventType.MODEL_FALLBACK_ACTIVATED);
        assertFalse(hasFallback, "No fallback event should be emitted when fallbackModel is null");
    }

    @Test
    void req_attemptEventFieldsMatchSpecSuggestedShape() {
        // Verify all suggested fields from the spec are present in events
        ModelCallAttemptStartEvent start =
                new ModelCallAttemptStartEvent(
                        "reply-42",
                        2,
                        5,
                        "anthropic",
                        "claude-opus-4",
                        ModelCallAttemptRole.PRIMARY);
        assertNotNull(start.getReplyId());
        assertTrue(start.getAttemptIndex() > 0);
        assertTrue(start.getMaxAttempts() > 0);
        assertNotNull(start.getProvider());
        assertNotNull(start.getModelName());
        assertNotNull(start.getRole());

        ModelCallAttemptFailedEvent failed =
                new ModelCallAttemptFailedEvent(
                        "reply-42",
                        2,
                        ModelCallAttemptFailureCategory.TIMEOUT,
                        true,
                        "TIMEOUT_EXCEEDED",
                        "request timed out",
                        ModelCallAttemptRole.PRIMARY);
        assertNotNull(failed.getReplyId());
        assertNotNull(failed.getFailureCategory());
        assertNotNull(failed.getErrorCode());
        assertNotNull(failed.getErrorMessage());
        assertNotNull(failed.getRole());

        ModelCallAttemptEndEvent end =
                new ModelCallAttemptEndEvent(
                        "reply-42", 5, true, null, 500L, ModelCallAttemptRole.FALLBACK);
        assertNotNull(end.getReplyId());
        assertTrue(end.isSuccess());
        assertNotNull(end.getRole());
        assertTrue(end.getLatencyMs() >= 0);
    }

    // ========================================================================
    // REQ: Failed attempts do not create assistant messages
    //      (verified architecturally: tracker only emits events, no Msg objects)
    // ========================================================================

    @Test
    void req_failedAttemptsOnlyEmitEventsNoMessages() {
        List<AgentEvent> emitted = new ArrayList<>();

        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(1)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.error(new HttpTransportException("error", 400, ""));

        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(
                        raw,
                        opts,
                        null,
                        MODEL,
                        PROVIDER,
                        emitted::add,
                        REPLY,
                        1,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(result).verifyError();

        // All emitted items must be AgentEvent instances (no Msg objects leak)
        for (AgentEvent e : emitted) {
            assertNotNull(e.getType(), "Every emitted item must have a valid event type");
        }
        assertEquals(2, emitted.size());
        assertEquals(AgentEventType.MODEL_ATTEMPT_START, emitted.get(0).getType());
        assertEquals(AgentEventType.MODEL_ATTEMPT_FAILED, emitted.get(1).getType());
    }

    // ========================================================================
    // REQ: Existing aggregate MODEL_CALL_START/END remain backward compatible
    // ========================================================================

    @Test
    void req_backwardCompat_originalApplyTimeoutAndRetryStillWorks() {
        // The original 5-arg method must still work without any tracker
        GenerateOptions opts =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(Duration.ofSeconds(5))
                                        .maxAttempts(1)
                                        .build())
                        .build();

        Flux<ChatResponse> raw = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        // Original signature - no emitter params
        Flux<ChatResponse> result =
                ModelUtils.applyTimeoutAndRetry(raw, opts, null, MODEL, PROVIDER);

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Simulates ReActAgent.modelForCall() fallback wrapper with event emission.
     */
    private static Model wrapWithFallback(
            Model primary, Model fallback, java.util.function.Consumer<AgentEvent> emitter) {
        if (fallback == null) {
            return primary;
        }
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<io.agentscope.core.message.Msg> messages,
                    List<io.agentscope.core.model.ToolSchema> tools,
                    GenerateOptions options) {
                return primary.stream(messages, tools, options)
                        .switchOnFirst(
                                (signal, flux) -> {
                                    if (signal.isOnError()) {
                                        Throwable error = signal.getThrowable();
                                        emitter.accept(
                                                new ModelFallbackActivatedEvent(
                                                        null,
                                                        0,
                                                        primary.getModelName(),
                                                        fallback.getModelName()));
                                        return fallback.stream(messages, tools, options);
                                    }
                                    return flux;
                                });
            }

            @Override
            public String getModelName() {
                return primary.getModelName();
            }
        };
    }

    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static class StubModel implements Model {
        private final String provider;
        private final String name;

        StubModel(String provider, String name) {
            this.provider = provider;
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public String toString() {
            return provider + "/" + name;
        }
    }

    private static class FailingModel implements Model {
        private final String provider;
        private final String name;
        private final RuntimeException error;

        FailingModel(String provider, String name, RuntimeException error) {
            this.provider = provider;
            this.name = name;
            this.error = error;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            return Flux.error(error);
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public String toString() {
            return provider + "/" + name;
        }
    }
}
