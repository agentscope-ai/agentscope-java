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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelMediaException;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class HistoricalMediaRecoveryMiddlewareTest {

    private static final String PLACEHOLDER = "[expired historical media]";

    @Test
    void replacesHistoricalUrlMediaRetriesOnceAndCommitsState() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        Msg history = mixedHistoricalMedia("history");
        Msg toolHistory = toolHistory("tool-history");
        Msg current = textMessage("current", "continue");
        AgentState state =
                AgentState.builder()
                        .addMessage(history)
                        .addMessage(toolHistory)
                        .addMessage(current)
                        .build();
        RuntimeContext ctx = context("session", state);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ReasoningInput> retryInput = new AtomicReference<>();

        List<AgentEvent> events =
                invoke(
                                middleware,
                                ctx,
                                List.of(current),
                                new ReasoningInput(
                                        List.of(history, toolHistory, current), List.of(), null),
                                input -> {
                                    if (calls.incrementAndGet() == 1) {
                                        return Flux.concat(
                                                Flux.just(new ModelCallStartEvent("first")),
                                                Flux.error(
                                                        new RuntimeException(
                                                                new TestMediaException(true))));
                                    }
                                    retryInput.set(input);
                                    return Flux.just(new ModelCallStartEvent("retry"));
                                })
                        .collectList()
                        .block();

        assertEquals(2, calls.get());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof ModelCallStartEvent);
        assertEquals("first", ((ModelCallStartEvent) events.get(0)).getReplyId());

        Msg retriedHistory = retryInput.get().messages().get(0);
        assertEquals(history.getId(), retriedHistory.getId());
        assertEquals(history.getRole(), retriedHistory.getRole());
        assertEquals(history.getTimestamp(), retriedHistory.getTimestamp());
        assertEquals(history.getUsage(), retriedHistory.getUsage());
        assertEquals("preserve", retriedHistory.getMetadata().get("marker"));
        assertEquals(4, countTopLevelPlaceholders(retriedHistory));
        assertEquals(1, retriedHistory.getContentBlocks(ImageBlock.class).size());
        assertTrue(
                retriedHistory.getContentBlocks(ImageBlock.class).get(0).getSource()
                        instanceof Base64Source);
        assertFalse(hasUrlMedia(retriedHistory));

        Msg retriedTool = retryInput.get().messages().get(1);
        assertFalse(hasUrlMedia(retriedTool));
        ToolResultBlock retryToolResult =
                retriedTool.getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals(ToolResultState.SUCCESS, retryToolResult.getState());
        assertEquals("preserve", retryToolResult.getMetadata().get("marker"));

        assertFalse(hasUrlMedia(state.getContext().get(0)));
        assertFalse(hasUrlMedia(state.getContext().get(1)));
        assertSame(current, state.getContext().get(2));

        Msg nextCurrent = textMessage("next-current", "continue again");
        AtomicInteger nextCalls = new AtomicInteger();
        StepVerifier.create(
                        invoke(
                                middleware,
                                ctx,
                                List.of(nextCurrent),
                                new ReasoningInput(
                                        List.of(
                                                state.getContext().get(0),
                                                state.getContext().get(1),
                                                current,
                                                nextCurrent),
                                        List.of(),
                                        null),
                                ignored -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.error(new TestMediaException(true));
                                }))
                .expectError(TestMediaException.class)
                .verify();
        assertEquals(1, nextCalls.get(), "cleaned history must not trigger recovery again");
    }

    @Test
    void currentInputMediaFailureIsPropagatedWithoutRetry() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        Msg current = imageMessage("current", "https://example.invalid/current.png");
        AgentState state = AgentState.builder().addMessage(current).build();
        RuntimeContext ctx = context("session", state);
        AtomicInteger calls = new AtomicInteger();

        StepVerifier.create(
                        invoke(
                                middleware,
                                ctx,
                                List.of(current),
                                new ReasoningInput(List.of(current), List.of(), null),
                                input -> {
                                    calls.incrementAndGet();
                                    return Flux.concat(
                                            Flux.just(new ModelCallStartEvent("first")),
                                            Flux.error(new TestMediaException(true)));
                                }))
                .expectNextCount(1)
                .expectError(TestMediaException.class)
                .verify();

        assertEquals(1, calls.get());
        assertSame(current, state.getContext().get(0));
        assertTrue(hasUrlMedia(state.getContext().get(0)));
    }

    @Test
    void retryFailureDoesNotCommitHistoricalSanitization() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = imageMessage("current", "https://example.invalid/current.png");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        RuntimeContext ctx = context("session", state);
        AtomicInteger calls = new AtomicInteger();
        TestMediaException retryError = new TestMediaException(true);

        StepVerifier.create(
                        invoke(
                                middleware,
                                ctx,
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                input -> {
                                    int call = calls.incrementAndGet();
                                    return Flux.concat(
                                            Flux.just(new ModelCallStartEvent("call-" + call)),
                                            Flux.error(
                                                    call == 1
                                                            ? new TestMediaException(true)
                                                            : retryError));
                                }))
                .expectNextCount(1)
                .expectErrorMatches(error -> error == retryError)
                .verify();

        assertEquals(2, calls.get());
        assertSame(history, state.getContext().get(0));
        assertTrue(hasUrlMedia(state.getContext().get(0)));
        assertSame(current, state.getContext().get(1));
    }

    @Test
    void outputBeforeFailurePreventsRetry() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        RuntimeContext ctx = context("session", state);
        AtomicInteger calls = new AtomicInteger();

        StepVerifier.create(
                        invoke(
                                middleware,
                                ctx,
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                input -> {
                                    calls.incrementAndGet();
                                    return Flux.concat(
                                            Flux.just(
                                                    new ModelCallStartEvent("first"),
                                                    new TextBlockDeltaEvent(
                                                            "first", "text", "partial")),
                                            Flux.error(new TestMediaException(true)));
                                }))
                .expectNextCount(2)
                .expectError(TestMediaException.class)
                .verify();

        assertEquals(1, calls.get());
        assertSame(history, state.getContext().get(0));
    }

    @Test
    void unrelatedErrorIsNotRetried() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        RuntimeContext ctx = context("session", state);
        AtomicInteger calls = new AtomicInteger();

        StepVerifier.create(
                        invoke(
                                middleware,
                                ctx,
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                input -> {
                                    calls.incrementAndGet();
                                    return Flux.error(new IllegalArgumentException("bad request"));
                                }))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertEquals(1, calls.get());
        assertSame(history, state.getContext().get(0));
    }

    @Test
    void recoversAfterCompactionFailureFallsBackToOriginalReasoning() {
        HistoricalMediaRecoveryMiddleware recovery = middleware();
        CompactionMiddleware compaction =
                new CompactionMiddleware(
                        null,
                        new SynchronousFailingModel(),
                        CompactionConfig.builder()
                                .triggerTokens(1)
                                .keepTokens(1)
                                .flushBeforeCompact(false)
                                .offloadBeforeCompact(false)
                                .prune(null)
                                .build());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .model(new SynchronousFailingModel())
                        .build();
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        RuntimeContext ctx = context("session", state);
        ReasoningInput input = new ReasoningInput(List.of(history, current), List.of(), null);
        AtomicInteger reasoningCalls = new AtomicInteger();

        List<AgentEvent> events =
                recovery.onAgent(
                                agent,
                                ctx,
                                new AgentInput(List.of(current)),
                                ignored ->
                                        recovery.onReasoning(
                                                agent,
                                                ctx,
                                                input,
                                                compactInput ->
                                                        compaction.onReasoning(
                                                                agent,
                                                                ctx,
                                                                compactInput,
                                                                reasoningInput -> {
                                                                    if (reasoningCalls
                                                                                    .incrementAndGet()
                                                                            == 1) {
                                                                        return Flux.error(
                                                                                new TestMediaException(
                                                                                        true));
                                                                    }
                                                                    return Flux.empty();
                                                                })))
                        .collectList()
                        .block();

        assertTrue(events.isEmpty());
        assertEquals(2, reasoningCalls.get());
        assertFalse(hasUrlMedia(state.getContext().get(0)));
        assertSame(current, state.getContext().get(1));
    }

    @Test
    void concurrentSessionsKeepCurrentInputMarkersIsolated() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        RecoveryCall first = recoveryCall("a");
        RecoveryCall second = recoveryCall("b");

        Flux.merge(
                        first.invoke(middleware).subscribeOn(Schedulers.parallel()),
                        second.invoke(middleware).subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertEquals(2, first.calls().get());
        assertEquals(2, second.calls().get());
        assertFalse(hasUrlMedia(first.state().getContext().get(0)));
        assertSame(first.current(), first.state().getContext().get(1));
        assertFalse(hasUrlMedia(second.state().getContext().get(0)));
        assertSame(second.current(), second.state().getContext().get(1));
    }

    @Test
    void missingRuntimeContextPassesThroughWithoutRecovery() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        TestMediaException failure = new TestMediaException(true);
        AtomicInteger calls = new AtomicInteger();

        StepVerifier.create(
                        invoke(
                                middleware(),
                                null,
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                ignored -> {
                                    calls.incrementAndGet();
                                    return Flux.error(failure);
                                }))
                .expectErrorMatches(error -> error == failure)
                .verify();

        assertEquals(1, calls.get());
    }

    @Test
    void unsafeCurrentInputCannotTriggerHistoricalRecovery() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg missingId = textMessage(null, "continue");
        assertNoRecoveryForCurrentInput(Collections.singletonList(null), List.of(history));
        assertNoRecoveryForCurrentInput(List.of(missingId), List.of(history, missingId));
    }

    @Test
    void absentCurrentInputStillAllowsHistoricalRecovery() {
        assertRecoveryWithoutCurrentInput(null);
        assertRecoveryWithoutCurrentInput(List.of());
    }

    @Test
    void absentReasoningHistoryIsNotRetried() {
        assertNoRecoveryForReasoningHistory(null);
        assertNoRecoveryForReasoningHistory(List.of());
    }

    @Test
    void falseMediaContractAndCyclicCausesPropagateUnchanged() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();

        assertUnrecoverableFailure(new TestMediaException(false), history, current, state);

        RuntimeException outer = new RuntimeException("outer");
        RuntimeException inner = new RuntimeException("inner");
        outer.initCause(inner);
        inner.initCause(outer);
        assertUnrecoverableFailure(outer, history, current, state);
    }

    @Test
    void emptyContentAndEmbeddedMediaRemainUntouchedWhenHistoryIsRecovered() {
        Msg empty = Msg.builder().id("empty").role(MsgRole.USER).build();
        Msg embedded =
                Msg.builder()
                        .id("embedded")
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("keep text").build(),
                                        ImageBlock.builder()
                                                .source(new Base64Source("image/png", "AA=="))
                                                .build(),
                                        AudioBlock.builder()
                                                .source(new Base64Source("audio/mpeg", "AA=="))
                                                .build(),
                                        VideoBlock.builder()
                                                .source(new Base64Source("video/mp4", "AA=="))
                                                .build(),
                                        DataBlock.builder()
                                                .id("file")
                                                .source(new Base64Source("application/pdf", "AA=="))
                                                .build()))
                        .build();
        ToolResultBlock cleanToolResult =
                new ToolResultBlock(
                        "tool-id", "read", List.of(TextBlock.builder().text("ok").build()));
        Msg tool = Msg.builder().id("tool").role(MsgRole.TOOL).content(cleanToolResult).build();
        ToolResultBlock emptyToolResult = new ToolResultBlock("empty-tool", "read", List.of());
        Msg emptyTool =
                Msg.builder().id("empty-tool").role(MsgRole.TOOL).content(emptyToolResult).build();
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state =
                AgentState.builder()
                        .addMessage(empty)
                        .addMessage(embedded)
                        .addMessage(tool)
                        .addMessage(emptyTool)
                        .addMessage(history)
                        .addMessage(current)
                        .build();
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware(),
                        context(null, state),
                        List.of(current),
                        new ReasoningInput(state.getContext(), List.of(), null),
                        reasoning -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(new TestMediaException(true));
                            }
                            assertSame(empty, reasoning.messages().get(0));
                            assertSame(embedded, reasoning.messages().get(1));
                            assertSame(tool, reasoning.messages().get(2));
                            assertSame(emptyTool, reasoning.messages().get(3));
                            assertFalse(hasUrlMedia(reasoning.messages().get(4)));
                            return Flux.empty();
                        })
                .blockLast();

        assertEquals(2, calls.get());
        assertSame(empty, state.getContext().get(0));
        assertSame(embedded, state.getContext().get(1));
        assertSame(tool, state.getContext().get(2));
        assertSame(emptyTool, state.getContext().get(3));
        assertFalse(hasUrlMedia(state.getContext().get(4)));
    }

    @Test
    void retryPreservesToolsOptionsAndFirstVisibleStart() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        List<ToolSchema> tools =
                List.of(ToolSchema.builder().name("lookup").description("look up data").build());
        GenerateOptions options = GenerateOptions.builder().temperature(0.3).build();
        AtomicInteger calls = new AtomicInteger();

        List<AgentEvent> events =
                invoke(
                                middleware(),
                                context("session", state),
                                List.of(current),
                                new ReasoningInput(List.of(history, current), tools, options),
                                reasoning -> {
                                    if (calls.incrementAndGet() == 1) {
                                        return Flux.error(new TestMediaException(true));
                                    }
                                    assertSame(tools, reasoning.tools());
                                    assertSame(options, reasoning.options());
                                    assertFalse(hasUrlMedia(reasoning.messages().get(0)));
                                    return Flux.just(
                                            new ModelCallStartEvent("retry"),
                                            new TextBlockDeltaEvent("retry", "text", "done"));
                                })
                        .collectList()
                        .block();

        assertEquals(2, calls.get());
        assertEquals(2, events.size());
        assertEquals("retry", ((ModelCallStartEvent) events.get(0)).getReplyId());
        assertTrue(events.get(1) instanceof TextBlockDeltaEvent);
        assertFalse(hasUrlMedia(state.getContext().get(0)));
    }

    @Test
    void retryWithAnEarlierStartDropsOnlyTheDuplicateStart() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        AtomicInteger calls = new AtomicInteger();

        List<AgentEvent> events =
                invoke(
                                middleware(),
                                context("session", state),
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                ignored -> {
                                    if (calls.incrementAndGet() == 1) {
                                        return Flux.concat(
                                                Flux.just(new ModelCallStartEvent("first")),
                                                Flux.error(new TestMediaException(true)));
                                    }
                                    return Flux.just(
                                            new ModelCallStartEvent("retry"),
                                            new TextBlockDeltaEvent("retry", "text", "done"));
                                })
                        .collectList()
                        .block();

        assertEquals(2, calls.get());
        assertEquals(2, events.size());
        assertEquals("first", ((ModelCallStartEvent) events.get(0)).getReplyId());
        assertTrue(events.get(1) instanceof TextBlockDeltaEvent);
        assertFalse(hasUrlMedia(state.getContext().get(0)));
    }

    @Test
    void nullReasoningMessageIsPreservedWhileOtherHistoryIsRecovered() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware(),
                        context("session", state),
                        List.of(current),
                        new ReasoningInput(Arrays.asList(null, history, current), List.of(), null),
                        reasoning -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(new TestMediaException(true));
                            }
                            assertNull(reasoning.messages().get(0));
                            assertFalse(hasUrlMedia(reasoning.messages().get(1)));
                            assertSame(current, reasoning.messages().get(2));
                            return Flux.empty();
                        })
                .blockLast();

        assertEquals(2, calls.get());
        assertFalse(hasUrlMedia(state.getContext().get(0)));
    }

    @Test
    void retrySucceedsWithoutWritableAgentState() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware(),
                        context("session", null),
                        List.of(current),
                        new ReasoningInput(List.of(history, current), List.of(), null),
                        ignored ->
                                calls.incrementAndGet() == 1
                                        ? Flux.error(new TestMediaException(true))
                                        : Flux.empty())
                .blockLast();

        assertEquals(2, calls.get());
        assertTrue(hasUrlMedia(history));
    }

    @Test
    void commitOnlyChangesRecoveredMessagesStillContainingUrlMedia() {
        Msg first = imageMessage("first", "https://example.invalid/first.png");
        Msg second = imageMessage("second", "https://example.invalid/second.png");
        Msg current = textMessage("current", "continue");
        Msg replacement = textMessage("second", "already cleaned elsewhere");
        Msg addedLater = imageMessage("later", "https://example.invalid/later.png");
        AgentState state =
                AgentState.builder()
                        .addMessage(first)
                        .addMessage(second)
                        .addMessage(current)
                        .build();
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware(),
                        context("session", state),
                        List.of(current),
                        new ReasoningInput(List.of(first, second, current), List.of(), null),
                        reasoning -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(new TestMediaException(true));
                            }
                            assertFalse(hasUrlMedia(reasoning.messages().get(0)));
                            assertFalse(hasUrlMedia(reasoning.messages().get(1)));
                            state.contextMutable().set(1, replacement);
                            state.contextMutable().add(addedLater);
                            return Flux.empty();
                        })
                .blockLast();

        assertEquals(2, calls.get());
        assertFalse(hasUrlMedia(state.getContext().get(0)));
        assertSame(replacement, state.getContext().get(1));
        assertSame(current, state.getContext().get(2));
        assertSame(addedLater, state.getContext().get(3));
    }

    @Test
    void alreadyRemovedHistoryDoesNotCommitOnSuccessfulRetry() {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware(),
                        context("session", state),
                        List.of(current),
                        new ReasoningInput(List.of(history, current), List.of(), null),
                        ignored -> {
                            if (calls.incrementAndGet() == 1) {
                                return Flux.error(new TestMediaException(true));
                            }
                            state.contextMutable().remove(0);
                            return Flux.empty();
                        })
                .blockLast();

        assertEquals(2, calls.get());
        assertEquals(List.of(current), state.getContext());
    }

    @Test
    void cancellationDoesNotCommitAndClearsCurrentInputMarker() {
        HistoricalMediaRecoveryMiddleware middleware = middleware();
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        RuntimeContext ctx = context("session", state);
        AtomicInteger calls = new AtomicInteger();

        StepVerifier.create(
                        invoke(
                                middleware,
                                ctx,
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                ignored ->
                                        calls.incrementAndGet() == 1
                                                ? Flux.error(new TestMediaException(true))
                                                : Flux.never()))
                .thenCancel()
                .verify();

        assertEquals(2, calls.get());
        assertSame(history, state.getContext().get(0));
        TestMediaException failure = new TestMediaException(true);
        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                ctx,
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                ignored -> {
                                    calls.incrementAndGet();
                                    return Flux.error(failure);
                                }))
                .expectErrorMatches(error -> error == failure)
                .verify();
        assertEquals(3, calls.get(), "the cancelled agent call must clear its recovery marker");
    }

    @Test
    void configRejectsBlankReplacementText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoricalMediaRecoveryConfig.builder().replacementText("  ").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> HistoricalMediaRecoveryConfig.builder().replacementText(null).build());
        assertEquals(
                HistoricalMediaRecoveryConfig.DEFAULT_REPLACEMENT_TEXT,
                HistoricalMediaRecoveryConfig.defaults().getReplacementText());
    }

    @Test
    void nullMiddlewareConfigUsesDefaultReplacementText() {
        HistoricalMediaRecoveryMiddleware middleware = new HistoricalMediaRecoveryMiddleware(null);
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        Msg current = textMessage("current", "continue");
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware,
                        context("session", state),
                        List.of(current),
                        new ReasoningInput(List.of(history, current), List.of(), null),
                        ignored ->
                                calls.incrementAndGet() == 1
                                        ? Flux.error(new TestMediaException(true))
                                        : Flux.empty())
                .blockLast();

        assertEquals(2, calls.get());
        assertEquals(
                HistoricalMediaRecoveryConfig.DEFAULT_REPLACEMENT_TEXT,
                state.getContext().get(0).getContentBlocks(TextBlock.class).get(0).getText());
    }

    private void assertNoRecoveryForCurrentInput(
            List<Msg> currentInput, List<Msg> reasoningMessages) {
        Msg history = reasoningMessages.get(0);
        AgentState state = AgentState.builder().addMessage(history).build();
        TestMediaException failure = new TestMediaException(true);
        AtomicInteger calls = new AtomicInteger();

        StepVerifier.create(
                        invoke(
                                middleware(),
                                context("session", state),
                                currentInput,
                                new ReasoningInput(reasoningMessages, List.of(), null),
                                ignored -> {
                                    calls.incrementAndGet();
                                    return Flux.error(failure);
                                }))
                .expectErrorMatches(error -> error == failure)
                .verify();

        assertEquals(1, calls.get());
        assertSame(history, state.getContext().get(0));
    }

    private void assertRecoveryWithoutCurrentInput(List<Msg> currentInput) {
        Msg history = imageMessage("history", "https://example.invalid/history.png");
        AgentState state = AgentState.builder().addMessage(history).build();
        AtomicInteger calls = new AtomicInteger();

        invoke(
                        middleware(),
                        context("session", state),
                        currentInput,
                        new ReasoningInput(List.of(history), List.of(), null),
                        ignored ->
                                calls.incrementAndGet() == 1
                                        ? Flux.error(new TestMediaException(true))
                                        : Flux.empty())
                .blockLast();

        assertEquals(2, calls.get());
        assertFalse(hasUrlMedia(state.getContext().get(0)));
    }

    private void assertNoRecoveryForReasoningHistory(List<Msg> reasoningMessages) {
        AtomicInteger calls = new AtomicInteger();
        TestMediaException failure = new TestMediaException(true);

        StepVerifier.create(
                        invoke(
                                middleware(),
                                context("session", null),
                                List.of(),
                                new ReasoningInput(reasoningMessages, List.of(), null),
                                ignored -> {
                                    calls.incrementAndGet();
                                    return Flux.error(failure);
                                }))
                .expectErrorMatches(error -> error == failure)
                .verify();

        assertEquals(1, calls.get());
    }

    private void assertUnrecoverableFailure(
            RuntimeException failure, Msg history, Msg current, AgentState state) {
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.create(
                        invoke(
                                middleware(),
                                context("session", state),
                                List.of(current),
                                new ReasoningInput(List.of(history, current), List.of(), null),
                                ignored -> {
                                    calls.incrementAndGet();
                                    return Flux.error(failure);
                                }))
                .expectErrorMatches(error -> error == failure)
                .verify();
        assertEquals(1, calls.get());
        assertSame(history, state.getContext().get(0));
    }

    private HistoricalMediaRecoveryMiddleware middleware() {
        return new HistoricalMediaRecoveryMiddleware(
                HistoricalMediaRecoveryConfig.builder().replacementText(PLACEHOLDER).build());
    }

    private Flux<AgentEvent> invoke(
            HistoricalMediaRecoveryMiddleware middleware,
            RuntimeContext ctx,
            List<Msg> currentMessages,
            ReasoningInput reasoningInput,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        Agent agent = agent();
        return middleware.onAgent(
                agent,
                ctx,
                new AgentInput(currentMessages),
                ignored -> middleware.onReasoning(agent, ctx, reasoningInput, next));
    }

    private RecoveryCall recoveryCall(String suffix) {
        Msg history =
                imageMessage(
                        "history-" + suffix, "https://example.invalid/history-" + suffix + ".png");
        Msg current = textMessage("current-" + suffix, "continue " + suffix);
        AgentState state = AgentState.builder().addMessage(history).addMessage(current).build();
        RuntimeContext ctx = context("session-" + suffix, state);
        AtomicInteger calls = new AtomicInteger();
        return new RecoveryCall(history, current, state, ctx, calls);
    }

    private Msg mixedHistoricalMedia(String id) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(
                        List.of(
                                TextBlock.builder().text("keep text").build(),
                                ImageBlock.builder()
                                        .source(new URLSource("https://example.invalid/image.png"))
                                        .build(),
                                AudioBlock.builder()
                                        .source(new URLSource("https://example.invalid/audio.mp3"))
                                        .build(),
                                VideoBlock.builder()
                                        .source(new URLSource("https://example.invalid/video.mp4"))
                                        .build(),
                                DataBlock.builder()
                                        .id("data")
                                        .name("file.pdf")
                                        .source(new URLSource("https://example.invalid/file.pdf"))
                                        .build(),
                                ImageBlock.builder()
                                        .source(new Base64Source("image/png", "aGVsbG8="))
                                        .build()))
                .metadata(Map.of("marker", "preserve"))
                .build();
    }

    private Msg toolHistory(String id) {
        ToolResultBlock result =
                new ToolResultBlock(
                        "call-1",
                        "download",
                        List.of(
                                TextBlock.builder().text("keep tool text").build(),
                                DataBlock.builder()
                                        .id("tool-data")
                                        .source(new URLSource("https://example.invalid/tool.bin"))
                                        .build()),
                        Map.of("marker", "preserve"),
                        ToolResultState.SUCCESS);
        return Msg.builder().id(id).role(MsgRole.TOOL).content(result).build();
    }

    private Msg imageMessage(String id, String url) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .content(ImageBlock.builder().source(new URLSource(url)).build())
                .build();
    }

    private Msg textMessage(String id, String text) {
        return Msg.builder().id(id).role(MsgRole.USER).textContent(text).build();
    }

    private RuntimeContext context(String sessionId, AgentState state) {
        return RuntimeContext.builder().sessionId(sessionId).agentState(state).build();
    }

    private Agent agent() {
        return new AgentBase("test-agent") {
            @Override
            protected Mono<Msg> doCall(List<Msg> msgs) {
                return Mono.empty();
            }

            @Override
            protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
                return Mono.empty();
            }
        };
    }

    private int countTopLevelPlaceholders(Msg message) {
        return (int)
                message.getContent().stream()
                        .filter(
                                block ->
                                        block instanceof TextBlock text
                                                && PLACEHOLDER.equals(text.getText()))
                        .count();
    }

    private boolean hasUrlMedia(Msg message) {
        return hasUrlMedia(message.getContent());
    }

    private boolean hasUrlMedia(List<ContentBlock> blocks) {
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock image && image.getSource() instanceof URLSource
                    || block instanceof AudioBlock audio && audio.getSource() instanceof URLSource
                    || block instanceof VideoBlock video && video.getSource() instanceof URLSource
                    || block instanceof DataBlock data && data.getSource() instanceof URLSource) {
                return true;
            }
            if (block instanceof ToolResultBlock toolResult
                    && hasUrlMedia(toolResult.getOutput())) {
                return true;
            }
        }
        return false;
    }

    private record RecoveryCall(
            Msg history,
            Msg current,
            AgentState state,
            RuntimeContext context,
            AtomicInteger calls) {

        private Flux<AgentEvent> invoke(HistoricalMediaRecoveryMiddleware middleware) {
            Agent testAgent =
                    new AgentBase("test-agent-" + current.getId()) {
                        @Override
                        protected Mono<Msg> doCall(List<Msg> msgs) {
                            return Mono.empty();
                        }

                        @Override
                        protected Mono<Msg> handleInterrupt(
                                InterruptContext interruptContext, Msg... originalArgs) {
                            return Mono.empty();
                        }
                    };
            ReasoningInput input = new ReasoningInput(List.of(history, current), List.of(), null);
            return middleware.onAgent(
                    testAgent,
                    context,
                    new AgentInput(List.of(current)),
                    ignored ->
                            middleware.onReasoning(
                                    testAgent,
                                    context,
                                    input,
                                    reasoning -> {
                                        if (calls.incrementAndGet() == 1) {
                                            return Flux.error(new TestMediaException(true));
                                        }
                                        return Flux.empty();
                                    }));
        }
    }

    private static final class TestMediaException extends RuntimeException
            implements ModelMediaException {

        private final boolean mediaUnavailable;

        private TestMediaException(boolean mediaUnavailable) {
            super("media unavailable");
            this.mediaUnavailable = mediaUnavailable;
        }

        @Override
        public boolean isMediaUnavailable() {
            return mediaUnavailable;
        }
    }

    private static final class SynchronousFailingModel extends ChatModelBase {

        @Override
        public String getModelName() {
            return "failing-compaction-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            throw new IllegalStateException("compaction unavailable");
        }
    }
}
