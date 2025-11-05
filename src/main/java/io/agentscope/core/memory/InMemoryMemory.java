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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.StateModuleBase;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of Memory with state persistence support.
 *
 * This implementation stores messages in memory using thread-safe collections
 * and provides state serialization/deserialization for session management.
 *
 * Uses Jackson ObjectMapper for complete serialization of all message types,
 * using JSON format for serialization.
 */
public class InMemoryMemory extends StateModuleBase implements Memory {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Constructor that registers the messages list for state management.
     */
    public InMemoryMemory() {
        super();
        // Register messages for custom serialization
        registerState("messages", this::serializeMessages, this::deserializeMessages);
    }

    /**
     * Adds a message to the in-memory message list.
     *
     * <p>This method is thread-safe due to the use of CopyOnWriteArrayList.
     *
     * @param message The message to add to memory
     */
    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    /**
     * Retrieves all non-null messages from memory.
     *
     * <p>This method filters out any null entries and returns a new list copy. Thread-safe for
     * concurrent reads.
     *
     * @return A new list containing all non-null messages
     */
    @Override
    public List<Msg> getMessages() {
        return messages.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Deletes a message at the specified index.
     *
     * <p>This method is thread-safe due to the use of CopyOnWriteArrayList. If the index is out
     * of bounds, this operation is a no-op (no exception thrown).
     *
     * @param index The index of the message to delete (0-based)
     */
    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    /**
     * Clears all messages from memory.
     *
     * <p>This method is thread-safe due to the use of CopyOnWriteArrayList.
     */
    @Override
    public void clear() {
        messages.clear();
    }

    /**
     * Serialize messages to a JSON-compatible format using Jackson.
     * This ensures all ContentBlock types (including ToolUseBlock, ToolResultBlock, etc.)
     * are properly serialized with their complete data.
     */
    private Object serializeMessages(Object messages) {
        if (messages instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Msg> msgList = (List<Msg>) messages;
            return msgList.stream()
                    .map(
                            msg -> {
                                try {
                                    // Convert Msg to Map using ObjectMapper to handle all
                                    // ContentBlock types
                                    return OBJECT_MAPPER.convertValue(
                                            msg, new TypeReference<Map<String, Object>>() {});
                                } catch (Exception e) {
                                    throw new RuntimeException(
                                            "Failed to serialize message: " + msg, e);
                                }
                            })
                    .collect(Collectors.toList());
        }
        return messages;
    }

    /**
     * Deserialize messages from a JSON-compatible format using Jackson.
     * This properly reconstructs all ContentBlock types from their JSON representations.
     */
    private Object deserializeMessages(Object data) {
        if (data instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgDataList = (List<Map<String, Object>>) data;

            List<Msg> restoredMessages =
                    msgDataList.stream()
                            .map(
                                    msgData -> {
                                        try {
                                            // Convert Map back to Msg using ObjectMapper
                                            return OBJECT_MAPPER.convertValue(msgData, Msg.class);
                                        } catch (Exception e) {
                                            throw new RuntimeException(
                                                    "Failed to deserialize message: " + msgData, e);
                                        }
                                    })
                            .toList();

            // Replace current messages with restored ones
            messages.clear();
            messages.addAll(restoredMessages);

            return messages;
        }
        return data;
    }
}
