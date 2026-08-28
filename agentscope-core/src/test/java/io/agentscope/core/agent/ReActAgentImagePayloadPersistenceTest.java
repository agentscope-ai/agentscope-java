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
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ImagePayloadTransformer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConcurrentSessionModificationException;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@DisplayName("ReActAgent image payload persistence")
class ReActAgentImagePayloadPersistenceTest {

    private static final String IMAGE_DATA = "aW1hZ2UtcGF5bG9hZA==";
    private static final String OTHER_IMAGE_DATA = "b3RoZXItaW1hZ2U=";
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("u1").sessionId("session-1").build();

    @Test
    @DisplayName("offloading is opt-in so rolling upgrades keep the legacy state format")
    void offloadingIsDisabledByDefault() {
        CountingStore store = new CountingStore();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .sysPrompt("system prompt")
                        .model(new CapturingModel())
                        .stateStore(store)
                        .build();

        agent.call(List.of(imageMessage()), CONTEXT).block();

        assertFalse(agent.isImagePayloadOffloadingEnabled());
        assertTrue(storedState(store).toJson().contains(IMAGE_DATA));
        assertEquals(0, store.payloadReads());
    }

    @Test
    @DisplayName("history stays lean while the model receives restored image payloads")
    void historyStaysLeanAndModelReceivesHydratedImage() {
        CountingStore store = new CountingStore();
        CapturingModel firstModel = new CapturingModel();
        ReActAgent firstAgent = agent(firstModel, store);

        firstAgent.call(List.of(imageMessage()), CONTEXT).block();

        AgentState persisted = storedState(store);
        assertFalse(persisted.toJson().contains(IMAGE_DATA));
        assertEquals(IMAGE_DATA, firstImageData(firstModel.calls().get(0)));
        assertTrue(
                firstImageData(firstAgent.getAgentState("u1", "session-1").getContext())
                        .startsWith("__agentscope_image_ref__:"));

        store.resetPayloadReads();
        CapturingModel resumedModel = new CapturingModel();
        ReActAgent resumedAgent = agent(resumedModel, store);

        AgentState leanHistory = resumedAgent.getAgentState("u1", "session-1");
        assertTrue(
                firstImageData(leanHistory.getContext()).startsWith("__agentscope_image_ref__:"));
        assertEquals(0, store.payloadReads());

        resumedAgent.call(List.of(textMessage("continue")), CONTEXT).block();

        assertEquals(IMAGE_DATA, firstImageData(resumedModel.calls().get(0)));
        assertTrue(store.payloadReads() >= 1);
        assertTrue(
                firstImageData(resumedAgent.getAgentState("u1", "session-1").getContext())
                        .startsWith("__agentscope_image_ref__:"));
        assertFalse(storedState(store).toJson().contains(IMAGE_DATA));
    }

    @Test
    @DisplayName("missing payload is deferred until model-bound hydration")
    void missingPayloadFailsOnlyWhenModelInputIsBuilt() {
        CountingStore store = new CountingStore();
        ReActAgent writer = agent(new CapturingModel(), store);
        writer.call(List.of(imageMessage()), CONTEXT).block();

        AgentState lean = storedState(store);
        String payloadId =
                ImagePayloadTransformer.referencedPayloadIds(lean.getContext()).iterator().next();
        store.delete("u1", "session-1", AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX + payloadId);
        store.resetPayloadReads();

        ReActAgent resumed = agent(new CapturingModel(), store);
        assertTrue(
                firstImageData(resumed.getAgentState("u1", "session-1").getContext())
                        .startsWith("__agentscope_image_ref__:"));
        assertEquals(0, store.payloadReads());

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> resumed.call(List.of(textMessage("continue")), CONTEXT).block());

