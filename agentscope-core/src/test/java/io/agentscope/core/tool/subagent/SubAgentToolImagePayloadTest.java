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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@DisplayName("SubAgentTool image payload persistence")
class SubAgentToolImagePayloadTest {

    private static final String IMAGE_DATA = "c3ViYWdlbnQtaW1hZ2U=";

    @Test
    @DisplayName("continued child session hydrates its own history instead of the parent slot")
    void continuedChildSessionHydratesItsOwnHistory() {
        String childSession = "child-session";
        RecordingStore childStore = new RecordingStore();
        childStore.saveAgentState(
                null,
                childSession,
                AgentState.builder()
                        .sessionId(childSession)
                        .context(List.of(imageMessage()))
                        .build());
        childStore.resetIoThreads();

        CapturingModel model = new CapturingModel();
        List<ReActAgent> createdAgents = new ArrayList<>();
        SubAgentProvider<ReActAgent> provider =
                () -> {
                    ReActAgent agent =
                            ReActAgent.builder()
                                    .name("child")
                                    .sysPrompt("child system")
                                    .model(model)
                                    .build();
                    createdAgents.add(agent);
                    return agent;
                };
        SubAgentTool tool =
                new SubAgentTool(
                        provider,
                        SubAgentConfig.builder()
                                .stateStore(childStore)
                                .imagePayloadOffloadingEnabled(true)
                                .forwardEvents(false)
                                .build());
        RuntimeContext parentContext =
                RuntimeContext.builder()
                        .userId("parent-user")
                        .sessionId("parent-session")
                        .put("trace", "preserved")
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name(tool.getName())
                                        .input(
                                                Map.of(
                                                        "session_id",
                                                        childSession,
                                                        "message",
                                                        "continue"))
                                        .build())
                        .input(Map.of("session_id", childSession, "message", "continue"))
                        .runtimeContext(parentContext)
                        .build();

        ToolResultBlock result;
        Scheduler caller = Schedulers.newSingle("subagent-caller");
        try {
            result = tool.callAsync(param).subscribeOn(caller).block();
        } finally {
            caller.dispose();
        }

        assertNotNull(result);
        assertFalse(childStore.ioThreads().isEmpty());
        assertTrue(
                childStore.ioThreads().stream()
                        .allMatch(name -> name.startsWith("boundedElastic-")),
                childStore.ioThreads().toString());
        assertEquals(IMAGE_DATA, firstImageData(model.calls().get(0)));
        ReActAgent executedAgent = createdAgents.get(1);
        assertEquals(
                List.of("continue", "done"),
                textContents(executedAgent.getAgentState(null, childSession).getContext()));
        AgentState raw =
                childStore
                        .get(null, childSession, AgentStateStore.AGENT_STATE_KEY, AgentState.class)
                        .orElseThrow();
        assertFalse(raw.toJson().contains(IMAGE_DATA));
    }

    private static Msg imageMessage() {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(
                        ImageBlock.builder()
                                .source(new Base64Source("image/png", IMAGE_DATA))
                                .build())
                .build();
    }

    private static String firstImageData(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ImageBlock.class::isInstance)
                .map(ImageBlock.class::cast)
                .map(ImageBlock::getSource)
                .filter(Base64Source.class::isInstance)
                .map(Base64Source.class::cast)
                .map(Base64Source::getData)
                .findFirst()
                .orElseThrow();
    }

    private static List<String> textContents(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .toList();
    }

    private static final class CapturingModel extends ChatModelBase {
        private final List<List<Msg>> calls = new ArrayList<>();

        @Override
        public String getModelName() {
            return "subagent-capturing-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                            .build());
        }

        private List<List<Msg>> calls() {
            return calls;
        }
    }

    private static final class RecordingStore extends InMemoryAgentStateStore {
        private final List<String> ioThreads = new CopyOnWriteArrayList<>();

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            ioThreads.add(Thread.currentThread().getName());
            return super.get(userId, sessionId, key, type);
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            ioThreads.add(Thread.currentThread().getName());
            return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
        }

        private List<String> ioThreads() {
            return List.copyOf(ioThreads);
        }

        private void resetIoThreads() {
            ioThreads.clear();
        }
    }
}
