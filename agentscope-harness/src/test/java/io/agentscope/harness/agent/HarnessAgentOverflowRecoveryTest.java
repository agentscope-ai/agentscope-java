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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Regression coverage for context-overflow recovery on the streaming path. */
@HarnessQuiescence
class HarnessAgentOverflowRecoveryTest {

    private static final String OVERFLOW_MESSAGE =
            "HTTP transport error during streaming: HTTP request failed with status 400 | "
                    + "{\"error\":{\"code\":400,\"message\":\"request (766014 tokens) exceeds the "
                    + "available context size (128000 tokens), try increasing it\","
                    + "\"type\":\"exceed_context_size_error\",\"n_prompt_tokens\":766014,"
                    + "\"n_ctx\":128000}}";

    @TempDir Path workspace;

    @Test
    void streamEvents_recoversOnceWithoutDuplicatingContentAndPersistsState() throws Exception {
        Files.createDirectories(workspace);
        OverflowOnceModel model = new OverflowOnceModel(false);
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("overflow").build();
        Msg input = userMessage("hello");

        try (HarnessAgent agent = buildAgent(model, store)) {
            seedConversation(agent, ctx);

            List<AgentEvent> events = agent.streamEvents(List.of(input), ctx).collectList().block();

            assertNotNull(events);
            assertTrue(
                    model.callCount() >= 3,
                    "expected failed attempt + compaction + replay, saw " + model.callCount());
            assertTrue(
                    containsSummary(lastInput(model)), "the replay must use the compacted context");
            assertEquals(
                    1,
                    countMessagesWithId(lastInput(model), input),
                    "the replayed input must appear exactly once");
            assertEquals(
                    "recovered",
                    streamedText(events),
                    "partial output from the failed attempt must not be replayed");
            assertEquals(
                    1,
                    events.stream().filter(AgentStartEvent.class::isInstance).count(),
                    "the failed attempt must not leak a duplicate agent-start event");
            assertEquals(
                    1,
                    events.stream().filter(ModelCallStartEvent.class::isInstance).count(),
                    "the failed attempt must not leak a duplicate model-call-start event");

            AgentState persisted =
                    store.get(ctx.getUserId(), ctx.getSessionId(), "agent_state", AgentState.class)
                            .orElseThrow();
            assertTrue(
                    containsSummary(persisted.getContext()),
                    "the compacted state must be persisted before the replay");
        }
    }

