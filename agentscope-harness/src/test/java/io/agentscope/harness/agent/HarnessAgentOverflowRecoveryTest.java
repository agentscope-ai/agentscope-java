/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Regression tests for recovery inside the real ReAct reasoning scope: ownership, interruption,
 * cancellation, persistence and the single-attempt/input contracts.
 */
@HarnessQuiescence
class HarnessAgentOverflowRecoveryTest {

    private static final String SESSION_ID = "overflow-session";

    /** Enough prior turns that the forced compaction (keepMessages = 20) has a prefix to fold. */
    private static final int PRIOR_TURNS = 12;

    /** Padding that keeps prior turns above the fixed summary-wrapper cost after folding. */
    private static final String PAD = " earlier conversation detail".repeat(40);

    @TempDir Path workspace;

    @TempDir Path stateDir;

    /**
     * Overflow on the turn's first reasoning step. The JSON-file store returns a copy on load, so
     * it also exposes a retry that reloads the uncompacted context; the in-memory store does not.
     */
    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void recoveredOverflowKeepsTheInputOnceAndRetriesWithTheCompactedContext(String storeKind) {
        AgentStateStore store =
                "json-file".equals(storeKind)
                        ? new JsonFileAgentStateStore(stateDir)
                        : new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);

        Msg input = userMsg("final question");
        model.script(overflow());
        Msg reply = agent.call(input, ctx).block(Duration.ofSeconds(30));

