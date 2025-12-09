/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.memory.autocontext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for message serialization and deserialization operations.
 *
 * <p>This class provides methods for converting between {@link Msg} objects and JSON-compatible
 * formats (Map structures) for state persistence. It handles polymorphic types like ContentBlock
 * and its subtypes (TextBlock, ToolUseBlock, ToolResultBlock, etc.) using Jackson ObjectMapper.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Serialization: Converts {@code List<Msg>} to {@code List<Map<String, Object>>}</li>
 *   <li>Deserialization: Converts {@code List<Map<String, Object>>} back to {@code List<Msg>}</li>
 *   <li>Map serialization: Handles {@code Map<String, List<Msg>>} for offload context storage</li>
 *   <li>Message manipulation: Provides utility methods for replacing message ranges</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * These methods are primarily used by {@link AutoContextMemory} for state persistence through
 * {@link io.agentscope.core.state.StateModuleBase}. The serialized format preserves all ContentBlock
 * type information using Jackson's polymorphic type handling.
 */
public class MsgUtils {

    /** Configured ObjectMapper for handling polymorphic message types. */
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    /** Type reference for deserializing lists of JSON strings. */
    private static final TypeReference<List<String>> MSG_STRING_LIST_TYPE =
            new TypeReference<>() {};

    /** Type reference for deserializing maps of string lists. */
    private static final TypeReference<Map<String, List<String>>> MSG_STRING_LIST_MAP_TYPE =
            new TypeReference<>() {};