        assertTrue(failure.getMessage().contains("Missing image payload"));
        assertTrue(store.payloadReads() >= 1);
    }

    @Test
    @DisplayName("summary model receives restored image payloads")
    void summaryModelReceivesHydratedImage() {
        CountingStore store = new CountingStore();
        store.saveAgentState(
                "u1",
                "session-1",
                AgentState.builder()
                        .userId("u1")
                        .sessionId("session-1")
                        .context(List.of(imageMessage()))
                        .build());
        store.resetPayloadReads();
        CapturingModel summaryModel = new CapturingModel(true);
        ReActAgent agent = agent(summaryModel, store, 1);

        agent.call(List.of(textMessage("summarize")), CONTEXT).block();

        assertEquals(IMAGE_DATA, firstImageData(summaryModel.calls().get(1)));
        assertFalse(storedState(store).toJson().contains(IMAGE_DATA));
        assertTrue(
                firstImageData(agent.getAgentState("u1", "session-1").getContext())
                        .startsWith("__agentscope_image_ref__:"));
        assertEquals(
                1,
                store.payloadReads(),
                "reasoning and summary should share one call-scoped payload read");
    }

    @Test
    @DisplayName("legacy inline state migrates on the next agent save")
    void legacyInlineStateMigratesOnNextSave() {
        CountingStore store = new CountingStore();
        store.save(
                "u1",
                "session-1",
                AgentStateStore.AGENT_STATE_KEY,
                AgentState.builder()
                        .userId("u1")
                        .sessionId("session-1")
                        .context(List.of(imageMessage()))
                        .build());
        CapturingModel model = new CapturingModel();
        ReActAgent agent = agent(model, store);

        agent.call(List.of(textMessage("continue")), CONTEXT).block();

        assertEquals(IMAGE_DATA, firstImageData(model.calls().get(0)));
        assertFalse(storedState(store).toJson().contains(IMAGE_DATA));
    }

    @Test
    @DisplayName("overwrite conflict path still persists lightweight state")
    void overwriteConflictKeepsStateLightweight() {
        CountingStore store = new CountingStore();
        ReActAgent first = agent(new CapturingModel(), store, 10, ConflictPolicy.OVERWRITE);
        ReActAgent stale = agent(new CapturingModel(), store, 10, ConflictPolicy.OVERWRITE);
        AgentState firstState = first.getAgentState("u1", "session-1");
        AgentState staleState = stale.getAgentState("u1", "session-1");
        firstState.contextMutable().add(imageMessage(IMAGE_DATA));
        staleState.contextMutable().add(imageMessage(OTHER_IMAGE_DATA));

        first.saveAgentState("u1", "session-1");
        stale.saveAgentState("u1", "session-1");

        assertFalse(storedState(store).toJson().contains(OTHER_IMAGE_DATA));
        assertEquals(
                List.of(OTHER_IMAGE_DATA),
                imageData(store.getAgentState("u1", "session-1").orElseThrow().getContext()));
    }

    @Test
    @DisplayName("fail conflict path leaves the winning lightweight state untouched")
    void failConflictKeepsWinningStateLightweight() {
        CountingStore store = new CountingStore();
        ReActAgent first = agent(new CapturingModel(), store, 10, ConflictPolicy.FAIL);
        ReActAgent stale = agent(new CapturingModel(), store, 10, ConflictPolicy.FAIL);
        first.getAgentState("u1", "session-1").contextMutable().add(imageMessage(IMAGE_DATA));
        stale.getAgentState("u1", "session-1").contextMutable().add(imageMessage(OTHER_IMAGE_DATA));

        first.saveAgentState("u1", "session-1");

        assertThrows(
                ConcurrentSessionModificationException.class,
                () -> stale.saveAgentState("u1", "session-1"));
        assertFalse(storedState(store).toJson().contains(IMAGE_DATA));
        assertEquals(
                List.of(IMAGE_DATA),
                imageData(store.getAgentState("u1", "session-1").orElseThrow().getContext()));
    }

    @Test
    @DisplayName("append-merge conflict preserves both image turns as references")
    void appendMergeConflictPreservesBothImageTurns() {
        CountingStore store = new CountingStore();
        ReActAgent first = agent(new CapturingModel(), store, 10, ConflictPolicy.APPEND_MERGE);
        ReActAgent stale = agent(new CapturingModel(), store, 10, ConflictPolicy.APPEND_MERGE);
        first.getAgentState("u1", "session-1").contextMutable().add(imageMessage(IMAGE_DATA));
        stale.getAgentState("u1", "session-1").contextMutable().add(imageMessage(OTHER_IMAGE_DATA));

        first.saveAgentState("u1", "session-1");
        stale.saveAgentState("u1", "session-1");

        AgentState raw = storedState(store);
        assertFalse(raw.toJson().contains(IMAGE_DATA));
        assertFalse(raw.toJson().contains(OTHER_IMAGE_DATA));
        assertEquals(
                List.of(IMAGE_DATA, OTHER_IMAGE_DATA),
                imageData(store.getAgentState("u1", "session-1").orElseThrow().getContext()));
    }

    private static ReActAgent agent(ChatModelBase model, AgentStateStore store) {
        return agent(model, store, 10);
    }

    private static ReActAgent agent(ChatModelBase model, AgentStateStore store, int maxIters) {
        return agent(model, store, maxIters, ConflictPolicy.OVERWRITE);
    }

    private static ReActAgent agent(
            ChatModelBase model,
            AgentStateStore store,
            int maxIters,
            ConflictPolicy conflictPolicy) {
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("system prompt")
                .model(model)
                .stateStore(store)
                .imagePayloadOffloadingEnabled(true)
                .maxIters(maxIters)
                .conflictPolicy(conflictPolicy)
                .build();
    }

    private static Msg imageMessage() {
        return imageMessage(IMAGE_DATA);
    }

    private static Msg imageMessage(String data) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(ImageBlock.builder().source(new Base64Source("image/png", data)).build())
                .build();
    }

    private static Msg textMessage(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static AgentState storedState(AgentStateStore store) {
        return store.get("u1", "session-1", AgentStateStore.AGENT_STATE_KEY, AgentState.class)
                .orElseThrow();
    }

    private static String firstImageData(List<Msg> messages) {
        return imageData(messages).stream().findFirst().orElseThrow();
    }

    private static List<String> imageData(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ImageBlock.class::isInstance)
                .map(ImageBlock.class::cast)
                .map(ImageBlock::getSource)
                .filter(Base64Source.class::isInstance)
                .map(Base64Source.class::cast)
                .map(Base64Source::getData)
                .toList();
    }

    private static final class CapturingModel extends ChatModelBase {
        private final List<List<Msg>> calls = new ArrayList<>();
        private final boolean forceSummary;

        private CapturingModel() {
            this(false);
        }

        private CapturingModel(boolean forceSummary) {
            this.forceSummary = forceSummary;
        }

        @Override
        public String getModelName() {
            return "capturing-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            if (forceSummary && calls.size() == 1) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id("missing-call")
                                                        .name("missing-tool")
                                                        .input(Map.of())
                                                        .build()))
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }

        private List<List<Msg>> calls() {
            return calls;
        }
    }

    private static final class CountingStore extends InMemoryAgentStateStore {
        private int payloadReads;

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            if (key.startsWith(AgentStateStore.IMAGE_PAYLOAD_KEY_PREFIX)) {
                payloadReads++;
            }
            return super.get(userId, sessionId, key, type);
        }

        private int payloadReads() {
            return payloadReads;
        }

        private void resetPayloadReads() {
            payloadReads = 0;
        }
    }
}