        assertNotNull(reply);
        assertEquals("ok", reply.getTextContent());
        assertEquals(1, count(persistedContext(store), input), "input persisted once");
        List<Msg> retryInput = model.lastMainInput();
        assertEquals(1, count(retryInput, input), "retry must send the input to the model once");
        assertTrue(
                retryInput.stream().noneMatch(m -> priorTurn(0).equals(m.getTextContent())),
                "retry must run on the compacted context, not the reloaded original");
    }

    /** Overflow after a tool step: the retry continues from it instead of redoing the turn. */
    @Test
    void overflowAfterAToolStepContinuesWithoutRerunningTheTool() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        Probe probe = new Probe();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(probe);
        HarnessAgent agent = newAgent(model, store, toolkit);
        RuntimeContext ctx = runPriorTurns(agent);

        Msg input = userMsg("final question");
        model.script(toolUse("probe-call", "probe"), overflow());
        Msg reply = agent.call(input, ctx).block(Duration.ofSeconds(30));

        assertNotNull(reply);
        assertEquals("ok", reply.getTextContent());
        assertEquals(1, probe.calls.get(), "the tool must not run again on the retry");
        List<Msg> persisted = persistedContext(store);
        assertEquals(1, count(persisted, input), "input persisted once");
        assertEquals(1, toolResults(persisted, "probe-call"), "tool result persisted once");
        assertEquals(1, toolResults(model.lastMainInput(), "probe-call"));
    }

    @Test
    void repeatedOverflowRecoveryDoesNotDuplicateInputs() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        Msg first = userMsg("first overflow question");
        Msg second = userMsg("second overflow question");
        for (Msg input : List.of(first, second)) {
            model.script(overflow());
            assertNotNull(agent.call(input, ctx).block(Duration.ofSeconds(30)));
            assertEquals(1, count(model.lastMainInput(), input));
            assertEquals(1, count(persistedContext(store), input));
            assertTrue(
                    persistedContext(store).stream()
                            .anyMatch(
                                    m ->
                                            ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                    m.getName())));
        }
        assertEquals(1, count(persistedContext(store), first));
        assertEquals(1, count(persistedContext(store), second));
    }

    @Test
    void retryErrorPropagatesWithoutASecondRecoveryOrDuplicateInput() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        Msg input = userMsg("overflow twice");
        int before = model.mainInputs.size();
        model.script(overflow(), overflow());

        StepVerifier.create(agent.call(input, ctx))
                .expectErrorMatches(e -> e.getMessage().contains("context_length_exceeded"))
                .verify(Duration.ofSeconds(30));

        assertEquals(before + 2, model.mainInputs.size());
        assertEquals(1, count(persistedContext(store), input));
        assertEquals(1, count(model.lastMainInput(), input));
    }

    @Test
    void retryTimeoutPropagatesWithCompactedStateAndSingleInput() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        Msg input = userMsg("retry timeout");
        int before = model.mainInputs.size();
        model.script(overflow(), Flux.error(new TimeoutException("retry provider timeout")));

        StepVerifier.create(agent.call(input, ctx))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(10));

        assertEquals(before + 2, model.mainInputs.size());
        assertEquals(1, count(persistedContext(store), input));
        assertTrue(
                persistedContext(store).stream()
                        .anyMatch(m -> ConversationCompactor.SUMMARY_MSG_NAME.equals(m.getName())));
    }

    @Test
    void failedCallEndSavePropagatesOnWorkerAfterInScopeRetry() {
        SummarySaveFailingStore store = new SummarySaveFailingStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        Msg input = userMsg("cannot persist compaction");
        int before = model.mainInputs.size();
        model.script(overflow());

        StepVerifier.create(agent.call(input, ctx))
                .expectErrorMatches(e -> e.getMessage().contains("compacted state save failed"))
                .verify(Duration.ofSeconds(30));

        assertEquals(before + 2, model.mainInputs.size(), "Retry stays inside the one live call");
        assertTrue(store.saveThread.startsWith("boundedElastic-"), store.saveThread);
        assertEquals(
                0,
                count(persistedContext(store), input),
                "Failing save leaves prior stored history");
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void pendingSummaryKeepsSameSessionOwnedWhileAnotherSessionProgresses(String kind)
            throws Exception {
        AgentStateStore store = spy(store(kind));
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        clearInvocations(store);
        PendingResponse summary = new PendingResponse();
        model.summaries.add(summary.stream());
        model.script(overflow());
        Msg a = userMsg("A recovering");
        Msg b = userMsg("B same session");
        int before = model.mainInputs.size();
        var callA = agent.call(a, ctx).subscribeOn(Schedulers.parallel()).toFuture();
        await(summary.subscribed);
        verify(store, times(0)).save(any(), any(), any(), any(State.class));
        verify(store, times(0)).saveIfVersion(any(), any(), any(), any(State.class), anyLong());
        var callB =
                agent.call(b, RuntimeContext.builder().sessionId(SESSION_ID).build()).toFuture();
        try {
            RuntimeContext other = RuntimeContext.builder().sessionId("other-session").build();
            assertEquals(
                    "ok",
                    agent.call("C different session", other)
                            .block(Duration.ofSeconds(5))
                            .getTextContent());
            assertFalse(callA.isDone());
            assertFalse(callB.isDone(), "B cannot finish while A owns the pending summary");
            assertEquals(
                    before + 2,
                    model.mainInputs.size(),
                    "Only A and the other session entered reasoning");
            assertEquals(0, count(model.mainInputs.stream().flatMap(List::stream).toList(), b));

            summary.complete(textResponse("recovered history"));
            assertEquals("ok", callA.get(5, TimeUnit.SECONDS).getTextContent());
            assertEquals("ok", callB.get(5, TimeUnit.SECONDS).getTextContent());
            List<Msg> persisted = persistedContext(store);
            assertEquals(1, count(persisted, a));
            assertEquals(1, count(persisted, b));
            assertEquals(1, model.summaryInputs.size());
            for (List<Msg> request :
                    model.mainInputs.subList(before + 2, model.mainInputs.size())) {
                assertTrue(hasSummary(request), "Retry and B must use recovered history");
                assertEquals(1, count(request, a));
            }
            assertEquals(1, count(model.lastMainInput(), b));
            assertTrue(hasSummary(persisted));
        } finally {
            callA.cancel(true);
            callB.cancel(true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void explicitInterruptCancelsPendingSummaryWithoutMutationOrRetry(String kind)
            throws Exception {
        AgentStateStore store = spy(store(kind));
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        clearInvocations(store);
        List<Msg> original = persistedContext(store);
        String originalSummary = agent.getDelegate().getAgentState(ctx).getSummary();
        PendingResponse summary = new PendingResponse();
        model.summaries.add(summary.stream());
        model.script(overflow());
        Msg input = userMsg("interrupt recovering call");
        int before = model.mainInputs.size();
        var call = agent.call(input, ctx).subscribeOn(Schedulers.parallel()).toFuture();
        try {
            await(summary.subscribed);
            verify(store, times(0)).save(any(), any(), any(), any(State.class));
            verify(store, times(0)).saveIfVersion(any(), any(), any(), any(State.class), anyLong());
            agent.interrupt(ctx);
            assertEquals(
                    GenerateReason.INTERRUPTED, call.get(5, TimeUnit.SECONDS).getGenerateReason());
            await(summary.cancelled);
            summary.sink.tryEmitValue(textResponse("late summary must not be applied"));
            assertEquals(before + 1, model.mainInputs.size(), "No retry after interruption");
            List<Msg> persisted = persistedContext(store);
            assertFalse(hasSummary(persisted));
            assertEquals(originalSummary, agent.getDelegate().getAgentState(ctx).getSummary());
            assertFalse(agent.getDelegate().getAgentState(ctx).isShutdownInterrupted());
            for (Msg msg : original) {
                assertEquals(1, count(persisted, msg), "Original history retained by id");
            }
            assertEquals(1, count(persisted, input));
            assertEquals(
                    "ok",
                    agent.call("next turn", ctx).block(Duration.ofSeconds(5)).getTextContent());
        } finally {
            call.cancel(true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void downstreamCancellationCancelsSummaryAndReleasesTheSession(String kind) throws Exception {
        AgentStateStore store = spy(store(kind));
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        clearInvocations(store);
        List<Msg> original = persistedContext(store);
        PendingResponse summary = new PendingResponse();
        model.summaries.add(summary.stream());
        model.script(overflow());
        int before = model.mainInputs.size();
        var call = agent.call("cancel recovery", ctx).subscribeOn(Schedulers.parallel()).toFuture();
        try {
            await(summary.subscribed);
            verify(store, times(0)).save(any(), any(), any(), any(State.class));
            verify(store, times(0)).saveIfVersion(any(), any(), any(), any(State.class), anyLong());
            assertTrue(call.cancel(true));
            await(summary.cancelled);
            summary.sink.tryEmitValue(textResponse("late cancelled summary"));
            verify(store, times(0)).save(any(), any(), any(), any(State.class));
            verify(store, times(0)).saveIfVersion(any(), any(), any(), any(State.class), anyLong());
            assertFalse(hasSummary(persistedContext(store)));
            assertEquals(
                    "ok",
                    agent.call("B after cancellation", ctx)
                            .block(Duration.ofSeconds(5))
                            .getTextContent());
            assertEquals(before + 2, model.mainInputs.size());
            assertFalse(hasSummary(persistedContext(store)));
            for (Msg msg : original) {
                assertEquals(1, count(persistedContext(store), msg));
            }
        } finally {
            call.cancel(true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "empty", "blank", "truncated", "tool", "not-smaller"})
    void invalidEmergencySummaryRetainsHistoryAndOriginalOverflow(String kind) {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        List<Msg> original = persistedContext(store);
        Msg input = userMsg("invalid summary");
        Flux<ChatResponse> invalid =
                switch (kind) {
                    case "failure" -> Flux.error(new IllegalStateException("summary failed"));
                    case "empty" -> Flux.empty();
                    case "blank" -> Flux.just(textResponse(" \n "));
                    case "truncated" ->
                            Flux.just(
                                    new ChatResponse(
                                            "summary",
                                            List.of(TextBlock.builder().text("partial").build()),
                                            null,
                                            Map.of(),
                                            "length"));
                    case "tool" -> toolUse("bad-summary-tool", "probe");
                    default -> Flux.just(textResponse(PAD.repeat(100)));
                };
        model.summaries.add(invalid);
        model.script(overflow());
        int before = model.mainInputs.size();
        StepVerifier.create(agent.call(input, ctx))
                .expectErrorMatches(e -> e.getMessage().contains("context_length_exceeded"))
                .verify(Duration.ofSeconds(10));
        List<Msg> persisted = persistedContext(store);
        assertEquals(before + 1, model.mainInputs.size());
        assertEquals(1, model.summaryInputs.size());
        assertFalse(hasSummary(persisted));
        assertEquals(original.size() + 1, persisted.size());
        for (Msg msg : original) {
            assertEquals(1, count(persisted, msg));
        }
        assertEquals(1, count(persisted, input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"streamEvents", "delegate", "disabled"})
    void recoveryOptInIsCallOnly(String kind) {
        AgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent =
                "disabled".equals(kind)
                        ? agentBuilder(model, store, new Toolkit()).disableCompaction().build()
                        : newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        Msg input = userMsg("must not recover");
        int before = model.mainInputs.size();
        model.script(overflow());
        if ("streamEvents".equals(kind)) {
            StepVerifier.create(agent.streamEvents(input, ctx).then())
                    .expectErrorMatches(e -> e.getMessage().contains("context_length_exceeded"))
                    .verify(Duration.ofSeconds(10));
        } else {
            StepVerifier.create(
                            "delegate".equals(kind)
                                    ? agent.getDelegate().call(List.of(input), ctx)
                                    : agent.call(input, ctx))
                    .expectErrorMatches(e -> e.getMessage().contains("context_length_exceeded"))
                    .verify(Duration.ofSeconds(10));
        }
        assertEquals(before + 1, model.mainInputs.size());
        assertEquals(0, model.summaryInputs.size());
        assertEquals(1, count(persistedContext(store), input));
    }

    @Test
    void attemptBudgetSpansLaterReasoningRounds() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        Toolkit toolkit = new Toolkit();
        Probe probe = new Probe();
        toolkit.registerTool(probe);
        HarnessAgent agent = newAgent(model, store, toolkit);
        RuntimeContext ctx = runPriorTurns(agent);
        Msg input = userMsg("overflow then tool then overflow");
        int before = model.mainInputs.size();
        model.script(overflow(), toolUse("retry-tool", "probe"), overflow());
        StepVerifier.create(agent.call(input, ctx))
                .expectErrorMatches(e -> e.getMessage().contains("context_length_exceeded"))
                .verify(Duration.ofSeconds(10));
        assertEquals(before + 3, model.mainInputs.size());
        assertEquals(1, model.summaryInputs.size());
        assertEquals(1, probe.calls.get());
        assertEquals(1, count(persistedContext(store), input));
        assertEquals(1, toolResults(persistedContext(store), "retry-tool"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"text", "thinking", "tool", "hidden-text", "hidden-thinking", "hidden-tool"})
    void partialReasoningOutputAbortsRatherThanCombiningIntoSuccess(String kind) {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent.Builder builder = agentBuilder(model, store, new Toolkit());
        if (kind.startsWith("hidden-")) {
            builder.middleware(
                    new MiddlewareBase() {
                        @Override
                        public Flux<AgentEvent> onModelCall(
                                Agent agent,
                                RuntimeContext rc,
                                ModelCallInput input,
                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                            Flux<AgentEvent> stream = next.apply(input);
                            return input.messages().stream()
                                            .anyMatch(
                                                    m ->
                                                            "partial overflow"
                                                                    .equals(m.getTextContent()))
                                    ? stream.filter(e -> e instanceof ModelCallStartEvent)
                                    : stream;
                        }
                    });
        }
        HarnessAgent agent = builder.build();
        RuntimeContext ctx = runPriorTurns(agent);
        Flux<ChatResponse> partial =
                switch (kind.replace("hidden-", "")) {
                    case "thinking" ->
                            Flux.just(
                                    new ChatResponse(
                                            "partial",
                                            List.of(
                                                    ThinkingBlock.builder()
                                                            .thinking("unfinished thought")
                                                            .build()),
                                            null,
                                            Map.of(),
                                            null));
                    case "tool" -> toolUse("partial-tool", "probe");
                    default -> Flux.just(textResponse("unfinished answer"));
                };
        model.script(partial.concatWith(overflow()));
        int before = model.mainInputs.size();
        StepVerifier.create(agent.call("partial overflow", ctx))
                .expectErrorMatches(e -> e.getMessage().contains("context_length_exceeded"))
                .verify(Duration.ofSeconds(10));
        assertEquals(before + 1, model.mainInputs.size());
        assertEquals(0, model.summaryInputs.size());
        assertFalse(hasSummary(persistedContext(store)));
        assertEquals(0, toolResults(persistedContext(store), "partial-tool"));
        assertTrue(
                persistedContext(store).stream()
                        .noneMatch(m -> m.getTextContent().contains("unfinished answer")));
    }

    @Test
    void emptyFailedChunksDoNotLeakUsageOrProviderMetadataIntoRetry() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        ChatResponse empty =
                new ChatResponse(
                        "failed",
                        List.of(),
                        ChatUsage.builder().inputTokens(123).build(),
                        Map.of("failed-provider-metadata", "must not leak"),
                        null);
        model.script(Flux.just(empty).concatWith(overflow()));
        Msg reply = agent.call("metadata-only overflow", ctx).block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals("ok", reply.getTextContent());
        assertFalse(reply.getMetadata().containsKey("failed-provider-metadata"));
        assertNull(reply.getUsage());
        assertEquals(1, model.summaryInputs.size());
    }

    @Test
    void retryPreservesSystemToolsOptionsAndRichInputIdentity() {
        AgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent =
                agentBuilder(model, store, new Toolkit())
                        .sysPrompt("preserve this system prompt")
                        .disableSubagents()
                        .generateOptions(
                                GenerateOptions.builder().temperature(0.25).maxTokens(321).build())
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                                        .flushPrompt("configured emergency flush")
                                        .build())
                        .build();
        RuntimeContext ctx = runPriorTurns(agent);
        Msg input =
                Msg.builder()
                        .id("rich-input-id")
                        .name("caller")
                        .role(MsgRole.USER)
                        .metadata(Map.of("request", "original"))
                        .content(
                                List.of(
                                        TextBlock.builder().text("rich input").build(),
                                        ImageBlock.builder()
                                                .source(new Base64Source("image/png", "AA=="))
                                                .build(),
                                        AudioBlock.builder()
                                                .source(new Base64Source("audio/wav", "AQ=="))
                                                .build()))
                        .build();
        int before = model.mainInputs.size();
        model.script(overflow());
        assertNotNull(agent.call(input, ctx).block(Duration.ofSeconds(10)));
        assertSame(model.mainInputs.get(before).get(0), model.mainInputs.get(before + 1).get(0));
        assertSame(model.mainTools.get(before), model.mainTools.get(before + 1));
        assertSame(model.mainOptions.get(before), model.mainOptions.get(before + 1));
        assertSame(
                input,
                model.lastMainInput().stream()
                        .filter(m -> m.getId().equals(input.getId()))
                        .findFirst()
                        .orElseThrow());
        assertSame(
                input,
                persistedContext(store).stream()
                        .filter(m -> m.getId().equals(input.getId()))
                        .findFirst()
                        .orElseThrow());
        assertEquals(
                "configured emergency flush", model.flushInputs.get(0).get(0).getTextContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "retry-error", "cancel", "interrupt"})
    void sandboxLeaseSpansRecoveryAndCleansUpOnEveryTerminalPath(String outcome) throws Exception {
        Sandbox sandbox = mock(Sandbox.class);
        SandboxState sandboxState = new SandboxState() {};
        sandboxState.setWorkspaceSpec(new WorkspaceSpec());
        when(sandbox.getState()).thenReturn(sandboxState);
        when(sandbox.isRunning()).thenReturn(true);
        SandboxClient<?> client = mock(SandboxClient.class);
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        when(sandbox.exec(any(), anyString(), any())).thenReturn(new ExecResult(0, "", "", false));
        SandboxFilesystemSpec spec =
                new SandboxFilesystemSpec() {
                    @Override
                    protected SandboxClient<?> createClient() {
                        return client;
                    }

                    @Override
                    protected SandboxClientOptions clientOptions() {
                        return null;
                    }

                    @Override
                    protected SandboxSnapshotSpec snapshotSpec() {
                        return null;
                    }

                    @Override
                    protected WorkspaceSpec workspaceSpec() {
                        return new WorkspaceSpec();
                    }
                };
        spec.workspaceProjectionEnabled(false);
        AtomicReference<RuntimeContext> scopedContext = new AtomicReference<>();
        MiddlewareBase capture =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent a,
                            RuntimeContext rc,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        scopedContext.set(rc);
                        return next.apply(input);
                    }
                };
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent =
                agentBuilder(model, new InMemoryAgentStateStore(), new Toolkit())
                        .filesystem(spec)
                        .disableMemoryHooks()
                        .disableTranscript()
                        .middleware(capture)
                        .build();
        RuntimeContext ctx = runPriorTurns(agent);
        clearInvocations(sandbox, client);
        PendingResponse summary = new PendingResponse();
        model.summaries.add(summary.stream());
        model.script(overflow());
        if ("retry-error".equals(outcome)) {
            model.script(Flux.error(new IllegalStateException("retry failed")));
        }
        var call =
                agent.call("sandbox overflow", ctx).subscribeOn(Schedulers.parallel()).toFuture();
        try {
            await(summary.subscribed);
            RuntimeContext live = scopedContext.get();
            assertSame(sandbox, live.get(SandboxAcquireResult.class).getSandbox());
            verify(sandbox, times(1)).start();
            verify(sandbox, times(0)).stop();
            verify(sandbox, times(0)).shutdown();
            if ("cancel".equals(outcome)) {
                assertTrue(call.cancel(true));
                await(summary.cancelled);
            } else if ("interrupt".equals(outcome)) {
                agent.interrupt(ctx);
                assertEquals(
                        GenerateReason.INTERRUPTED,
                        call.get(5, TimeUnit.SECONDS).getGenerateReason());
                await(summary.cancelled);
            } else {
                summary.complete(textResponse("sandbox summary"));
                if ("success".equals(outcome)) {
                    assertEquals("ok", call.get(5, TimeUnit.SECONDS).getTextContent());
                } else {
                    java.util.concurrent.ExecutionException error =
                            org.junit.jupiter.api.Assertions.assertThrows(
                                    java.util.concurrent.ExecutionException.class,
                                    () -> call.get(5, TimeUnit.SECONDS));
                    assertEquals("retry failed", error.getCause().getMessage());
                }
            }
            // Terminal completion of Mono.using's eager cleanup precedes the future result.
            // Cancellation propagates cleanup synchronously before cancel() returns.
            assertSame(live, scopedContext.get(), "Recovery must retain the admitted call context");
            assertNull(live.get(SandboxAcquireResult.class));
            verify(client, times(1)).create(any(), any(), any());
            verify(sandbox, times(1)).start();
            verify(sandbox, times(1)).stop();
            verify(sandbox, times(1)).shutdown();
        } finally {
            call.cancel(true);
        }
    }

    @Test
    void cancellingPendingRetryReleasesTheSameSession() throws Exception {
        AgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        PendingResponse retry = new PendingResponse();
        model.script(overflow(), retry.stream());
        int before = model.mainInputs.size();
        var call = agent.call("cancel retry", ctx).subscribeOn(Schedulers.parallel()).toFuture();
        try {
            await(retry.subscribed);
            assertTrue(hasSummary(model.lastMainInput()));
            assertTrue(call.cancel(true));
            await(retry.cancelled);
            assertEquals(
                    "ok",
                    agent.call("B after retry cancellation", ctx)
                            .block(Duration.ofSeconds(5))
                            .getTextContent());
            assertEquals(before + 3, model.mainInputs.size());
            assertEquals(1, model.summaryInputs.size());
        } finally {
            call.cancel(true);
        }
    }

    @Test
    void normalPreemptiveCompactionIsNotReenteredByTheNestedRetry() {
        AgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        RuntimeContext ctx = runPriorTurns(newAgent(model, store, new Toolkit()));
        Model preemptiveModel = mock(Model.class);
        when(preemptiveModel.stream(any(), any(), any()))
                .thenReturn(Flux.just(textResponse("verbose summary detail ".repeat(20))));
        HarnessAgent agent =
                agentBuilder(model, store, new Toolkit())
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(1)
                                        .keepTokens(0)
                                        .model(preemptiveModel)
                                        .flushBeforeCompact(false)
                                        .offloadBeforeCompact(false)
                                        .build())
                        .build();
        model.script(overflow());
        Msg input = userMsg("preemptive then emergency");
        assertNotNull(agent.call(input, ctx).block(Duration.ofSeconds(10)));
        verify(preemptiveModel, times(1)).stream(any(), any(), any());
        assertEquals(
                1,
                model.summaryInputs.size(),
                "Exactly one emergency summary, using primary model");
        assertEquals(1, count(persistedContext(store), input));
        assertTrue(hasSummary(model.lastMainInput()));
    }

    @Test
    void resubscribingACallGetsANewAttemptBudget() {
        AgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);
        var call = agent.call(List.of(), ctx);
        int before = model.mainInputs.size();
        for (int i = 0; i < 2; i++) {
            model.script(overflow());
            assertEquals("ok", call.block(Duration.ofSeconds(10)).getTextContent());
        }
        assertEquals(before + 4, model.mainInputs.size());
        assertEquals(2, model.summaryInputs.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void overflowRetryKeepsAdmittedPolicyAndPrecallAdministrationWins(String kind)
            throws Exception {
        AgentStateStore store = store(kind);
        ScriptedModel model = new ScriptedModel();
        PolicyProbe probe = new PolicyProbe();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(probe);
        PermissionContextState template = policy(PermissionBehavior.DENY);
        PermissionContextState precall = policy(PermissionBehavior.ALLOW);
        RuntimeContext ctx = RuntimeContext.builder().sessionId(SESSION_ID).build();
        try (HarnessAgent agent =
                agentBuilder(model, store, toolkit).permissionContext(template).build()) {
            agent.getDelegate().replacePermissionContext(null, SESSION_ID, precall);
            runPriorTurns(agent);
            assertEquals(precall, agent.getDelegate().getAgentState(ctx).getPermissionContext());
            PendingResponse summary = new PendingResponse();
            model.summaries.add(summary.stream());
            model.script(overflow());
            var older = agent.call("admitted before administration", ctx).toFuture();
            try {
                await(summary.subscribed);
                PermissionContextState replacement = policy(PermissionBehavior.DENY);
                agent.getDelegate().replacePermissionContext(null, SESSION_ID, replacement);
                assertEquals(
                        replacement, agent.getDelegate().getAgentState(ctx).getPermissionContext());
                assertFalse(hasSummary(persistedContext(store)));
                model.script(toolUse("older-policy", "policy_probe"));
                summary.complete(textResponse("recovered history"));
                assertEquals("ok", older.get(5, TimeUnit.SECONDS).getTextContent());
                assertEquals(1, probe.calls.get(), "older retry must retain its admitted ALLOW");
                assertTrue(
                        probe.checkedContexts.stream().allMatch(precall::equals),
                        "ToolBase checks must see the same admitted context after recovery");
                model.script(toolUse("next-policy", "policy_probe"));
                assertEquals(
                        "ok",
                        agent.call("next policy", ctx)
                                .block(Duration.ofSeconds(5))
                                .getTextContent());
                assertEquals(1, probe.calls.get(), "next call must use administered DENY");
                assertDenied(persistedContext(store), "next-policy");
                assertEquals(
                        replacement, agent.getDelegate().getAgentState(ctx).getPermissionContext());
                assertEquals(
                        replacement,
                        store.get(null, SESSION_ID, "agent_state", AgentState.class)
                                .orElseThrow()
                                .getPermissionContext());
                assertEquals(
                        policy(PermissionBehavior.DENY),
                        template,
                        "administration must not mutate the builder template");
            } finally {
                older.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void acceptedRulesReachCurrentAdminCacheAcrossOverflowAndTheNextCall(String kind)
            throws Exception {
        AgentStateStore store = store(kind);
        ScriptedModel model = new ScriptedModel();
        PolicyProbe probe = new PolicyProbe();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(probe);
        AtomicInteger confirmations = new AtomicInteger();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("confirm")
                                .description("confirm")
                                .inputSchema(Map.of("type", "object"))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.ask("confirm"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        confirmations.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("confirmed"));
                    }
                });
        AtomicBoolean holdNext = new AtomicBoolean();
        CountDownLatch admitted = new CountDownLatch(1);
        Sinks.Empty<Void> release = Sinks.empty();
        Hook holdConfirmation =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent && holdNext.compareAndSet(true, false)) {
                            admitted.countDown();
                            return release.asMono().thenReturn(event);
                        }
                        return Mono.just(event);
                    }
                };
        try (HarnessAgent agent =
                agentBuilder(model, store, toolkit)
                        .permissionContext(policy(PermissionBehavior.ALLOW))
                        .hook(holdConfirmation)
                        .build()) {
            RuntimeContext ctx = runPriorTurns(agent);
            model.script(toolUse("confirm-policy", "confirm"));
            Msg prompt = agent.call("confirm policy", ctx).block(Duration.ofSeconds(5));
            assertNotNull(prompt);
            assertEquals(GenerateReason.PERMISSION_ASKING, prompt.getGenerateReason());
            PermissionRule rule =
                    new PermissionRule(
                            "policy_probe", null, PermissionBehavior.DENY, "confirmation");
            Msg acceptance =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(
                                                    new ConfirmResult(
                                                            true,
                                                            prompt.getContentBlocks(
                                                                            ToolUseBlock.class)
                                                                    .get(0),
                                                            List.of(rule)))))
                            .build();
            PendingResponse summary = new PendingResponse();
            model.summaries.add(summary.stream());
            model.script(overflow());
            holdNext.set(true);
            var accepting = agent.call(acceptance, ctx).toFuture();
            try {
                await(admitted);
                PermissionContextState admin =
                        PermissionContextState.builder()
                                .mode(PermissionMode.BYPASS)
                                .addWorkingDirectory(
                                        "admin",
                                        new AdditionalWorkingDirectory(
                                                workspace.toString(), "admin"))
                                .addDenyRule(
                                        "audit",
                                        new PermissionRule(
                                                "audit",
                                                null,
                                                PermissionBehavior.DENY,
                                                "administrator"))
                                .build();
                agent.getDelegate().replacePermissionContext(null, SESSION_ID, admin);
                assertEquals(Sinks.EmitResult.OK, release.tryEmitEmpty());
                await(summary.subscribed);
                PermissionContextState expected = admin.withAddedRules(List.of(rule));
                assertEquals(
                        expected,
                        agent.getDelegate().getAgentState(ctx).getPermissionContext(),
                        "confirmation must update CURRENT cache without replacing admin settings");
                assertEquals(1, confirmations.get());
                model.script(toolUse("confirmed-retry", "policy_probe"));
                summary.complete(textResponse("recovered history"));
                assertEquals("ok", accepting.get(5, TimeUnit.SECONDS).getTextContent());
                assertDenied(persistedContext(store), "confirmed-retry");
                model.script(toolUse("confirmed-next", "policy_probe"));
                assertEquals(
                        "ok",
                        agent.call("next confirmed policy", ctx)
                                .block(Duration.ofSeconds(5))
                                .getTextContent());
                assertDenied(persistedContext(store), "confirmed-next");
                assertEquals(0, probe.calls.get());
                assertEquals(
                        expected, agent.getDelegate().getAgentState(ctx).getPermissionContext());
                assertEquals(
                        expected,
                        store.get(null, SESSION_ID, "agent_state", AgentState.class)
                                .orElseThrow()
                                .getPermissionContext());
            } finally {
                release.tryEmitEmpty();
                accepting.cancel(true);
            }
        }
    }

    @Test
    void cancelledRecoveredCallKeepsGateUntilItsAdmittedBlockingWriteFinishes() throws Exception {
        BlockingRecoveredStore store = new BlockingRecoveredStore();
        ScriptedModel model = new ScriptedModel();
        try (HarnessAgent agent = newAgent(model, store, new Toolkit())) {
            RuntimeContext ctx = runPriorTurns(agent);
            PendingResponse summary = new PendingResponse();
            model.summaries.add(summary.stream());
            model.script(overflow());
            store.hold.set(true);
            int loadsBefore = store.sameSlotLoads.get();
            Msg a = userMsg("A recovery then blocking save");
            Msg b = userMsg("B queued behind recovered A");
            var callA = agent.call(a, ctx).toFuture();
            var callB =
                    agent.call(b, RuntimeContext.builder().sessionId(SESSION_ID).build())
                            .toFuture();
            try {
                await(summary.subscribed);
                assertEquals(loadsBefore + 1, store.sameSlotLoads.get());
                assertFalse(callB.isDone(), "B must wait while emergency summary is pending");
                assertEquals(1, store.entered.getCount(), "no pre-retry terminal write");
                assertFalse(hasSummary(agent.getDelegate().getAgentState(ctx).getContext()));
                RuntimeContext otherUser =
                        RuntimeContext.builder().userId("other-user").sessionId(SESSION_ID).build();
                assertEquals(
                        "ok",
                        agent.call("other user during summary", otherUser)
                                .block(Duration.ofSeconds(5))
                                .getTextContent());
                summary.complete(textResponse("recovered history"));
                await(store.entered);
                assertTrue(callA.cancel(true));
                assertFalse(callB.isDone(), "cancel must not release B before the real write ends");
                assertEquals(
                        loadsBefore + 1,
                        store.sameSlotLoads.get(),
                        "B cannot load state during canceled-A write cleanup");
                assertEquals(1, store.finished.getCount(), "nonabortable write is still running");
                RuntimeContext otherSession =
                        RuntimeContext.builder().sessionId("other-write-session").build();
                assertEquals(
                        "ok",
                        agent.call("other session during write", otherSession)
                                .block(Duration.ofSeconds(5))
                                .getTextContent());
                assertFalse(callB.isDone());
                store.release.countDown();
                assertEquals("ok", callB.get(5, TimeUnit.SECONDS).getTextContent());
                await(store.finished);
                assertEquals(loadsBefore + 2, store.sameSlotLoads.get());
                assertEquals(1, model.summaryInputs.size(), "no extra recovery or admission");
                assertEquals(1, count(persistedContext(store), a));
                assertEquals(1, count(persistedContext(store), b));
                assertTrue(hasSummary(persistedContext(store)));
                assertEquals(0, agent.getDelegate().getStateConflictCount());
            } finally {
                store.release.countDown();
                callA.cancel(true);
                callB.cancel(true);
            }
        }
    }

    private static final class BlockingRecoveredStore extends InMemoryAgentStateStore {
        private final AtomicBoolean hold = new AtomicBoolean();
        private final AtomicInteger sameSlotLoads = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public <T extends State> io.agentscope.core.state.VersionedState<T> getVersioned(
                String userId, String sessionId, String key, Class<T> type) {
            if (userId == null && SESSION_ID.equals(sessionId)) {
                sameSlotLoads.incrementAndGet();
            }
            return super.getVersioned(userId, sessionId, key, type);
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long version) {
            if (userId == null
                    && SESSION_ID.equals(sessionId)
                    && "agent_state".equals(key)
                    && hold.compareAndSet(true, false)) {
                entered.countDown();
                boolean interrupted = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try {
                    while (release.getCount() != 0) {
                        try {
                            assertTrue(
                                    release.await(
                                            Math.max(1, deadline - System.nanoTime()),
                                            TimeUnit.NANOSECONDS),
                                    "blocking write must be released");
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    return super.saveIfVersion(userId, sessionId, key, value, version);
                } finally {
                    finished.countDown();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            return super.saveIfVersion(userId, sessionId, key, value, version);
        }
    }

    private static PermissionContextState policy(PermissionBehavior behavior) {
        return PermissionContextState.builder()
                .build()
                .withAddedRules(
                        List.of(new PermissionRule("policy_probe", null, behavior, "policy")));
    }

    private static void assertDenied(List<Msg> messages, String id) {
        assertTrue(
                messages.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(
                                r ->
                                        id.equals(r.getId())
                                                && r.getState()
                                                        == io.agentscope.core.message
                                                                .ToolResultState.DENIED));
    }

    private static final class PolicyProbe extends ToolBase {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<PermissionContextState> checkedContexts = new CopyOnWriteArrayList<>();

        PolicyProbe() {
            super(
                    ToolBase.builder()
                            .name("policy_probe")
                            .description("policy probe")
                            .inputSchema(Map.of("type", "object")));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState context) {
            checkedContexts.add(context);
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("observed"));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected model lifecycle signal");
    }

    private static boolean hasSummary(List<Msg> messages) {
        return messages.stream()
                .anyMatch(m -> ConversationCompactor.SUMMARY_MSG_NAME.equals(m.getName()));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(
                UUID.randomUUID().toString(),
                List.of(TextBlock.builder().text(text).build()),
                null,
                Map.of(),
                "stop");
    }

    private static final class PendingResponse {
        private final Sinks.One<ChatResponse> sink = Sinks.one();
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        Flux<ChatResponse> stream() {
            return sink.asMono()
                    .flux()
                    .doOnSubscribe(ignored -> subscribed.countDown())
                    .doOnCancel(cancelled::countDown);
        }

        void complete(ChatResponse response) {
            assertEquals(Sinks.EmitResult.OK, sink.tryEmitValue(response));
        }
    }

    private static final class SummarySaveFailingStore extends JsonFileAgentStateStore {
        private String saveThread;

        private SummarySaveFailingStore(Path root) {
            super(root);
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            if (value instanceof AgentState state
                    && state.getContext().stream()
                            .anyMatch(
                                    m ->
                                            ConversationCompactor.SUMMARY_MSG_NAME.equals(
                                                    m.getName()))) {
                saveThread = Thread.currentThread().getName();
                throw new IllegalStateException("compacted state save failed");
            }
            super.save(userId, sessionId, key, value);
        }
    }

    private HarnessAgent newAgent(Model model, AgentStateStore store, Toolkit toolkit) {
        return agentBuilder(model, store, toolkit).build();
    }

    private HarnessAgent.Builder agentBuilder(Model model, AgentStateStore store, Toolkit toolkit) {
        return HarnessAgent.builder()
                .name("overflow-agent")
                .model(model)
                .workspace(workspace)
                .toolkit(toolkit)
                .stateStore(store)
                .compaction(CompactionConfig.builder().triggerMessages(1_000).build())
                .memory(
                        MemoryConfig.builder()
                                .flushTrigger(MemoryConfig.FlushTrigger.never())
                                .build());
    }

    private AgentStateStore store(String kind) {
        return "json-file".equals(kind)
                ? new JsonFileAgentStateStore(stateDir)
                : new InMemoryAgentStateStore();
    }

    private static RuntimeContext runPriorTurns(HarnessAgent agent) {
        RuntimeContext ctx = RuntimeContext.builder().sessionId(SESSION_ID).build();
        for (int i = 0; i < PRIOR_TURNS; i++) {
            assertNotNull(agent.call(userMsg(priorTurn(i)), ctx).block(Duration.ofSeconds(30)));
        }
        return ctx;
    }

    /** Creates the padded text of one prior turn so folded turns stay identifiable. */
    private static String priorTurn(int i) {
        return "turn " + i + PAD;
    }

    private static List<Msg> persistedContext(AgentStateStore store) {
        return store.get(null, SESSION_ID, "agent_state", AgentState.class)
                .orElseThrow()
                .getContext();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static long count(List<Msg> msgs, Msg target) {
        return msgs.stream().filter(m -> Objects.equals(m.getId(), target.getId())).count();
    }

    private static long toolResults(List<Msg> msgs, String toolUseId) {
        return msgs.stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(r -> toolUseId.equals(r.getId()))
                .count();
    }

    private static Flux<ChatResponse> overflow() {
        return Flux.error(
                new RuntimeException(
                        "context_length_exceeded: this model's maximum context length was"
                                + " exceeded"));
    }

    private static Flux<ChatResponse> toolUse(String id, String name) {
        ToolUseBlock use =
                ToolUseBlock.builder().id(id).name(name).input(Map.of()).content("{}").build();
        return Flux.just(new ChatResponse("tool", List.of(use), null, Map.of(), "tool_use"));
    }

    /** A tool that counts its invocations. */
    public static class Probe {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Read a fixed probe value", readOnly = true)
        public String probe() {
            calls.incrementAndGet();
            return "observed";
        }
    }

    /**
     * Answers {@code "ok"} unless a scripted response is queued. Scripted responses only apply to
     * main-loop requests, which carry the agent's tool schemas; that tells them apart from the
     * compaction summary request.
     */
    private static final class ScriptedModel implements Model {

        private final Queue<Flux<ChatResponse>> script = new ConcurrentLinkedQueue<>();
        private final Queue<Flux<ChatResponse>> summaries = new ConcurrentLinkedQueue<>();
        private final List<List<Msg>> mainInputs = new CopyOnWriteArrayList<>();
        private final List<List<Msg>> summaryInputs = new CopyOnWriteArrayList<>();
        private final List<List<Msg>> flushInputs = new CopyOnWriteArrayList<>();
        private final List<List<ToolSchema>> mainTools = new CopyOnWriteArrayList<>();
        private final List<GenerateOptions> mainOptions = new CopyOnWriteArrayList<>();

        @SafeVarargs
        final void script(Flux<ChatResponse>... responses) {
            script.addAll(List.of(responses));
        }

        List<Msg> lastMainInput() {
            return mainInputs.get(mainInputs.size() - 1);
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (tools != null && !tools.isEmpty()) {
                mainInputs.add(List.copyOf(messages));
                mainTools.add(tools);
                mainOptions.add(options);
                Flux<ChatResponse> scripted = script.poll();
                return scripted != null ? scripted : Flux.just(textResponse("ok"));
            }
            if (messages.size() == 2 && messages.get(0).getRole() == MsgRole.SYSTEM) {
                flushInputs.add(List.copyOf(messages));
                return Flux.just(textResponse("NO_REPLY"));
            }
            summaryInputs.add(List.copyOf(messages));
            Flux<ChatResponse> summary = summaries.poll();
            return summary != null ? summary : Flux.just(textResponse("ok"));
        }
    }
}