    /**
     * Creates and configures an ObjectMapper for serializing/deserializing messages.
     *
     * <p>Configuration ensures proper handling of polymorphic types like ContentBlock
     * and its subtypes (TextBlock, ToolUseBlock, ToolResultBlock, etc.).
     *
     * <p>The ObjectMapper automatically recognizes @JsonTypeInfo annotations on ContentBlock
     * and will include the "type" discriminator field during serialization, which is required
     * for proper deserialization of polymorphic types.
     *
     * @return configured ObjectMapper instance
     */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Configure features for proper polymorphic type handling
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // Ensure type information is included in serialization (required for ContentBlock subtypes)
        mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, false);
        // The @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        // property = "type")
        // annotation on ContentBlock will automatically add "type" field during serialization
        return mapper;
    }

    /**
     * Serializes a map of message lists to a JSON-compatible format.
     *
     * <p>Converts {@code Map<String, List<Msg>>} to {@code Map<String, List<Map<String, Object>>>}
     * for state persistence. This is used for serializing offload context storage.
     *
     * <p>Each entry in the map is processed by converting its {@code List<Msg>} value to
     * {@code List<Map<String, Object>>} using {@link #serializeMsgList(Object)}.
     *
     * @param object the object to serialize, expected to be {@code Map<String, List<Msg>>}
     * @return the serialized map as {@code Map<String, List<Map<String, Object>>>}, or the
     *         original object if it's not a Map
     */
    public static Object serializeMsgListMap(Object object) {
        if (object instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, List<Msg>> msgListMap = (Map<String, List<Msg>>) object;

            Map<String, List<Map<String, Object>>> mapListMap = new HashMap<>(msgListMap.size());
            for (Map.Entry<String, List<Msg>> entry : msgListMap.entrySet()) {
                mapListMap.put(
                        entry.getKey(),
                        (List<Map<String, Object>>) serializeMsgList(entry.getValue()));
            }
            return mapListMap;
        }
        return object;
    }

    /**
     * Serializes a list of messages to a JSON-compatible format.
     *
     * <p>Converts {@code List<Msg>} to {@code List<Map<String, Object>>} using Jackson
     * ObjectMapper. This ensures all ContentBlock types (including ToolUseBlock, ToolResultBlock,
     * etc.) are properly serialized with their complete data and type information.
     *
     * <p>The serialization preserves polymorphic type information through Jackson's
     * {@code @JsonTypeInfo} annotations, which is required for proper deserialization.
     *
     * @param messages the object to serialize, expected to be {@code List<Msg>}
     * @return the serialized list as {@code List<Map<String, Object>>}, or the original
     *         object if it's not a List
     * @throws RuntimeException if serialization fails for any message
     */
    public static Object serializeMsgList(Object messages) {
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
     * Deserializes a list of messages from a JSON-compatible format.
     *
     * <p>Converts {@code List<Map<String, Object>>} back to {@code List<Msg>} using Jackson
     * ObjectMapper. This properly reconstructs all ContentBlock types (TextBlock, ToolUseBlock,
     * ToolResultBlock, etc.) from their JSON representations using the type discriminator
     * field included during serialization.
     *
     * <p>The deserialization relies on Jackson's polymorphic type handling to correctly
     * instantiate the appropriate ContentBlock subtypes based on the "type" field.
     *
     * @param data the data to deserialize, expected to be {@code List<Map<String, Object>>}
     * @return a new {@code ArrayList} containing the deserialized {@code List<Msg>}, or the
     *         original object if it's not a List
     * @throws RuntimeException if deserialization fails for any message
     */
    public static Object deserializeToMsgList(Object data) {
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

            // Return a new ArrayList to ensure mutability
            return new ArrayList<>(restoredMessages);
        }
        return data;
    }

    /**
     * Deserializes a map of message lists from a JSON-compatible format.
     *
     * <p>Converts {@code Map<String, List<Map<String, Object>>>} back to
     * {@code Map<String, List<Msg>>} for state restoration. This is used for deserializing
     * offload context storage.
     *
     * <p>Each entry in the map is processed by converting its {@code List<Map<String, Object>>}
     * value to {@code List<Msg>} using {@link #deserializeToMsgList(Object)}.
     *
     * @param data the data to deserialize, expected to be
     *             {@code Map<String, List<Map<String, Object>>>}
     * @return a new {@code HashMap} containing the deserialized {@code Map<String, List<Msg>>},
     *         or the original object if it's not a Map
     * @throws RuntimeException if deserialization fails for any message list
     */
    public static Object deserializeToMsgListMap(Object data) {
        if (data instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> msgDataList =
                    (Map<String, List<Map<String, Object>>>) data;
            Map<String, List<Msg>> restoredMessages = new HashMap<>();
            for (String key : msgDataList.keySet()) {
                restoredMessages.put(
                        key, (List<Msg>) MsgUtils.deserializeToMsgList(msgDataList.get(key)));
            }
            return restoredMessages;
        }
        return data;
    }

    /**
     * Replaces a range of messages in a list with a single new message.
     *
     * <p>Removes all messages from {@code startIndex} to {@code endIndex} (inclusive) and
     * inserts {@code newMsg} at the {@code startIndex} position. This is typically used
     * during context compression to replace multiple messages with a compressed summary.
     *
     * <p><b>Behavior:</b>
     * <ul>
     *   <li>If {@code rawMessages} or {@code newMsg} is null, the method returns without
     *       modification</li>
     *   <li>If indices are invalid (negative, out of bounds, or startIndex > endIndex), the
     *       method returns without modification</li>
     *   <li>If {@code endIndex} exceeds the list size, it is adjusted to the last valid index</li>
     * </ul>
     *
     * @param rawMessages the list of messages to modify (must not be null)
     * @param startIndex  the start index of the range to replace (inclusive, must be >= 0)
     * @param endIndex    the end index of the range to replace (inclusive, must be >= startIndex)
     * @param newMsg      the new message to insert at startIndex (must not be null)
     */
    public static void replaceMsg(List<Msg> rawMessages, int startIndex, int endIndex, Msg newMsg) {
        if (rawMessages == null || newMsg == null) {
            return;
        }

        int size = rawMessages.size();

        // Validate indices
        if (startIndex < 0 || endIndex < startIndex || startIndex >= size) {
            return;
        }

        // Ensure endIndex doesn't exceed list size
        int actualEndIndex = Math.min(endIndex, size - 1);

        // Remove messages from startIndex to endIndex (inclusive)
        // Remove from end to start to avoid index shifting issues
        if (actualEndIndex >= startIndex) {
            rawMessages.subList(startIndex, actualEndIndex + 1).clear();
        }

        // Insert newMsg at startIndex position
        rawMessages.add(startIndex, newMsg);
    }
}
