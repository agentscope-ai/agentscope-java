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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.journal.ThreadInteractions;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Persistence must acknowledge permission suspension before its prompt is observable. */
class ReActAgentPermissionPersistenceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedCheckpointPreservesAppendMergeHistory(boolean conflictBeforePrompt) {
        Fixture f = new Fixture(false);
        f.conflictPolicy = ConflictPolicy.APPEND_MERGE;
        var injected = new AtomicBoolean();
        Runnable concurrentWrite =
                () -> {
                    if (!injected.compareAndSet(false, true)) return;
                    var latest =
                            f.legacy
                                    .get(
                                            "permission-user",
                                            "permission-session",
                                            "agent_state",
                                            AgentState.class)
                                    .map(state -> AgentState.fromJsonString(state.toJson()))
                                    .orElseGet(
                                            () ->
                                                    AgentState.builder()
                                                            .userId("permission-user")
                                                            .sessionId("permission-session")
                                                            .build());
                    latest.contextMutable()
                            .add(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("concurrent input")
                                            .build());
                    f.legacy.save("permission-user", "permission-session", "agent_state", latest);
                };
        if (conflictBeforePrompt) f.beforeSuspensionSave = concurrentWrite;
        try (var agent = f.agent(true)) {
            agent.streamEvents(List.of(), context())
                    .doOnNext(
                            event -> {
                                if (!conflictBeforePrompt
                                        && event instanceof RequireUserConfirmEvent)
                                    concurrentWrite.run();
                            })
                    .collectList()
                    .block(TIMEOUT);
        }
        var saved =
                f.legacy
                        .get(
                                "permission-user",
                                "permission-session",
                                "agent_state",
                                AgentState.class)
                        .orElseThrow();
        assertEquals(
                1,
                saved.getContext().stream()
                        .filter(msg -> "concurrent input".equals(msg.getTextContent()))
                        .count());
        assertEquals(
                1,
                saved.getContext().stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                        .filter(tool -> "permission-tool".equals(tool.getId()))
                        .count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callAlsoReturnsRecoverablePermissionState(boolean journal) {
        Fixture f = new Fixture(journal);
        try (var agent = f.agent(true)) {
            Msg result = agent.call(List.of(), context()).block(TIMEOUT);
            assertNotNull(result);
            assertEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason());
            f.assertRecoverable(
                    new RequireUserConfirmEvent(
                            (String)
                                    result.getMetadata().get(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID),
                            result.getContentBlocks(ToolUseBlock.class)));
            assertEquals(0, f.executed.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmationIsRecoverableBeforePublication(boolean journal) {
        Fixture f = new Fixture(journal);
        try (var agent = f.agent(true)) {
            var events =
                    agent.streamEvents(List.of(), context())
                            .doOnNext(
                                    event -> {
                                        if (event instanceof RequireUserConfirmEvent request)
                                            f.assertRecoverable(request);
                                    })
                            .collectList()
                            .block(TIMEOUT);
            assertEquals(
                    1, events.stream().filter(RequireUserConfirmEvent.class::isInstance).count());
            if (journal)
                assertEquals(
                        1,
                        f.log().readAfter(0, 1000).stream()
                                .filter(e -> e.type().equals("interaction/requested"))
                                .count());
            assertEquals(0, f.executed.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationWaitsForPersistenceAcknowledgement(boolean journal) throws Exception {
        Fixture f = new Fixture(journal);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        f.beforeSuspensionSave =
                () -> {
                    entered.countDown();
                    await(release);
                };
        var events = new CopyOnWriteArrayList<AgentEvent>();
        try (var agent = f.agent(true)) {
            var completion =
                    agent.streamEvents(List.of(), context())
                            .doOnNext(events::add)
                            .then()
                            .subscribeOn(Schedulers.boundedElastic())
                            .toFuture();
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(events.stream().anyMatch(RequireUserConfirmEvent.class::isInstance));
            } finally {
                release.countDown();
                completion.get(10, TimeUnit.SECONDS);
            }
            assertEquals(
                    1, events.stream().filter(RequireUserConfirmEvent.class::isInstance).count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistenceFailureDoesNotPublishConfirmation(boolean journal) {
        Fixture f = new Fixture(journal);
        f.beforeSuspensionSave =
                () -> {
                    throw new IllegalStateException("suspension save failed");
                };
        var events = new CopyOnWriteArrayList<AgentEvent>();
        try (var agent = f.agent(true)) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            agent.streamEvents(List.of(), context())
                                    .doOnNext(events::add)
                                    .collectList()
                                    .block(TIMEOUT));
            assertFalse(events.stream().anyMatch(RequireUserConfirmEvent.class::isInstance));
            assertEquals(0, f.executed.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forwardingAlsoWaitsForRecoverableSuspension(boolean journal) {
        Fixture f = new Fixture(journal);
        var forwarded = new CopyOnWriteArrayList<AgentEvent>();
        AgentEventEmitter emitter =
                event -> {
                    if (event instanceof RequireUserConfirmEvent request) {
                        f.assertRecoverable(request);
                        assertNotNull(request.getExecution());
                    }
                    forwarded.add(event);
                };
        try (var agent = f.agent(true)) {
            agent.streamEvents(List.of(), context())
                    .contextWrite(ctx -> ctx.put(AgentEventEmitter.FORWARDING_CONTEXT_KEY, emitter))
                    .collectList()
                    .block(TIMEOUT);
            assertEquals(
                    1,
                    forwarded.stream().filter(RequireUserConfirmEvent.class::isInstance).count());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void rebuiltAgentCanApproveOrDenyDurableSuspension(boolean journal, boolean approved) {
        Fixture f = new Fixture(journal);
        RequireUserConfirmEvent request;
        try (var first = f.agent(true)) {
            request =
                    (RequireUserConfirmEvent)
                            first.streamEvents(List.of(), context())
                                    .filter(RequireUserConfirmEvent.class::isInstance)
                                    .blockFirst(TIMEOUT);
        }
        // Cancel on the prompt rather than relying on the normal call-end save.
        assertNotNull(request);
        // Cancellation cleanup releases the native writer asynchronously; a replacement
        // invocation must wait for that release rather than racing the active lease.
        if (journal) await(f.writerReleased);
        try (var resumed = f.agent(false)) {
            var input =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(
                                                    new ConfirmResult(
                                                            approved,
                                                            request.getToolCalls().get(0)))))
                            .build();
            var events =
                    resumed.streamEvents(List.of(input), context()).collectList().block(TIMEOUT);
            assertFalse(events.stream().anyMatch(RequireUserConfirmEvent.class::isInstance));
            assertTrue(
                    MessageUtils.pendingToolUseIds(resumed.getAgentState(context()).getContext())
                            .isEmpty());
            if (!approved)
                assertTrue(
                        events.stream()
                                .anyMatch(
                                        e ->
                                                e instanceof ToolResultEndEvent end
                                                        && end.getState() == ToolResultState.DENIED
                                                        && request.getReplyId()
                                                                .equals(end.getReplyId())));
            assertEquals(approved ? 1 : 0, f.executed.get());
        }
        try (var verifier = f.agent(false)) {
            List<ToolUseBlock> persistedCalls =
                    verifier.getAgentState(context()).getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                            .toList();
            assertEquals(1, persistedCalls.size());
            assertEquals(
                    approved ? ToolCallState.ALLOWED : ToolCallState.FINISHED,
                    persistedCalls.get(0).getState());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void singleDenialExitsAskingStateAndAllowsFollowUpTurn(
            boolean journal, boolean stopOnAllToolsDenied) {
        Fixture f = new Fixture(journal);
        Msg paused;
        try (var first = f.agent(true)) {
            paused = first.call(List.of(), context()).block(TIMEOUT);
            assertNotNull(paused);
            assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        }
        ToolUseBlock asking = paused.getContentBlocks(ToolUseBlock.class).get(0);
        assertEquals(ToolCallState.ASKING, asking.getState());

        MiddlewareBase stopMiddleware =
                stopOnAllToolsDenied
                        ? new MiddlewareBase() {
                            @Override
                            public Set<ExtensionPoint> activePoints() {
                                return EnumSet.of(ExtensionPoint.ON_ACTING);
                            }

                            @Override
                            public Flux<AgentEvent> onActing(
                                    Agent agent,
                                    RuntimeContext ctx,
                                    ActingInput input,
                                    Function<ActingInput, Flux<AgentEvent>> next) {
                                return next.apply(input)
                                        .concatMap(
                                                ev ->
                                                        ev instanceof AllToolsDeniedEvent
                                                                ? Flux.just(
                                                                        ev,
                                                                        new RequestStopEvent(
                                                                                "all tools denied",
                                                                                GenerateReason
                                                                                        .ALL_TOOLS_DENIED))
                                                                : Flux.just(ev));
                            }
                        }
                        : null;

        try (var resumed =
                f.customAgent(
                        List.of(TextBlock.builder().text("after-deny").build()),
                        false,
                        stopMiddleware)) {
            Msg denyReply =
                    resumed.call(
                                    List.of(
                                            confirmInput(
                                                    List.of(new ConfirmResult(false, asking)))),
                                    context())
                            .block(TIMEOUT);
            assertNotNull(denyReply);
            if (stopOnAllToolsDenied) {
                assertEquals(GenerateReason.ALL_TOOLS_DENIED, denyReply.getGenerateReason());
                assertEquals(
                        ToolCallState.FINISHED,
                        denyReply.getContentBlocks(ToolUseBlock.class).get(0).getState());
            } else {
                assertNotEquals(GenerateReason.PERMISSION_ASKING, denyReply.getGenerateReason());
                assertEquals("after-deny", denyReply.getTextContent());
            }
        }
        assertEquals(0, f.executed.get());

        try (var followUp =
                f.customAgent(
                        List.of(TextBlock.builder().text("normal-follow-up").build()),
                        false,
                        null)) {
            AgentState persisted = followUp.getAgentState(context());
            assertTrue(
                    persisted.getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                            .noneMatch(t -> t.getState() == ToolCallState.ASKING),
                    "denied tool call must not remain in ASKING state in persisted AgentState");
            if (journal) {
                assertTrue(ThreadInteractions.pending(f.log()).isEmpty());
            }
            Msg nextReply =
                    followUp.call(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("next user turn")
                                                    .build()),
                                    context())
                            .block(TIMEOUT);
            assertNotNull(nextReply);
            assertNotEquals(GenerateReason.PERMISSION_ASKING, nextReply.getGenerateReason());
            assertEquals("normal-follow-up", nextReply.getTextContent());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialDenialInMultiToolBatchTransitionsDeniedToolOutOfAsking(boolean journal) {
        Fixture f = new Fixture(journal);
        ToolUseBlock tc1 =
                ToolUseBlock.builder().id("tc-1").name("ask").input(Map.of("k", "1")).build();
        ToolUseBlock tc2 =
                ToolUseBlock.builder().id("tc-2").name("ask").input(Map.of("k", "2")).build();

        try (var first = f.customAgent(List.of(tc1, tc2), false, null)) {
            Msg paused = first.call(List.of(), context()).block(TIMEOUT);
            assertNotNull(paused);
            assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
            assertEquals(
                    2,
                    paused.getContentBlocks(ToolUseBlock.class).stream()
                            .filter(t -> t.getState() == ToolCallState.ASKING)
                            .count());
        }

        // Fresh runtime #2: deny only tc-1; tc-2 must remain the sole ASKING tool call.
        try (var second =
                f.customAgent(List.of(TextBlock.builder().text("unused").build()), false, null)) {
            Msg reAsking =
                    second.call(
                                    List.of(confirmInput(List.of(new ConfirmResult(false, tc1)))),
                                    context())
                            .block(TIMEOUT);
            assertNotNull(reAsking);
            assertEquals(GenerateReason.PERMISSION_ASKING, reAsking.getGenerateReason());
            List<ToolUseBlock> blocks = reAsking.getContentBlocks(ToolUseBlock.class);
            assertEquals(2, blocks.size());
            assertEquals(ToolCallState.FINISHED, blocks.get(0).getState());
            assertEquals(ToolCallState.ASKING, blocks.get(1).getState());
        }

        // Fresh runtime #3: persisted state has only tc-2 in ASKING; denying tc-2 exits
        // PERMISSION_ASKING.
        try (var third =
                f.customAgent(
                        List.of(TextBlock.builder().text("batch-resolved").build()), false, null)) {
            List<ToolUseBlock> stillAsking =
                    third.getAgentState(context()).getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                            .filter(t -> t.getState() == ToolCallState.ASKING)
                            .toList();
            assertEquals(1, stillAsking.size());
            assertEquals("tc-2", stillAsking.get(0).getId());

            Msg done =
                    third.call(
                                    List.of(confirmInput(List.of(new ConfirmResult(false, tc2)))),
                                    context())
                            .block(TIMEOUT);
            assertNotNull(done);
            assertNotEquals(GenerateReason.PERMISSION_ASKING, done.getGenerateReason());
            assertEquals("batch-resolved", done.getTextContent());
            assertTrue(
                    third.getAgentState(context()).getContext().stream()
                            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                            .noneMatch(t -> t.getState() == ToolCallState.ASKING));
        }
        assertEquals(0, f.executed.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedDenialAndExternalSuspensionResumesCleanly(boolean journal) {
        Fixture f = new Fixture(journal);
        ToolUseBlock tc1 = ToolUseBlock.builder().id("tc-deny").name("ask").input(Map.of()).build();
        ToolUseBlock tc2 =
                ToolUseBlock.builder().id("tc-suspend").name("ask_suspend").input(Map.of()).build();

        try (var first = f.customAgent(List.of(tc1, tc2), true, null)) {
            Msg paused = first.call(List.of(), context()).block(TIMEOUT);
            assertNotNull(paused);
            assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        }

        // Fresh runtime #2: deny tc-deny and approve tc-suspend (which throws
        // ToolSuspendException).
        try (var second =
                f.customAgent(List.of(TextBlock.builder().text("unused").build()), true, null)) {
            Msg suspended =
                    second.call(
                                    List.of(
                                            confirmInput(
                                                    List.of(
                                                            new ConfirmResult(false, tc1),
                                                            new ConfirmResult(true, tc2)))),
                                    context())
                            .block(TIMEOUT);
            assertNotNull(suspended);
            assertEquals(GenerateReason.TOOL_SUSPENDED, suspended.getGenerateReason());
        }

        // Fresh runtime #3: deliver the external ToolResultBlock for tc-suspend; must not re-enter
        // PERMISSION_ASKING for already-denied tc-deny.
        try (var third =
                f.customAgent(
                        List.of(TextBlock.builder().text("after-external").build()), true, null)) {
            Msg externalResultMsg =
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.text("ext-ok")
                                            .withIdAndName("tc-suspend", "ask_suspend"))
                            .build();
            Msg completed = third.call(List.of(externalResultMsg), context()).block(TIMEOUT);
            assertNotNull(completed);
            assertNotEquals(GenerateReason.PERMISSION_ASKING, completed.getGenerateReason());
            assertEquals("after-external", completed.getTextContent());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyPersistedStateWithDeniedResultAndAskingToolUseResumesCleanly(boolean journal) {
        Fixture f = new Fixture(journal);
        ToolUseBlock tc1 =
                ToolUseBlock.builder()
                        .id("tc-1")
                        .name("ask")
                        .input(Map.of())
                        .state(ToolCallState.ASKING)
                        .build();
        ToolUseBlock tc2 =
                ToolUseBlock.builder()
                        .id("tc-2")
                        .name("ask")
                        .input(Map.of())
                        .state(ToolCallState.ASKING)
                        .build();

        // Simulate a legacy state persisted before this fix: tc-1 already has a DENIED
        // ToolResultBlock in context, while its ToolUseBlock on the assistant message was left
        // in ToolCallState.ASKING and tc-2 is still pending confirmation.
        AgentState legacyState =
                AgentState.builder()
                        .userId("permission-user")
                        .sessionId("permission-session")
                        .build();
        legacyState
                .contextMutable()
                .add(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("permission-agent")
                                .content(List.of(tc1, tc2))
                                .metadata(
                                        Map.of(
                                                Msg.METADATA_CONFIRM_REQUEST_REPLY_ID,
                                                "legacy-reply"))
                                .build());
        legacyState
                .contextMutable()
                .add(
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.text("Permission denied by user")
                                                .withIdAndName("tc-1", "ask")
                                                .withState(ToolResultState.DENIED))
                                .build());
        f.legacy.save("permission-user", "permission-session", "agent_state", legacyState);

        // Fresh runtime must only require confirmation for still-pending tc-2, not already-denied
        // tc-1.
        try (var resumed =
                f.customAgent(
                        List.of(TextBlock.builder().text("legacy-recovered").build()),
                        false,
                        null)) {
            Msg done =
                    resumed.call(
                                    List.of(confirmInput(List.of(new ConfirmResult(false, tc2)))),
                                    context())
                            .block(TIMEOUT);
            assertNotNull(done);
            assertNotEquals(GenerateReason.PERMISSION_ASKING, done.getGenerateReason());
            assertEquals("legacy-recovered", done.getTextContent());
        }
    }

    private static Msg confirmInput(List<ConfirmResult> results) {
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent("")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    private static RuntimeContext context() {
        return RuntimeContext.builder()
                .userId("permission-user")
                .sessionId("permission-session")
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("save gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class Fixture {
        final boolean journal;
        ConflictPolicy conflictPolicy = ConflictPolicy.OVERWRITE;
        final AtomicInteger executed = new AtomicInteger();
        final CountDownLatch writerReleased = new CountDownLatch(1);
        Runnable beforeSuspensionSave = () -> {};
        final InMemoryThreadJournalStore backing = new InMemoryThreadJournalStore();
        final InMemoryAgentStateStore legacy =
                new InMemoryAgentStateStore() {
                    @Override
                    public long saveIfVersion(
                            String user, String session, String key, State state, long version) {
                        if (state instanceof AgentState agentState
                                && !MessageUtils.pendingToolUseIds(agentState.getContext())
                                        .isEmpty()) beforeSuspensionSave.run();
                        return super.saveIfVersion(user, session, key, state, version);
                    }
                };

        Fixture(boolean journal) {
            this.journal = journal;
        }

        ThreadJournal log() {
            return backing.open(
                    new ThreadKey(
                            "permission-user", "permission-agent", "permission-session", "main"),
                    context());
        }

        ReActAgent agent(boolean ask) {
            var calls = new AtomicInteger();
            Model model =
                    new Model() {
                        @Override
                        public String getModelName() {
                            return "permission-fixture";
                        }

                        @Override
                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    ask && calls.getAndIncrement() == 0
                                                            ? List.of(
                                                                    ToolUseBlock.builder()
                                                                            .id("permission-tool")
                                                                            .name("ask")
                                                                            .input(Map.of())
                                                                            .build())
                                                            : List.of(
                                                                    TextBlock.builder()
                                                                            .text("done")
                                                                            .build()))
                                            .build());
                        }
                    };
            var toolkit = new Toolkit();
            toolkit.registerAgentTool(
                    new ToolBase(
                            "ask",
                            "asks",
                            Map.of("type", "object"),
                            false,
                            true,
                            false,
                            null,
                            false,
                            false) {
                        @Override
                        public Mono<PermissionDecision> checkPermissions(
                                Map<String, Object> input, PermissionContextState state) {
                            return Mono.just(PermissionDecision.ask("confirm"));
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            executed.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("executed"));
                        }
                    });
            var builder =
                    ReActAgent.builder()
                            .name("permission-agent")
                            .model(model)
                            .toolkit(toolkit)
                            .conflictPolicy(conflictPolicy);
            if (journal)
                builder.threadJournalStore(
                        (key, ctx) ->
                                new InterceptedLog(
                                        backing.open(key, ctx),
                                        () -> beforeSuspensionSave.run(),
                                        writerReleased));
            else builder.stateStore(legacy);
            return builder.build();
        }

        ReActAgent customAgent(
                List<io.agentscope.core.message.ContentBlock> firstResponseBlocks,
                boolean includeSuspendingTool,
                MiddlewareBase middleware) {
            var calls = new AtomicInteger();
            Model model =
                    new Model() {
                        @Override
                        public String getModelName() {
                            return "permission-fixture";
                        }

                        @Override
                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    calls.getAndIncrement() == 0
                                                            ? firstResponseBlocks
                                                            : List.of(
                                                                    TextBlock.builder()
                                                                            .text("done")
                                                                            .build()))
                                            .build());
                        }
                    };
            var toolkit = new Toolkit();
            toolkit.registerAgentTool(
                    new ToolBase(
                            "ask",
                            "asks",
                            Map.of("type", "object"),
                            false,
                            true,
                            false,
                            null,
                            false,
                            false) {
                        @Override
                        public Mono<PermissionDecision> checkPermissions(
                                Map<String, Object> input, PermissionContextState state) {
                            return Mono.just(PermissionDecision.ask("confirm"));
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            executed.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("executed"));
                        }
                    });
            if (includeSuspendingTool) {
                toolkit.registerAgentTool(
                        new ToolBase(
                                "ask_suspend",
                                "asks then suspends",
                                Map.of("type", "object"),
                                false,
                                true,
                                false,
                                null,
                                false,
                                false) {
                            @Override
                            public Mono<PermissionDecision> checkPermissions(
                                    Map<String, Object> input, PermissionContextState state) {
                                return Mono.just(PermissionDecision.ask("confirm suspend"));
                            }

                            @Override
                            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                                return Mono.error(new ToolSuspendException("wait external"));
                            }
                        });
            }
            var builder =
                    ReActAgent.builder()
                            .name("permission-agent")
                            .model(model)
                            .toolkit(toolkit)
                            .stateStore(legacy)
                            .conflictPolicy(conflictPolicy);
            if (middleware != null) {
                builder.middleware(middleware);
            }
            if (journal)
                builder.threadJournalStore(
                        (key, ctx) ->
                                new InterceptedLog(
                                        backing.open(key, ctx),
                                        () -> beforeSuspensionSave.run(),
                                        writerReleased));
            return builder.build();
        }

        void assertRecoverable(RequireUserConfirmEvent request) {
            try (var fresh = agent(false)) {
                var state = fresh.getAgentState(context());
                assertEquals(
                        java.util.Set.of("permission-tool"),
                        MessageUtils.pendingToolUseIds(state.getContext()));
                var assistant = MessageUtils.lastAssistantMessage(state.getContext());
                assertEquals(
                        ToolCallState.ASKING,
                        assistant.getContentBlocks(ToolUseBlock.class).get(0).getState());
                assertEquals(
                        request.getReplyId(),
                        assistant.getMetadata().get(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID));
                if (journal)
                    assertTrue(ThreadInteractions.pending(log()).containsKey("permission-tool"));
            }
        }
    }

    private record InterceptedLog(
            ThreadJournal delegate, Runnable beforeSave, CountDownLatch released)
            implements ThreadJournal {
        public Head head() {
            return delegate.head();
        }

        public Writer acquire(String owner, Duration lease) {
            return delegate.acquire(owner, lease);
        }

        public Head sealWriter(String owner) {
            return delegate.sealWriter(owner);
        }

        public void renew(Writer writer, Duration lease) {
            delegate.renew(writer, lease);
        }

        public void release(Writer writer) {
            delegate.release(writer);
            released.countDown();
        }

        public List<JournalEvent> readAfter(long after, int limit) {
            return delegate.readAfter(after, limit);
        }

        public Head commit(Writer writer, String id, long expected, List<JournalEvent> events) {
            if (events.stream().anyMatch(e -> e.type().equals("interaction/requested")))
                beforeSave.run();
            return delegate.commit(writer, id, expected, events);
        }
    }
}
