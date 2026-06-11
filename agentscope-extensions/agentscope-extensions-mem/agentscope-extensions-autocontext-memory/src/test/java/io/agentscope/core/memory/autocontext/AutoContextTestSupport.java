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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

final class AutoContextTestSupport {

    private AutoContextTestSupport() {}

    static Msg userMessage(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    static Msg assistantMessage(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    static Model noopModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "noop";
            }
        };
    }

    static Model recordingModel(String responseText, AtomicReference<String> threadNameRef) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                threadNameRef.set(Thread.currentThread().getName());
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text(responseText).build()))
                                .build());
            }

            @Override
            public String getModelName() {
                return "recording";
            }
        };
    }

    static AgentState runtimeState(String sessionId, String userId, List<Msg> messages) {
        return AgentState.builder().sessionId(sessionId).userId(userId).context(messages).build();
    }

    static RuntimeContext runtimeContext(AgentState state) {
        return RuntimeContext.builder()
                .sessionId(state.getSessionId())
                .userId(state.getUserId())
                .agentState(state)
                .build();
    }

    static AgentStateStore inMemoryStore() {
        return new InMemoryAgentStateStore();
    }

    private static final class InMemoryAgentStateStore implements AgentStateStore {

        private final Map<String, Map<String, Object>> data = new ConcurrentHashMap<>();

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            slot(userId, sessionId).put(key, value);
        }

        @Override
        public void save(
                String userId, String sessionId, String key, List<? extends State> values) {
            slot(userId, sessionId).put(key, new ArrayList<>(values));
        }

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            Object value = slot(userId, sessionId).get(key);
            if (type.isInstance(value)) {
                return Optional.of(type.cast(value));
            }
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> itemType) {
            Object value = slot(userId, sessionId).get(key);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            for (Object item : list) {
                if (itemType.isInstance(item)) {
                    result.add((T) item);
                }
            }
            return result;
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return data.containsKey(slotKey(userId, sessionId));
        }

        @Override
        public void delete(String userId, String sessionId) {
            data.remove(slotKey(userId, sessionId));
        }

        @Override
        public void delete(String userId, String sessionId, String key) {
            Map<String, Object> slot = data.get(slotKey(userId, sessionId));
            if (slot == null) {
                return;
            }
            slot.remove(key);
            if (slot.isEmpty()) {
                data.remove(slotKey(userId, sessionId));
            }
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            String prefix = slotKey(userId, "");
            Set<String> sessionIds = new java.util.HashSet<>();
            for (String key : data.keySet()) {
                if (key.startsWith(prefix)) {
                    sessionIds.add(key.substring(prefix.length()));
                }
            }
            return sessionIds;
        }

        private Map<String, Object> slot(String userId, String sessionId) {
            return data.computeIfAbsent(
                    slotKey(userId, sessionId), ignored -> new ConcurrentHashMap<>());
        }

        private String slotKey(String userId, String sessionId) {
            String uid = userId == null || userId.isBlank() ? "__anon__" : userId;
            return uid + "|" + sessionId;
        }
    }
}
