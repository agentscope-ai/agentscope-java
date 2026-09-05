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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Regression tests for durable conversation state after abnormal call termination. */
@DisplayName("ReActAgent abnormal-call persistence")
class ReActAgentCallFailurePersistenceTest {

    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("u1").sessionId("session-1").build();

    @Test
    @DisplayName("failed model stream persists the user input but not incomplete model output")
    void failedStreamPersistsOnlySafeConversationState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeException modelFailure = new RuntimeException("model stream failed");
        ReActAgent agent = agent(new AlwaysFailingModel(modelFailure), store);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> agent.call(List.of(userMsg("original question")), CONTEXT).block());

        assertSame(modelFailure, thrown);
        AgentState persisted =
                store.get("u1", "session-1", "agent_state", AgentState.class).orElseThrow();
        assertEquals(List.of("original question"), textContents(persisted.getContext()));
        assertEquals(
                List.of(MsgRole.USER), persisted.getContext().stream().map(Msg::getRole).toList());
        assertFalse(
                persisted.getContext().stream()
                        .flatMap(msg -> msg.getContent().stream())
                        .anyMatch(ToolUseBlock.class::isInstance),
                "an incomplete tool call must not enter durable model history");
    }

    @Test
    @DisplayName("same agent can continue with the user input from the failed turn")
    void sameAgentCanContinueAfterFailure() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        FailOnceThenCaptureModel model = new FailOnceThenCaptureModel();
        ReActAgent agent = agent(model, store);

        assertThrows(
                RuntimeException.class,
                () -> agent.call(List.of(userMsg("original question")), CONTEXT).block());
        Msg response = agent.call(List.of(userMsg("continue")), CONTEXT).block();

        assertEquals("recovered", response.getTextContent());
        List<String> secondPrompt = textContents(model.calls().get(1));
        assertTrue(secondPrompt.contains("original question"));
        assertTrue(secondPrompt.contains("continue"));
        assertFalse(secondPrompt.contains("incomplete answer"));
    }

    @Test
    @DisplayName("a rebuilt agent can continue with the user input from the failed turn")
    void rebuiltAgentCanContinueAfterFailure() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent failingAgent =
                agent(new AlwaysFailingModel(new RuntimeException("model stream failed")), store);

        assertThrows(
                RuntimeException.class,
                () -> failingAgent.call(List.of(userMsg("original question")), CONTEXT).block());

        CapturingModel recoveryModel = new CapturingModel();
        ReActAgent rebuiltAgent = agent(recoveryModel, store);
        rebuiltAgent.call(List.of(userMsg("continue")), CONTEXT).block();

        List<String> recoveryPrompt = textContents(recoveryModel.calls().get(0));
        assertTrue(recoveryPrompt.contains("original question"));
        assertTrue(recoveryPrompt.contains("continue"));
        assertFalse(recoveryPrompt.contains("incomplete answer"));
    }

    @Test
    @DisplayName("state-store failure does not replace the original model failure")
    void persistenceFailureDoesNotMaskModelFailure() {
        RuntimeException modelFailure = new RuntimeException("model stream failed");
        RuntimeException storeFailure = new RuntimeException("state store failed");
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public long saveIfVersion(
                            String userId,
                            String sessionId,
                            String key,
                            State value,
                            long expectedVersion) {
                        throw storeFailure;
                    }
                };
        ReActAgent agent = agent(new AlwaysFailingModel(modelFailure), store);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> agent.call(List.of(userMsg("original question")), CONTEXT).block());

        assertSame(modelFailure, thrown);
        assertTrue(
                List.of(thrown.getSuppressed()).contains(storeFailure),
                "the persistence failure should remain available for diagnostics");
    }

    @Test
    @DisplayName("the same call and state-store failure is not self-suppressed")
    void identicalPersistenceAndModelFailureIsNotSelfSuppressed() {
        RuntimeException sharedFailure = new RuntimeException("shared failure");
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public long saveIfVersion(
                            String userId,
                            String sessionId,
                            String key,
                            State value,
                            long expectedVersion) {
                        throw sharedFailure;
                    }
                };
        ReActAgent agent = agent(new AlwaysFailingModel(sharedFailure), store);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> agent.call(List.of(userMsg("original question")), CONTEXT).block());

        assertSame(sharedFailure, thrown);
        assertFalse(List.of(thrown.getSuppressed()).contains(sharedFailure));
    }

    @Test
    @DisplayName("failed structured-output fallback also persists the user input")
    void failedStructuredOutputFallbackPersistsUserInput() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent =
                agent(new AlwaysFailingModel(new RuntimeException("model stream failed")), store);

        assertThrows(
                RuntimeException.class,
                () ->
                        agent.call(List.of(userMsg("structured question")), StructuredReply.class)
                                .block());

        AgentState persisted =
                store.get(null, agent.getDefaultSessionId(), "agent_state", AgentState.class)
                        .orElseThrow();
        assertTrue(textContents(persisted.getContext()).contains("structured question"));
        assertFalse(textContents(persisted.getContext()).contains("incomplete answer"));
    }

    @ParameterizedTest(name = "structured={0}, native={1}")
    @CsvSource({"false, false", "true, false", "true, true"})
    @DisplayName("cancelled calls persist only safe conversation state")
    void cancelledCallPersistsOnlySafeConversationState(
            boolean structuredOutput, boolean nativeStructuredOutput) throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        CountDownLatch responseEmitted = new CountDownLatch(1);
        ReActAgent agent =
                agent(new NeverCompletingModel(responseEmitted, nativeStructuredOutput), store);

        Disposable subscription =
                structuredOutput
                        ? agent.call(
                                        List.of(userMsg("original question")),
                                        StructuredReply.class,
                                        CONTEXT)
                                .subscribe()
                        : agent.call(List.of(userMsg("original question")), CONTEXT).subscribe();
        try {
            assertTrue(responseEmitted.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }

        AgentState persisted =
                store.get("u1", "session-1", "agent_state", AgentState.class).orElseThrow();
        assertEquals(List.of("original question"), textContents(persisted.getContext()));
        assertFalse(hasToolUse(persisted));
    }

    @Test
    @DisplayName("tool execution is cancelled before state is saved")
    void cancellationStopsAllowedToolBeforeSavingState() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch toolCancelled = new CountDownLatch(1);
        AtomicBoolean savedBeforeToolCancellation = new AtomicBoolean();
        AtomicInteger savesAfterToolStarted = new AtomicInteger();
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public long saveIfVersion(
                            String userId,
                            String sessionId,
                            String key,
                            State value,
                            long expectedVersion) {
                        if (toolStarted.getCount() == 0) {
                            savesAfterToolStarted.incrementAndGet();
                            if (toolCancelled.getCount() != 0) {
                                savedBeforeToolCancellation.set(true);
                            }
                        }
                        return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
                    }
                };
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new TestTool(
                        PermissionDecision.allow("allowed"),
                        Mono.<ToolResultBlock>never()
                                .doOnSubscribe(ignored -> toolStarted.countDown())
                                .doOnCancel(toolCancelled::countDown)));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("system prompt")
                        .model(new FixedResponseModel(incompleteResponse()))
                        .toolkit(toolkit)
                        .stateStore(store)
                        .build();

        Disposable subscription =
                agent.call(List.of(userMsg("original question")), CONTEXT).subscribe();
        try {
            assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
            assertEquals(
                    ToolCallState.ALLOWED,
                    onlyToolUse(agent.getAgentState("u1", "session-1")).getState());
        } finally {
            subscription.dispose();
        }

        assertTrue(toolCancelled.await(5, TimeUnit.SECONDS));
        assertFalse(savedBeforeToolCancellation.get());
        assertEquals(1, savesAfterToolStarted.get());
        AgentState persisted =
                store.get("u1", "session-1", "agent_state", AgentState.class).orElseThrow();
        assertEquals(
                List.of("original question", "incomplete answer"),
                textContents(persisted.getContext()));
        assertFalse(hasToolUse(persisted));
    }

    @Test
    @DisplayName("cancelling an in-flight normal save does not start a second save")
    void cancellationDuringNormalSaveDoesNotDoubleWrite() throws Exception {
        AtomicBoolean responseProduced = new AtomicBoolean();
        AtomicInteger savesAfterResponse = new AtomicInteger();
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch saveFinished = new CountDownLatch(1);
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public long saveIfVersion(
                            String userId,
                            String sessionId,
                            String key,
                            State value,
                            long expectedVersion) {
                        int saveNumber =
                                responseProduced.get() ? savesAfterResponse.incrementAndGet() : 0;
                        if (saveNumber == 1) {
                            saveStarted.countDown();
                            awaitUninterruptibly(releaseSave);
                        }
                        try {
                            return super.saveIfVersion(
                                    userId, sessionId, key, value, expectedVersion);
                        } finally {
                            if (saveNumber == 1) {
                                saveFinished.countDown();
                            }
                        }
                    }
                };
        ReActAgent agent =
                agent(
                        new FixedResponseModel(
                                textResponse("done"), () -> responseProduced.set(true)),
                        store);

        Disposable subscription =
                agent.call(List.of(userMsg("original question")), CONTEXT).subscribe();
        try {
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertEquals(1, savesAfterResponse.get());
        } finally {
            subscription.dispose();
            releaseSave.countDown();
        }
        assertTrue(saveFinished.await(5, TimeUnit.SECONDS));
        assertEquals(1, savesAfterResponse.get());
    }

    @Test
    @DisplayName("acting failures do not persist tool calls without results")
    void actingFailureDropsUncommittedToolCall() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("system prompt")
                        .model(new FixedResponseModel(incompleteResponse()))
                        .middleware(new FailingActingMiddleware())
                        .stateStore(store)
                        .build();

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of(userMsg("original question")), CONTEXT).block());

        assertEquals("acting failed", thrown.getMessage());
        AgentState persisted =
                store.get("u1", "session-1", "agent_state", AgentState.class).orElseThrow();
        assertEquals(
                List.of("original question", "incomplete answer"),
                textContents(persisted.getContext()));
        assertFalse(hasToolUse(persisted));
    }

    @ParameterizedTest(name = "permissionAsking={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("post-acting failures remove ALLOWED calls but preserve ASKING calls")
    void postActingFailureKeepsOnlyResumableToolCalls(boolean permissionAsking) {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        Toolkit toolkit = new Toolkit();
        PermissionDecision decision =
                permissionAsking
                        ? PermissionDecision.ask("confirmation required")
                        : PermissionDecision.allow("allowed");
        toolkit.registerAgentTool(new TestTool(decision, Mono.just(ToolResultBlock.text("done"))));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("system prompt")
                        .model(new FixedResponseModel(incompleteResponse()))
                        .toolkit(toolkit)
                        .middleware(new FailAfterActingMiddleware())
                        .stateStore(store)
                        .build();

        assertThrows(
                IllegalStateException.class,
                () -> agent.call(List.of(userMsg("original question")), CONTEXT).block());

        AgentState persisted =
                store.get("u1", "session-1", "agent_state", AgentState.class).orElseThrow();
        if (permissionAsking) {
            assertEquals(ToolCallState.ASKING, onlyToolUse(persisted).getState());
        } else {
            assertFalse(hasToolUse(persisted));
        }
    }

    private static ReActAgent agent(ChatModelBase model, InMemoryAgentStateStore store) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("system prompt")
                .model(model)
                .stateStore(store)
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static ChatResponse incompleteResponse() {
        return ChatResponse.builder()
                .content(
                        List.of(
                                TextBlock.builder().text("incomplete answer").build(),
                                ToolUseBlock.builder()
                                        .id("incomplete-call")
                                        .name("unfinished_tool")
                                        .input(Map.of("value", "partial"))
                                        .build()))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static List<String> textContents(List<Msg> messages) {
        List<String> result = new ArrayList<>();
        for (Msg message : messages) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    result.add(textBlock.getText());
                }
            }
        }
        return result;
    }

    private static boolean hasToolUse(AgentState state) {
        return state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .findAny()
                .isPresent();
    }

    private static ToolUseBlock onlyToolUse(AgentState state) {
        List<ToolUseBlock> toolUses =
                state.getContext().stream()
                        .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                        .toList();
        assertEquals(1, toolUses.size());
        return toolUses.get(0);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class AlwaysFailingModel extends ChatModelBase {
        private final RuntimeException failure;

        private AlwaysFailingModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String getModelName() {
            return "always-failing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.concat(Flux.just(incompleteResponse()), Flux.error(failure));
        }
    }

    private static final class FailOnceThenCaptureModel extends ChatModelBase {
        private final List<List<Msg>> calls = new ArrayList<>();

        @Override
        public String getModelName() {
            return "fail-once-then-capture";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            if (calls.size() == 1) {
                return Flux.concat(
                        Flux.just(incompleteResponse()),
                        Flux.error(new RuntimeException("model stream failed")));
            }
            return Flux.just(textResponse("recovered"));
        }

        private List<List<Msg>> calls() {
            return calls;
        }
    }

    private static final class CapturingModel extends ChatModelBase {
        private final List<List<Msg>> calls = new ArrayList<>();

        @Override
        public String getModelName() {
            return "capturing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            return Flux.just(textResponse("recovered"));
        }

        private List<List<Msg>> calls() {
            return calls;
        }
    }

    private static final class NeverCompletingModel extends ChatModelBase {
        private final CountDownLatch responseEmitted;
        private final boolean nativeStructuredOutput;

        private NeverCompletingModel(
                CountDownLatch responseEmitted, boolean nativeStructuredOutput) {
            this.responseEmitted = responseEmitted;
            this.nativeStructuredOutput = nativeStructuredOutput;
        }

        @Override
        public String getModelName() {
            return "never-completing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(incompleteResponse())
                    .doOnComplete(responseEmitted::countDown)
                    .concatWith(Flux.never());
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return nativeStructuredOutput;
        }
    }

    private static final class FixedResponseModel extends ChatModelBase {
        private final ChatResponse response;
        private final Runnable beforeStream;

        private FixedResponseModel(ChatResponse response) {
            this(response, () -> {});
        }

        private FixedResponseModel(ChatResponse response, Runnable beforeStream) {
            this.response = response;
            this.beforeStream = beforeStream;
        }

        @Override
        public String getModelName() {
            return "fixed-response";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            beforeStream.run();
            return Flux.just(response);
        }
    }

    private static final class FailingActingMiddleware implements MiddlewareBase {
        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.error(new IllegalStateException("acting failed"));
        }
    }

    private static final class FailAfterActingMiddleware implements MiddlewareBase {
        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            return next.apply(input)
                    .concatWith(Flux.error(new IllegalStateException("post-acting failed")));
        }
    }

    private static final class TestTool extends ToolBase {
        private final PermissionDecision decision;
        private final Mono<ToolResultBlock> result;

        private TestTool(PermissionDecision decision, Mono<ToolResultBlock> result) {
            super(
                    "unfinished_tool",
                    "test tool",
                    Map.of("type", "object", "properties", Map.of()),
                    true,
                    true,
                    false,
                    null,
                    false,
                    false);
            this.decision = decision;
            this.result = result;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState context) {
            return Mono.just(decision);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return result;
        }
    }

    private record StructuredReply(String answer) {}
}
