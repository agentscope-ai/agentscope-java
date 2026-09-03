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
package io.agentscope.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

class AgentEventStreamsTest {

    @Test
    void emitsResultTerminalThenEndOnNormalCompletion() {
        AgentResultEvent result = result(GenerateReason.MODEL_STOP);
        AgentEndEvent end = new AgentEndEvent("reply-1");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        new TextBlockEndEvent("reply-1", "block-1"),
                                        new ModelCallEndEvent("reply-1", null),
                                        result,
                                        end))
                        .collectList()
                        .block();

        assertEquals(7, events.size());
        assertSame(result, events.get(4));
        TextOutputDispositionEvent disposition =
                assertInstanceOf(TextOutputDispositionEvent.class, events.get(5));
        assertEquals("reply-1", disposition.getReplyId());
        assertEquals(TextOutputDisposition.TERMINAL, disposition.getDisposition());
        assertEquals(GenerateReason.MODEL_STOP, disposition.getGenerateReason());
        assertSame(end, events.get(6));
    }

    @Test
    void emitsIntermediateBeforeToolWhenVisibleTextPrecedesToolCall() {
        ToolCallStartEvent tool = new ToolCallStartEvent("reply-1", "call-1", "search");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "checking"),
                                        tool))
                        .collectList()
                        .block();

        assertEquals(4, events.size());
        assertDisposition(events.get(2), "reply-1", TextOutputDisposition.INTERMEDIATE);
        assertSame(tool, events.get(3));
    }

    @Test
    void emitsIntermediateAfterTextEndWhenToolCallPrecedesVisibleText() {
        TextBlockEndEvent textEnd = new TextBlockEndEvent("reply-1", "block-1");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new ToolCallStartEvent("reply-1", "call-1", "search"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "checking"),
                                        textEnd))
                        .collectList()
                        .block();

        assertEquals(5, events.size());
        assertSame(textEnd, events.get(3));
        assertDisposition(events.get(4), "reply-1", TextOutputDisposition.INTERMEDIATE);
    }

    @Test
    void emitsIntermediateBeforeNextModelRoundWithoutToolCall() {
        ModelCallStartEvent nextRound = new ModelCallStartEvent("reply-2");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "draft"),
                                        new ModelCallEndEvent("reply-1", null),
                                        nextRound))
                        .collectList()
                        .block();

        assertEquals(5, events.size());
        assertDisposition(events.get(3), "reply-1", TextOutputDisposition.INTERMEDIATE);
        assertSame(nextRound, events.get(4));
    }

    @Test
    void classifiesEachReplyAtMostOnceAcrossMultipleTextSegments() {
        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "first"),
                                        new ToolCallStartEvent("reply-1", "call-1", "search"),
                                        new TextBlockEndEvent("reply-1", "block-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-2", "second"),
                                        new TextBlockEndEvent("reply-1", "block-2")))
                        .collectList()
                        .block();

        assertEquals(
                1, events.stream().filter(TextOutputDispositionEvent.class::isInstance).count());
    }

    @Test
    void isolatesSameSourceByTaskId() {
        AgentEvent taskAStart = tagged(new ModelCallStartEvent("reply-a"), "worker", "task-a");
        AgentEvent taskAText =
                tagged(
                        new TextBlockDeltaEvent("reply-a", "block-a", "draft-a"),
                        "worker",
                        "task-a");
        AgentEvent taskBStart = tagged(new ModelCallStartEvent("reply-b"), "worker", "task-b");
        AgentEvent taskBText =
                tagged(
                        new TextBlockDeltaEvent("reply-b", "block-b", "draft-b"),
                        "worker",
                        "task-b");
        AgentEvent taskBTool =
                tagged(new ToolCallStartEvent("reply-b", "call-b", "search"), "worker", "task-b");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(taskAStart, taskAText, taskBStart, taskBText, taskBTool))
                        .collectList()
                        .block();

        assertEquals(6, events.size());
        TextOutputDispositionEvent disposition =
                assertInstanceOf(TextOutputDispositionEvent.class, events.get(4));
        assertEquals("reply-b", disposition.getReplyId());
        assertEquals("worker", disposition.getSource());
        assertEquals("task-b", disposition.getMetadata().get(AgentEvent.METADATA_TASK_ID));
        assertSame(taskBTool, events.get(5));
    }

    @Test
    void createsIndependentStateForEachSubscription() {
        Flux<AgentEvent> annotated =
                AgentEventStreams.withTextOutputDisposition(
                        Flux.just(
                                new ModelCallStartEvent("reply-1"),
                                new TextBlockDeltaEvent("reply-1", "block-1", "draft"),
                                new ToolCallStartEvent("reply-1", "call-1", "search")));

        List<AgentEventType> first = annotated.map(AgentEvent::getType).collectList().block();
        List<AgentEventType> second = annotated.map(AgentEvent::getType).collectList().block();

        assertEquals(first, second);
        assertEquals(AgentEventType.TEXT_OUTPUT_DISPOSITION, first.get(2));
    }

    @Test
    void emitsTopLevelEndWithoutTerminalWhenNoAuthoritativeResultExists() {
        AgentEndEvent end = new AgentEndEvent("reply-1");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        end))
                        .collectList()
                        .block();

        assertEquals(3, events.size());
        assertSame(end, events.get(2));
    }

    @Test
    void emitsTopLevelEndWithoutTerminalWhenAuthoritativeResultIsNull() {
        AgentResultEvent result = new AgentResultEvent(null);
        AgentEndEvent end = new AgentEndEvent("reply-1");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        result,
                                        end))
                        .collectList()
                        .block();

        assertEquals(4, events.size());
        assertSame(result, events.get(2));
        assertSame(end, events.get(3));
    }

    @Test
    void doesNotLeakPendingTopLevelEndOrTerminalOnError() {
        RuntimeException failure = new RuntimeException("boom");
        AgentEndEvent end = new AgentEndEvent("reply-1");

        Flux<AgentEvent> annotated =
                AgentEventStreams.withTextOutputDisposition(
                        Flux.concat(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        result(GenerateReason.MODEL_STOP),
                                        end),
                                Flux.error(failure)));

        StepVerifier.create(annotated)
                .expectNextCount(3)
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void cancellationAfterResultDoesNotSynthesizeTerminalDisposition() {
        TestPublisher<AgentEvent> source = TestPublisher.create();
        AgentResultEvent result = result(GenerateReason.MODEL_STOP);

        StepVerifier.create(AgentEventStreams.withTextOutputDisposition(source.flux()))
                .then(
                        () ->
                                source.next(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        result))
                .expectNextCount(2)
                .expectNext(result)
                .thenCancel()
                .verify();

        source.assertCancelled();
    }

    @Test
    void cancellationAfterTopLevelEndIsStagedDoesNotLeakTerminalOrEnd() {
        TestPublisher<AgentEvent> source = TestPublisher.create();
        AgentResultEvent result = result(GenerateReason.MODEL_STOP);
        AgentEndEvent end = new AgentEndEvent("reply-1");
        AtomicBoolean cancellationRequested = new AtomicBoolean();

        Flux<AgentEvent> annotated =
                AgentEventStreams.withTextOutputDisposition(
                        source.flux()
                                .doOnCancel(
                                        () ->
                                                assertTrue(
                                                        cancellationRequested.get(),
                                                        "source cancelled before verifier"
                                                                + " cancellation")));

        StepVerifier.create(annotated, 0)
                .thenRequest(1)
                .then(() -> source.next(new ModelCallStartEvent("reply-1")))
                .expectNextMatches(ModelCallStartEvent.class::isInstance)
                .thenRequest(1)
                .then(() -> source.next(new TextBlockDeltaEvent("reply-1", "block-1", "answer")))
                .expectNextMatches(TextBlockDeltaEvent.class::isInstance)
                .thenRequest(1)
                .then(() -> source.next(result))
                .expectNext(result)
                .then(
                        () -> {
                            source.next(end);
                            source.assertNoRequestOverflow();
                            source.assertSubscribers();
                        })
                .then(() -> cancellationRequested.set(true))
                .thenCancel()
                .verify();

        source.assertCancelled();
    }

    @Test
    void rejectsEventsAfterTopLevelEndWithoutLeakingHeldEvents() {
        AgentResultEvent lateResult = result(GenerateReason.MODEL_STOP);

        StepVerifier.create(
                        AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        new AgentEndEvent("reply-1"),
                                        lateResult)))
                .expectNextCount(2)
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getMessage().contains("after AgentEndEvent"))
                .verify();
    }

    @Test
    void respectsOneAtATimeDownstreamDemandForDerivedEvents() {
        AtomicBoolean completed = new AtomicBoolean();
        AgentEndEvent end = new AgentEndEvent("reply-1");
        Flux<AgentEvent> annotated =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        new ModelCallStartEvent("reply-1"),
                                        new TextBlockDeltaEvent("reply-1", "block-1", "answer"),
                                        result(GenerateReason.STRUCTURED_OUTPUT),
                                        end))
                        .doOnComplete(() -> completed.set(true));

        StepVerifier.create(annotated, 0)
                .thenRequest(1)
                .expectNextMatches(ModelCallStartEvent.class::isInstance)
                .thenRequest(1)
                .expectNextMatches(TextBlockDeltaEvent.class::isInstance)
                .thenRequest(1)
                .expectNextMatches(AgentResultEvent.class::isInstance)
                .thenRequest(1)
                .assertNext(
                        event -> {
                            TextOutputDispositionEvent disposition =
                                    assertInstanceOf(TextOutputDispositionEvent.class, event);
                            assertEquals(
                                    GenerateReason.STRUCTURED_OUTPUT,
                                    disposition.getGenerateReason());
                        })
                .then(() -> assertEquals(false, completed.get()))
                .thenRequest(1)
                .expectNext(end)
                .verifyComplete();
    }

    @Test
    void childEndImmediatelyClosesVisibleReplyWithTerminalDisposition() {
        AgentEndEvent end =
                (AgentEndEvent) tagged(new AgentEndEvent("reply-1"), "worker", "task-1");

        List<AgentEvent> events =
                AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        tagged(
                                                new ModelCallStartEvent("reply-1"),
                                                "worker",
                                                "task-1"),
                                        tagged(
                                                new TextBlockDeltaEvent(
                                                        "reply-1", "block-1", "answer"),
                                                "worker",
                                                "task-1"),
                                        end))
                        .collectList()
                        .block();

        assertEquals(4, events.size());
        TextOutputDispositionEvent disposition =
                assertInstanceOf(TextOutputDispositionEvent.class, events.get(2));
        assertEquals(TextOutputDisposition.TERMINAL, disposition.getDisposition());
        assertNull(disposition.getGenerateReason());
        assertEquals("worker", disposition.getSource());
        assertEquals("task-1", disposition.getMetadata().get(AgentEvent.METADATA_TASK_ID));
        assertSame(end, events.get(3));
    }

    @Test
    void cancellationAfterChildTerminalCanPreventFollowingEnd() {
        AgentEndEvent end =
                (AgentEndEvent) tagged(new AgentEndEvent("reply-1"), "worker", "task-1");

        StepVerifier.create(
                        AgentEventStreams.withTextOutputDisposition(
                                Flux.just(
                                        tagged(
                                                new ModelCallStartEvent("reply-1"),
                                                "worker",
                                                "task-1"),
                                        tagged(
                                                new TextBlockDeltaEvent(
                                                        "reply-1", "block-1", "answer"),
                                                "worker",
                                                "task-1"),
                                        end)))
                .expectNextCount(2)
                .expectNextMatches(TextOutputDispositionEvent.class::isInstance)
                .thenCancel()
                .verify();
    }

    private static AgentResultEvent result(GenerateReason reason) {
        return new AgentResultEvent(
                AssistantMessage.builder().textContent("answer").generateReason(reason).build());
    }

    private static AgentEvent tagged(AgentEvent event, String source, String taskId) {
        return event.withSource(source).withMetadataEntry(AgentEvent.METADATA_TASK_ID, taskId);
    }

    private static void assertDisposition(
            AgentEvent event, String replyId, TextOutputDisposition expected) {
        TextOutputDispositionEvent disposition =
                assertInstanceOf(TextOutputDispositionEvent.class, event);
        assertEquals(replyId, disposition.getReplyId());
        assertEquals(expected, disposition.getDisposition());
    }
}
