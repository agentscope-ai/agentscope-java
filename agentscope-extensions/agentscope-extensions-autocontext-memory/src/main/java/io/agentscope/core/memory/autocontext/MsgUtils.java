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

public class MsgUtils {

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    private static final TypeReference<List<String>> MSG_STRING_LIST_TYPE =
            new TypeReference<>() {};
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
     * from Map<String,List<Msg>> to Map<String,List<Map<String, Object>>
     * @param object
     * @return
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
     * Serialize messages to a JSON-compatible format using Jackson from List<Msg> to List<Map<String, Object>>
     * This ensures all ContentBlock types (including ToolUseBlock, ToolResultBlock, etc.)
     * are properly serialized with their complete data.
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
     * Deserialize messages from a JSON-compatible format using Jackson from List<Map<String, Object>> to List<Msg>
     * This properly reconstructs all ContentBlock types from their JSON representations.
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

            // Replace current messages with restored ones
            return new ArrayList(restoredMessages);
        }
        return data;
    }

    /**
     * Deserialize messages from a JSON-compatible format using Jackson from  Map<String,List<Map<String, Object>>> to Map<String,List<Msg>>
     * This properly reconstructs all ContentBlock types from their JSON representations.
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
     * Replace messages in rawMessages from startIndex to endIndex (inclusive) with newMsg.
     *
     * @param rawMessages the list of messages to modify
     * @param startIndex  the start index (inclusive)
     * @param endIndex    the end index (inclusive)
     * @param newMsg      the new message to replace the range with
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
