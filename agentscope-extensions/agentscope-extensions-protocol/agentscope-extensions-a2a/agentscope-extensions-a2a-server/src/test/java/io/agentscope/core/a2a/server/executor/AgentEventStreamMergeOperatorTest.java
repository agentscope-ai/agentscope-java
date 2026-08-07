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
package io.agentscope.core.a2a.server.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentEventStreamMergeOperatorTest {

    @Test
    void mergesAdjacentTextDeltasWithTheSameIdentity() {
        TextBlockDeltaEvent first = text("reply", "block", "a", "main/researcher");
        first.withMetadata(Map.of("tenant", "media"));
        TextBlockDeltaEvent second = text("reply", "block", "b", "main/researcher");

        StepVerifier.create(
                        AgentEventStreamMergeOperator.merge(
                                Flux.just(first, second), 100, Duration.ofSeconds(1), stats -> {}))
                .assertNext(
                        event -> {
                            TextBlockDeltaEvent merged =
                                    assertInstanceOf(TextBlockDeltaEvent.class, event);
                            assertEquals("ab", merged.getDelta());
                            assertEquals(first.getId(), merged.getId());
                            assertEquals("main/researcher", merged.getSource());
                            assertEquals("media", merged.getMetadata().get("tenant"));
                        })
                .verifyComplete();
    }

    @Test
    void keepsThinkingSeparateFromTextAndHonorsIdentityBoundaries() {
        StepVerifier.create(
                        AgentEventStreamMergeOperator.merge(
                                Flux.just(
                                        text("reply", "block", "a", "main"),
                                        text("reply", "other", "b", "main"),
                                        text("reply", "other", "c", "child"),
                                        thinking("reply", "other", "d", "child")),
                                100,
                                Duration.ofSeconds(1),
                                stats -> {}))
                .assertNext(event -> assertEquals("a", delta(event)))
                .assertNext(event -> assertEquals("b", delta(event)))
                .assertNext(event -> assertEquals("c", delta(event)))
                .assertNext(
                        event -> {
                            assertInstanceOf(ThinkingBlockDeltaEvent.class, event);
                            assertEquals("d", delta(event));
                        })
                .verifyComplete();
    }

    @Test
    void flushesPendingDeltaBeforeEveryNonDeltaBoundary() {
        AgentEvent boundary = new TextBlockEndEvent("reply", "block");

        StepVerifier.create(
                        AgentEventStreamMergeOperator.merge(
                                Flux.just(
                                        text("reply", "block", "a", "main"),
                                        boundary,
                                        text("reply", "block", "b", "main")),
                                100,
                                Duration.ofSeconds(1),
                                stats -> {}))
                .assertNext(event -> assertEquals("a", delta(event)))
                .expectNext(boundary)
                .assertNext(event -> assertEquals("b", delta(event)))
                .verifyComplete();
    }

    @Test
    void flushesWhenMaxSizeIsReached() {
        StepVerifier.create(
                        AgentEventStreamMergeOperator.merge(
                                Flux.concat(
                                        Flux.just(
                                                text("reply", "block", "a", "main"),
                                                text("reply", "block", "b", "main")),
                                        Mono.never()),
                                2,
                                Duration.ofSeconds(10),
                                stats -> {}))
                .assertNext(event -> assertEquals("ab", delta(event)))
                .thenCancel()
                .verify();
    }

    @Test
    void flushesWhenIntervalExpires() {
        StepVerifier.withVirtualTime(
                        () ->
                                AgentEventStreamMergeOperator.merge(
                                        Flux.concat(
                                                Mono.just(text("reply", "block", "a", "main")),
                                                Mono.never()),
                                        100,
                                        Duration.ofMillis(300),
                                        stats -> {}))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(299))
                .thenAwait(Duration.ofMillis(1))
                .assertNext(event -> assertEquals("a", delta(event)))
                .thenCancel()
                .verify();
    }

    @Test
    void flushesBeforePropagatingAnError() {
        StepVerifier.create(
                        AgentEventStreamMergeOperator.merge(
                                Flux.concat(
                                        Mono.just(text("reply", "block", "a", "main")),
                                        Mono.error(new IllegalStateException("boom"))),
                                100,
                                Duration.ofSeconds(1),
                                stats -> {}))
                .assertNext(event -> assertEquals("a", delta(event)))
                .expectErrorMessage("boom")
                .verify();
    }

    private static TextBlockDeltaEvent text(
            String replyId, String blockId, String delta, String source) {
        TextBlockDeltaEvent event = new TextBlockDeltaEvent(replyId, blockId, delta);
        event.withSource(source);
        return event;
    }

    private static ThinkingBlockDeltaEvent thinking(
            String replyId, String blockId, String delta, String source) {
        ThinkingBlockDeltaEvent event = new ThinkingBlockDeltaEvent(replyId, blockId, delta);
        event.withSource(source);
        return event;
    }

    private static String delta(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getDelta();
        }
        return assertInstanceOf(ThinkingBlockDeltaEvent.class, event).getDelta();
    }
}