    @Test
    void streamEvents_doesNotRecoverAfterTextWasAlreadyEmitted() throws Exception {
        Files.createDirectories(workspace);
        OverflowOnceModel model = new OverflowOnceModel(true);
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("overflow").build();

        try (HarnessAgent agent = buildAgent(model, new InMemoryAgentStateStore())) {
            seedConversation(agent, ctx);

            RuntimeException failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    agent.streamEvents(List.of(userMessage("hello")), ctx)
                                            .collectList()
                                            .block());

            assertTrue(
                    failure.getMessage().contains("exceeds the available context size"),
                    "the original overflow must propagate: " + failure.getMessage());
            assertEquals(1, model.callCount(), "no recovery may run after text was emitted");
        }
    }

    @Test
    void streamEvents_preservesOriginalOverflowWhenCompactionFails() throws Exception {
        Files.createDirectories(workspace);
        OverflowOnceModel model = new OverflowOnceModel(false);
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("overflow").build();

        try (HarnessAgent agent = buildAgent(model, new InMemoryAgentStateStore())) {
            RuntimeException failure =
                    assertThrows(
                            RuntimeException.class,
                            () -> agent.streamEvents(List.of(), ctx).collectList().block());

            assertNotNull(
                    findCauseContaining(failure, "exceeds the available context size"),
                    "the original provider error must survive recovery failure");
        }
    }

    @Test
    void streamEvents_emitsOpeningLifecycleWithoutWaitingForModelOutput() throws Exception {
        Files.createDirectories(workspace);
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("slow").build();

        try (HarnessAgent agent =
                buildAgent(new NeverCompletingModel(), new InMemoryAgentStateStore())) {
            StepVerifier.create(agent.streamEvents(List.of(userMessage("hello")), ctx))
                    .expectNextMatches(AgentStartEvent.class::isInstance)
                    .expectNextMatches(ModelCallStartEvent.class::isInstance)
                    .thenCancel()
                    .verify();
        }
    }

    @Test
    void streamEvents_preservesOpeningLifecycleBeforeNonOverflowError() throws Exception {
        Files.createDirectories(workspace);
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("failure").build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        try (HarnessAgent agent = buildAgent(new FailingModel(), new InMemoryAgentStateStore())) {
            RuntimeException failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    agent.streamEvents(List.of(userMessage("hello")), ctx)
                                            .doOnNext(events::add)
                                            .collectList()
                                            .block());

            assertTrue(failure.getMessage().contains("ordinary model failure"));
            assertEquals(2, events.size());
            assertTrue(events.get(0) instanceof AgentStartEvent);
            assertTrue(events.get(1) instanceof ModelCallStartEvent);
        }
    }

    private HarnessAgent buildAgent(Model model, InMemoryAgentStateStore store) {
        return HarnessAgent.builder()
                .name("overflow-recovery")
                .model(model)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .stateStore(store)
                .disableMemoryHooks()
                .compaction(
                        CompactionConfig.builder()
                                .flushBeforeCompact(false)
                                .offloadBeforeCompact(false)
                                .build())
                .build();
    }

    private static void seedConversation(HarnessAgent agent, RuntimeContext ctx) {
        AgentState state = agent.getDelegate().getAgentState(ctx.getUserId(), ctx.getSessionId());
        state.contextMutable()
                .add(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("previous conversation history")
                                .build());
        agent.getDelegate()
                .getStateStore()
                .save(ctx.getUserId(), ctx.getSessionId(), "agent_state", state);
    }

    private static boolean containsSummary(List<Msg> messages) {
        return messages.stream()
                .anyMatch(m -> ConversationCompactor.SUMMARY_MSG_NAME.equals(m.getName()));
    }

    private static long countMessagesWithId(List<Msg> messages, Msg msg) {
        return messages.stream().filter(m -> Objects.equals(m.getId(), msg.getId())).count();
    }

    private static List<Msg> lastInput(OverflowOnceModel model) {
        return model.inputs.get(model.inputs.size() - 1);
    }

    private static String streamedText(List<AgentEvent> events) {
        return events.stream()
                .filter(TextBlockDeltaEvent.class::isInstance)
                .map(e -> ((TextBlockDeltaEvent) e).getDelta())
                .collect(Collectors.joining());
    }

    private static Throwable findCauseContaining(Throwable error, String marker) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(marker)) {
                return current;
            }
        }
        return null;
    }

    private static Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static final class OverflowOnceModel implements Model {

        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean overflowAfterFirstText;

        private OverflowOnceModel(boolean overflowAfterFirstText) {
            this.overflowAfterFirstText = overflowAfterFirstText;
        }

        /** Emits the configured response sequence for this test model. */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            int call = calls.incrementAndGet();
            if (isSummaryPrompt(messages)) {
                return Flux.just(textResponse("summary"));
            }
            if (call == 1) {
                if (overflowAfterFirstText) {
                    return Flux.concat(
                            Flux.just(textResponse("partial")),
                            Flux.error(new RuntimeException(OVERFLOW_MESSAGE)));
                }
                return Flux.error(new RuntimeException(OVERFLOW_MESSAGE));
            }
            return Flux.just(textResponse("recovered"));
        }

        int callCount() {
            return calls.get();
        }

        /** Returns the stable model name used by the overflow recovery tests. */
        @Override
        public String getModelName() {
            return "overflow-recovery-test-model";
        }

        private static boolean isSummaryPrompt(List<Msg> messages) {
            return messages.size() == 1
                    && messages.get(0).getTextContent() != null
                    && messages.get(0).getTextContent().contains("Context Extraction Assistant");
        }
    }

    private static final class NeverCompletingModel implements Model {

        /** Keeps the model stream open without producing content. */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.never();
        }

        /** Returns the model name used by the lifecycle timing test. */
        @Override
        public String getModelName() {
            return "never-completing-test-model";
        }
    }

    private static final class FailingModel implements Model {

        /** Fails before producing content with a non-overflow error. */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new RuntimeException("ordinary model failure"));
        }

        /** Returns the model name used by the ordinary failure test. */
        @Override
        public String getModelName() {
            return "failing-test-model";
        }
    }
}
