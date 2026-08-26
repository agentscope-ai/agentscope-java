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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Regression tests for durable conversation state when a model stream fails. */
@DisplayName("ReActAgent failed-call persistence")
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

    private record StructuredReply(String answer) {}
}
