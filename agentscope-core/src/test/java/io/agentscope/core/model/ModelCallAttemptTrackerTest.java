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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallAttemptEndEvent;
import io.agentscope.core.event.ModelCallAttemptFailedEvent;
import io.agentscope.core.event.ModelCallAttemptFailureCategory;
import io.agentscope.core.event.ModelCallAttemptRole;
import io.agentscope.core.event.ModelCallAttemptStartEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ModelCallAttemptTrackerTest {

    private static final String REPLY_ID = "test-reply-1";
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4o";

    @Test
    void successfulAttemptEmitsStartAndEnd() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY_ID,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(tracked).expectNextCount(1).verifyComplete();

        assertEquals(2, events.size());
        assertInstanceOf(ModelCallAttemptStartEvent.class, events.get(0));
        assertInstanceOf(ModelCallAttemptEndEvent.class, events.get(1));

        ModelCallAttemptStartEvent start = (ModelCallAttemptStartEvent) events.get(0);
        assertEquals(REPLY_ID, start.getReplyId());
        assertEquals(1, start.getAttemptIndex());
        assertEquals(3, start.getMaxAttempts());
        assertEquals(PROVIDER, start.getProvider());
        assertEquals(MODEL, start.getModelName());
        assertEquals(ModelCallAttemptRole.PRIMARY, start.getRole());

        ModelCallAttemptEndEvent end = (ModelCallAttemptEndEvent) events.get(1);
        assertEquals(REPLY_ID, end.getReplyId());
        assertEquals(1, end.getAttemptIndex());
        assertTrue(end.isSuccess());
        assertEquals(ModelCallAttemptRole.PRIMARY, end.getRole());
        assertTrue(end.getLatencyMs() >= 0);
    }

    @Test
    void failedAttemptEmitsStartAndFailed() {
        List<AgentEvent> events = new ArrayList<>();

        RuntimeException error = new RuntimeException("model call failed");

        Flux<ChatResponse> source = Flux.error(error);

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY_ID,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        StepVerifier.create(tracked).verifyError(RuntimeException.class);

        assertEquals(2, events.size());
        assertInstanceOf(ModelCallAttemptStartEvent.class, events.get(0));
        assertInstanceOf(ModelCallAttemptFailedEvent.class, events.get(1));

        ModelCallAttemptFailedEvent failed = (ModelCallAttemptFailedEvent) events.get(1);
        assertEquals(REPLY_ID, failed.getReplyId());
        assertEquals(1, failed.getAttemptIndex());
        assertEquals(ModelCallAttemptFailureCategory.UNKNOWN, failed.getFailureCategory());
        assertFalse(failed.isRetryable());
        assertEquals(ModelCallAttemptRole.PRIMARY, failed.getRole());
        assertNotNull(failed.getErrorMessage());
    }

    @Test
    void retryEmitsMultipleAttemptEvents() {
        List<AgentEvent> events = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        // First call fails, second succeeds
        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            if (callCount.incrementAndGet() == 1) {
                                return Flux.error(
                                        new io.agentscope.core.model.transport
                                                .HttpTransportException("rate limited", 429, ""));
                            }
                            return Flux.just(new ChatResponse(null, List.of(), null, null, null));
                        });

        // Apply tracking BEFORE retry (simulating what ModelUtils does)
        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY_ID,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.PRIMARY);

        // Apply retry on top of tracked flux
        Flux<ChatResponse> withRetry =
                tracked.retryWhen(
                        reactor.util.retry.Retry.backoff(2, Duration.ofMillis(10))
                                .maxBackoff(Duration.ofMillis(50))
                                .filter(e -> true));

        StepVerifier.create(withRetry).expectNextCount(1).verifyComplete();

        // Should have: START(1), FAILED(1), START(2), END(2) = 4 events
        assertEquals(4, events.size());

        ModelCallAttemptStartEvent start1 = (ModelCallAttemptStartEvent) events.get(0);
        assertEquals(1, start1.getAttemptIndex());

        ModelCallAttemptFailedEvent failed1 = (ModelCallAttemptFailedEvent) events.get(1);
        assertEquals(1, failed1.getAttemptIndex());
        assertEquals(ModelCallAttemptFailureCategory.RATE_LIMIT, failed1.getFailureCategory());

        ModelCallAttemptStartEvent start2 = (ModelCallAttemptStartEvent) events.get(2);
        assertEquals(2, start2.getAttemptIndex());

        ModelCallAttemptEndEvent end2 = (ModelCallAttemptEndEvent) events.get(3);
        assertEquals(2, end2.getAttemptIndex());
        assertTrue(end2.isSuccess());
    }

    @Test
    void fallbackRoleIsPassedThrough() {
        List<AgentEvent> events = new ArrayList<>();

        Flux<ChatResponse> source = Flux.just(new ChatResponse(null, List.of(), null, null, null));

        Flux<ChatResponse> tracked =
                ModelCallAttemptTracker.wrap(
                        source,
                        events::add,
                        REPLY_ID,
                        3,
                        PROVIDER,
                        MODEL,
                        ModelCallAttemptRole.FALLBACK);

        StepVerifier.create(tracked).expectNextCount(1).verifyComplete();

        assertEquals(2, events.size());
        assertEquals(
                ModelCallAttemptRole.FALLBACK,
                ((ModelCallAttemptStartEvent) events.get(0)).getRole());
        assertEquals(
                ModelCallAttemptRole.FALLBACK,
                ((ModelCallAttemptEndEvent) events.get(1)).getRole());
    }
}
