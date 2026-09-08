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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class ReActAgentToolResultReplayTest {
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("alice").sessionId("thread").build();

    private static final class CapturingModel extends ChatModelBase {
        final List<List<Msg>> inputs = new ArrayList<>();

        @Override
        public String getModelName() {
            return "replay-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private static ReActAgent agent(CapturingModel model, boolean recovery) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external")
                        .description("Execute outside the runtime")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build());
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .enablePendingToolRecovery(recovery)
                .build();
    }

    private static Msg calls(String... ids) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        java.util.Arrays.stream(ids)
                                .map(
                                        id ->
                                                (ContentBlock)
                                                        ToolUseBlock.builder()
                                                                .id(id)
                                                                .name("external")
                                                                .input(Map.of())
                                                                .build())
                                .toList())
                .metadata(Map.of(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID, "reply"))
                .build();
    }

    private static Msg result(String id) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(id)
                                .name("external")
                                .output(TextBlock.builder().text("result-" + id).build())
                                .state(ToolResultState.SUCCESS)
                                .build())
                .build();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static void seed(ReActAgent agent, String... pending) {
        List<Msg> context = agent.getAgentState(CONTEXT).contextMutable();
        context.addAll(List.of(calls("old"), result("old")));
        if (pending.length > 0) {
            context.add(calls(pending));
        }
    }

    private static List<ToolResultBlock> results(List<Msg> msgs) {
        return msgs.stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
    }

    @Test
    void consumedAndCurrentResultsResumeWithoutDuplicatingHistoryOrEvents() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, false)) {
            seed(agent, "pending");
            List<AgentEvent> events =
                    agent.streamEvents(
                                    List.of(result("old"), result("pending"), user("continue")),
                                    CONTEXT)
                            .collectList()
                            .block();
            assertEquals(
                    List.of("old", "pending"),
                    results(model.inputs.get(0)).stream().map(ToolResultBlock::getId).toList());
            ExternalExecutionResultEvent event =
                    events.stream()
                            .filter(ExternalExecutionResultEvent.class::isInstance)
                            .map(ExternalExecutionResultEvent.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertEquals("reply", event.getReplyId());
            assertEquals(
                    List.of("pending"),
                    event.getToolResults().stream().map(ToolResultBlock::getId).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consumedResultAndFreshInstructionRespectRecoverySwitch(boolean recovery) {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, recovery)) {
            seed(agent, "pending");
            List<Msg> input = List.of(result("old"), user("new instruction"));
            if (recovery) {
                assertEquals("done", agent.call(input, CONTEXT).block().getTextContent());
                List<ToolResultBlock> results = results(model.inputs.get(0));
                assertEquals(
                        List.of("old", "pending"),
                        results.stream().map(ToolResultBlock::getId).toList());
                assertEquals(ToolResultState.ERROR, results.get(1).getState());
                assertTrue(
                        model.inputs.get(0).stream()
                                .anyMatch(m -> "new instruction".equals(m.getTextContent())));
            } else {
                IllegalStateException error =
                        assertThrows(
                                IllegalStateException.class,
                                () -> agent.call(input, CONTEXT).block());
                assertTrue(error.getMessage().contains("enablePendingToolRecovery"));
                assertEquals(3, agent.getAgentState(CONTEXT).getContext().size());
                assertTrue(model.inputs.isEmpty());
            }
        }
    }

    @Test
    void repeatedConsumedResultsDoNotPolluteContextWhenNoToolsArePending() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent);
            for (int i = 0; i < 2; i++) {
                agent.call(List.of(result("old"), user("turn-" + i)), CONTEXT).block();
            }
            assertEquals(1, results(agent.getAgentState(CONTEXT).getContext()).size());
            assertEquals(2, model.inputs.size());
        }
    }

    @Test
    void cleaningMixedMessagePreservesRemainingContentAndIdentity() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent, "pending");
            Msg mixed =
                    Msg.builder()
                            .id("mixed")
                            .name("alice")
                            .role(MsgRole.TOOL)
                            .metadata(Map.of("trace", "value"))
                            .content(
                                    List.of(
                                            result("old")
                                                    .getFirstContentBlock(ToolResultBlock.class),
                                            TextBlock.builder().text("new instruction").build()))
                            .build();
            agent.call(List.of(mixed), CONTEXT).block();
            Msg cleaned =
                    agent.getAgentState(CONTEXT).getContext().stream()
                            .filter(m -> "mixed".equals(m.getId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("new instruction", cleaned.getTextContent());
            assertEquals(mixed.getMetadata(), cleaned.getMetadata());
            assertEquals(mixed.getTimestamp(), cleaned.getTimestamp());
            assertEquals(mixed.getRole(), cleaned.getRole());
            assertEquals(mixed.getName(), cleaned.getName());
            assertFalse(cleaned.hasContentBlocks(ToolResultBlock.class));
            assertEquals(2, mixed.getContent().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unknownIdsAreRejectedEvenWithoutPendingTools(boolean pending) {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent, pending ? new String[] {"pending"} : new String[] {});
            List<Msg> before = agent.getAgentState(CONTEXT).getContext();
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    agent.call(
                                                    List.of(result("unknown"), user("continue")),
                                                    CONTEXT)
                                            .block());
            assertTrue(error.getMessage().contains("Invalid tool result IDs"));
            assertEquals(before, agent.getAgentState(CONTEXT).getContext());
            assertTrue(model.inputs.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"old", "pending"})
    void duplicateIdsAreRejectedBeforeConsumedResultsAreRemoved(String id) {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent, "pending");
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    agent.call(
                                                    List.of(
                                                            result(id),
                                                            result(id),
                                                            user("continue")),
                                                    CONTEXT)
                                            .block());
            assertTrue(error.getMessage().contains("Duplicate tool result ID"));
            assertEquals(3, agent.getAgentState(CONTEXT).getContext().size());
        }
    }

    @Test
    void partialResultsAndTextStillFailAfterRemovingConsumedResults() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent, "one", "two");
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    agent.call(
                                                    List.of(
                                                            result("old"),
                                                            result("one"),
                                                            user("continue")),
                                                    CONTEXT)
                                            .block());
            assertTrue(error.getMessage().contains("partial tool results"));
            assertEquals(3, agent.getAgentState(CONTEXT).getContext().size());
        }
    }

    @Test
    void consumedResultsAloneDoNotExecutePendingToolsOrCallModel() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent, "pending");
            assertThrows(
                    IllegalStateException.class,
                    () -> agent.call(List.of(result("old")), CONTEXT).block());
            assertEquals(3, agent.getAgentState(CONTEXT).getContext().size());
            assertTrue(model.inputs.isEmpty());
        }
    }

    @Test
    void statelessCompleteHistoryStillReachesModel() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, false)) {
            List<Msg> history =
                    List.of(user("first"), calls("client"), result("client"), user("next"));
            agent.call(history, CONTEXT).block();
            assertTrue(model.inputs.get(0).containsAll(history));
        }
    }

    @Test
    void replaySelectionUsesReloadedStateAndDoesNotReplayRegeneratePrompt() {
        CapturingModel model = new CapturingModel();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        try (ReActAgent agent =
                ReActAgent.builder().name("asst").model(model).stateStore(store).build()) {
            // The cached state is deliberately obsolete when the invocation starts.
            agent.getAgentState(CONTEXT).contextMutable().add(calls("obsolete"));
            AgentState authoritative = AgentState.builder().sessionId("thread").build();
            authoritative.contextMutable().add(calls("pending"));
            store.save("alice", "thread", "agent_state", authoritative);
            RuntimeContext replay =
                    RuntimeContext.builder(CONTEXT)
                            .put(RuntimeContext.REPLAYED_INPUT, true)
                            .build();
            List<Msg> transcript =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .textContent("client system history")
                                    .build(),
                            user("old prompt"),
                            calls("pending"),
                            result("pending"),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .textContent("client split turn")
                                    .build());
            agent.call(transcript, replay).block();
            assertEquals(
                    List.of("pending"),
                    results(model.inputs.get(0)).stream().map(ToolResultBlock::getId).toList());
            assertFalse(
                    model.inputs.get(0).stream()
                            .anyMatch(
                                    m ->
                                            "old prompt".equals(m.getTextContent())
                                                    || "client system history"
                                                            .equals(m.getTextContent())));
        }
    }

    @Test
    void replayDoesNotBypassAskingToolWithAResultAndFreshText() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent);
            agent.getAgentState(CONTEXT)
                    .contextMutable()
                    .add(
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(
                                            ToolUseBlock.builder()
                                                    .id("approval")
                                                    .name("external")
                                                    .input(Map.of())
                                                    .state(ToolCallState.ASKING)
                                                    .build())
                                    .build());
            RuntimeContext replay =
                    RuntimeContext.builder(CONTEXT)
                            .put(RuntimeContext.REPLAYED_INPUT, true)
                            .build();
            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    agent.call(
                                                    List.of(
                                                            calls("approval"),
                                                            result("approval"),
                                                            user("continue")),
                                                    replay)
                                            .block());
            assertTrue(error.getMessage().contains("ASKING"));
            assertEquals(1, results(agent.getAgentState(CONTEXT).getContext()).size());
            assertTrue(model.inputs.isEmpty());
        }
    }

    @Test
    void storedOrphanResultDoesNotMakeAnUnknownIdValid() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            agent.getAgentState(CONTEXT).contextMutable().add(result("unknown"));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            agent.call(List.of(result("unknown"), user("continue")), CONTEXT)
                                    .block());
            assertTrue(model.inputs.isEmpty());
        }
    }

    @Test
    void existingSessionCannotValidateUnknownResultWithAnInputToolCall() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            agent.call(
                                            List.of(
                                                    calls("unknown"),
                                                    result("unknown"),
                                                    user("continue")),
                                            CONTEXT)
                                    .block());
            assertEquals(2, agent.getAgentState(CONTEXT).getContext().size());
        }
    }

    @Test
    void initialTranscriptRequiresToolCallBeforeResult() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, false)) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            agent.call(
                                            List.of(
                                                    result("unknown"),
                                                    calls("unknown"),
                                                    user("continue")),
                                            CONTEXT)
                                    .block());
            assertTrue(agent.getAgentState(CONTEXT).getContext().isEmpty());
        }
    }

    @Test
    void consumedResultsFromAnotherUserAreNotAccepted() {
        CapturingModel model = new CapturingModel();
        try (ReActAgent agent = agent(model, true)) {
            seed(agent);
            RuntimeContext otherUser =
                    RuntimeContext.builder()
                            .userId("bob")
                            .sessionId("thread")
                            .put(RuntimeContext.REPLAYED_INPUT, true)
                            .build();
            agent.getAgentState(otherUser).contextMutable().add(calls("bob-pending"));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            agent.call(
                                            List.of(
                                                    calls("client"),
                                                    result("old"),
                                                    user("continue")),
                                            otherUser)
                                    .block());
            assertEquals(1, agent.getAgentState(otherUser).getContext().size());
            assertEquals(2, agent.getAgentState(CONTEXT).getContext().size());
            assertTrue(model.inputs.isEmpty());
        }
    }
}
