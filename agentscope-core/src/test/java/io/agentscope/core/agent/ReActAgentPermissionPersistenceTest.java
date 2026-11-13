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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
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
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.MessageUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Persistence must acknowledge permission suspension before its prompt is observable. */
class ReActAgentPermissionPersistenceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void checkpointMergeKeepsLiveCacheAndAdminSaveHistoryAligned() {
        Fixture f = new Fixture();
        f.conflictPolicy = ConflictPolicy.APPEND_MERGE;
        AtomicBoolean injected = new AtomicBoolean();
        AtomicReference<AgentState> live = new AtomicReference<>();
        f.beforeSuspensionSave =
                () -> {
                    if (injected.compareAndSet(false, true)) {
                        AgentState concurrent =
                                AgentState.builder()
                                        .userId("permission-user")
                                        .sessionId("permission-session")
                                        .build();
                        concurrent
                                .contextMutable()
                                .add(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("concurrent input")
                                                .build());
                        f.legacy.save(
                                "permission-user", "permission-session", "agent_state", concurrent);
                    }
                };
        f.middleware =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onActing(
                            Agent agent,
                            RuntimeContext ctx,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        live.set(ctx.getAgentState());
                        return next.apply(input)
                                .concatWith(
                                        Flux.defer(
                                                () -> {
                                                    // This runs after the real permission
                                                    // checkpoint, before the normal call-end save.
                                                    assertTrue(injected.get());
                                                    assertEquals(
                                                            2L,
                                                            f.legacy
                                                                    .getVersioned(
                                                                            "permission-user",
                                                                            "permission-session",
                                                                            "agent_state",
                                                                            AgentState.class)
                                                                    .version());
                                                    ctx.getAgentState()
                                                            .contextMutable()
                                                            .add(
                                                                    Msg.builder()
                                                                            .role(MsgRole.USER)
                                                                            .textContent(
                                                                                    "after"
                                                                                        + " checkpoint")
                                                                            .build());
                                                    return Flux.empty();
                                                }));
                    }
                };
        try (ReActAgent agent = f.agent(true)) {
            StepVerifier.create(agent.streamEvents(List.of(), context()).collectList())
                    .assertNext(
                            events ->
                                    assertEquals(
                                            1,
                                            events.stream()
                                                    .filter(
                                                            RequireUserConfirmEvent.class
                                                                    ::isInstance)
                                                    .count()))
                    .expectComplete()
                    .verify(TIMEOUT);
            assertEquals(1L, agent.getStateConflictCount());
            var completed =
                    f.legacy.getVersioned(
                            "permission-user",
                            "permission-session",
                            "agent_state",
                            AgentState.class);
            assertEquals(3L, completed.version());
            List<String> history = completed.value().getContext().stream().map(Msg::getId).toList();
            assertEquals(
                    1L,
                    completed.value().getContext().stream()
                            .filter(msg -> "after checkpoint".equals(msg.getTextContent()))
                            .count());
            AgentState cached = agent.getAgentState(context());
            assertEquals(history, cached.getContext().stream().map(Msg::getId).toList());
            assertSame(
                    live.get(),
                    cached,
                    "admin APIs must see the live call state, not the merge baseline");
            PermissionContextState replacement =
                    PermissionContextState.builder()
                            .addDenyRule(
                                    "ask",
                                    new PermissionRule(
                                            "ask", null, PermissionBehavior.DENY, "admin"))
                            .build();
            agent.replacePermissionContext("permission-user", "permission-session", replacement);
            var replaced =
                    f.legacy.getVersioned(
                            "permission-user",
                            "permission-session",
                            "agent_state",
                            AgentState.class);
            assertEquals(4L, replaced.version());
            assertEquals(history, replaced.value().getContext().stream().map(Msg::getId).toList());
            assertEquals(
                    replacement.getDenyRules(),
                    replaced.value().getPermissionContext().getDenyRules());
            assertEquals(
                    1L,
                    replaced.value().getContext().stream()
                            .filter(msg -> "concurrent input".equals(msg.getTextContent()))
                            .count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedCheckpointPreservesAppendMergeHistory(boolean conflictBeforePrompt) {
        Fixture f = new Fixture();
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

    @Test
    void callAlsoReturnsRecoverablePermissionState() {
        Fixture f = new Fixture();
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

    @Test
    void confirmationIsRecoverableBeforePublication() {
        Fixture f = new Fixture();
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
            assertEquals(0, f.executed.get());
        }
    }

    @Test
    void publicationWaitsForPersistenceAcknowledgement() throws Exception {
        Fixture f = new Fixture();
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

    @Test
    void persistenceFailureDoesNotPublishConfirmation() {
        Fixture f = new Fixture();
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

    @Test
    void forwardingAlsoWaitsForRecoverableSuspension() {
        Fixture f = new Fixture();
        var forwarded = new CopyOnWriteArrayList<AgentEvent>();
        AgentEventEmitter emitter =
                event -> {
                    if (event instanceof RequireUserConfirmEvent request) {
                        f.assertRecoverable(request);
                    }
                    forwarded.add(event);
                };
        try (var agent = f.agent(true)) {
            agent.call(List.of(), context())
                    .contextWrite(ctx -> ctx.put(AgentEventEmitter.FORWARDING_CONTEXT_KEY, emitter))
                    .block(TIMEOUT);
            assertEquals(
                    1,
                    forwarded.stream().filter(RequireUserConfirmEvent.class::isInstance).count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebuiltAgentCanApproveOrDenyDurableSuspension(boolean approved) {
        Fixture f = new Fixture();
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
        ConflictPolicy conflictPolicy = ConflictPolicy.OVERWRITE;
        final AtomicInteger executed = new AtomicInteger();
        Runnable beforeSuspensionSave = () -> {};
        MiddlewareBase middleware;
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
            builder.stateStore(legacy);
            if (middleware != null) builder.middleware(middleware);
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
            }
        }
    }
}
