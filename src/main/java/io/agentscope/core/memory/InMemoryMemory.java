/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.memory;

import io.agentscope.core.message.ContentBlockUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.StateModuleBase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of Memory with state persistence support.
 *
 * This implementation stores messages in memory using thread-safe collections
 * and provides state serialization/deserialization for session management.
 */
public class InMemoryMemory extends StateModuleBase implements Memory {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    /**
     * Constructor that registers the messages list for state management.
     */
    public InMemoryMemory() {
        super();
        // Register messages for custom serialization
        registerState("messages", this::serializeMessages, this::deserializeMessages);
    }

    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    @Override
    public List<Msg> getMessages() {
        return messages.stream().filter(msg -> msg != null).collect(Collectors.toList());
    }

    @Override
    public void clear() {
        messages.clear();
    }

    /**
     * Serialize messages to a JSON-compatible format.
     */
    private Object serializeMessages(Object messages) {
        if (messages instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Msg> msgList = (List<Msg>) messages;
            return msgList.stream().map(this::serializeMessage).collect(Collectors.toList());
        }
        return messages;
    }

    /**
     * Deserialize messages from a JSON-compatible format.
     */
    private Object deserializeMessages(Object data) {
        if (data instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgDataList = (List<Map<String, Object>>) data;

            List<Msg> restoredMessages =
                    msgDataList.stream().map(this::deserializeMessage).collect(Collectors.toList());

            // Replace current messages with restored ones
            messages.clear();
            messages.addAll(restoredMessages);

            return messages;
        }
        return data;
    }

    /**
     * Serialize a single message to a map.
     */
    private Map<String, Object> serializeMessage(Msg msg) {
        return Map.of(
                "id", msg.getId() != null ? msg.getId() : "",
                "name", msg.getName() != null ? msg.getName() : "",
                "role", msg.getRole().name(),
                "content", ContentBlockUtils.extractTextContent(msg.getContent()),
                "contentType", msg.getContent().getType().name());
    }

    /**
     * Deserialize a single message from a map.
     */
    private Msg deserializeMessage(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "");
        String id = (String) data.getOrDefault("id", "");
        String roleStr = (String) data.get("role");
        String content = (String) data.getOrDefault("content", "");

        return Msg.builder()
                .id(id)
                .name(name)
                .role(io.agentscope.core.message.MsgRole.valueOf(roleStr))
                .content(io.agentscope.core.message.TextBlock.builder().text(content).build())
                .build();
    }
}
