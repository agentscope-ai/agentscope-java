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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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
 * the session API. The serialized format preserves all ContentBlock
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

    /**
     * Check if a message is a tool-related message (tool use or tool result).
     *
     * @param msg the message to check
     * @return true if the message contains tool use or tool result blocks, or has TOOL role
     */
    public static boolean isToolMessage(Msg msg) {
        if (msg == null) {
            return false;
        }
        // Check if message has TOOL role
        if (msg.getRole() == MsgRole.TOOL) {
            return true;
        }
        // Check if message contains ToolUseBlock or ToolResultBlock
        return msg.hasContentBlocks(ToolUseBlock.class)
                || msg.hasContentBlocks(ToolResultBlock.class);
    }

    /**
     * Check if a message is a tool use message (ASSISTANT with ToolUseBlock).
     *
     * @param msg the message to check
     * @return true if the message is an ASSISTANT message containing ToolUseBlock
     */
    public static boolean isToolUseMessage(Msg msg) {
        if (msg == null) {
            return false;
        }
        return msg.getRole() == MsgRole.ASSISTANT && msg.hasContentBlocks(ToolUseBlock.class);
    }

    /**
     * Check if a message is a tool result message (TOOL role or contains ToolResultBlock).
     *
     * @param msg the message to check
     * @return true if the message is a TOOL role message or contains ToolResultBlock
     */
    public static boolean isToolResultMessage(Msg msg) {
        if (msg == null) {
            return false;
        }
        if (msg.getRole() == MsgRole.TOOL) {
            return true;
        }
        return msg.hasContentBlocks(ToolResultBlock.class);
    }

    /**
     * Check if an ASSISTANT message is a final response to the user (not a tool call).
     *
     * <p>A final assistant response should not contain ToolUseBlock, as those are intermediate
     * tool invocation messages, not the final response returned to the user.
     *
     * @param msg the message to check
     * @return true if the message is an ASSISTANT role message that does not contain tool calls
     */
    public static boolean isFinalAssistantResponse(Msg msg) {
        if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
            return false;
        }

        // Skip compressed current round messages - they are compression results, not real assistant
        // responses
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null) {
            Object compressMeta = metadata.get("_compress_meta");
            // compressMeta may be null if the key doesn't exist, but instanceof handles null safely
            if (compressMeta != null && compressMeta instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> compressMetaMap = (Map<String, Object>) compressMeta;
                if (Boolean.TRUE.equals(compressMetaMap.get("compressed_current_round"))) {
                    return false;
                }
            }
        }

        // A final response should not contain ToolUseBlock (tool calls)
        // It may contain TextBlock or other content blocks, but not tool calls
        return !msg.hasContentBlocks(ToolUseBlock.class)
                && !msg.hasContentBlocks(ToolResultBlock.class);
    }

    /**
     * Serializes a list of compression events to a JSON-compatible format.
     *
     * <p>Converts {@code List<CompressionEvent>} to {@code List<Map<String, Object>>} for state
     * persistence.
     *
     * @param object the object to serialize, expected to be {@code List<CompressionEvent>}
     * @return the serialized list as {@code List<Map<String, Object>>}, or the original object
     *         if it's not a List
     * @throws RuntimeException if serialization fails
     */
    @SuppressWarnings("unchecked")
    public static Object serializeCompressionEventList(Object object) {
        if (object instanceof List<?>) {
            try {
                List<CompressionEvent> events = (List<CompressionEvent>) object;
                List<Map<String, Object>> serialized = new ArrayList<>();
                for (CompressionEvent event : events) {
                    Map<String, Object> eventMap = new HashMap<>();
                    eventMap.put("eventType", event.getEventType());
                    eventMap.put("timestamp", event.getTimestamp());
                    eventMap.put("compressedMessageCount", event.getCompressedMessageCount());
                    eventMap.put("previousMessageId", event.getPreviousMessageId());
                    eventMap.put("nextMessageId", event.getNextMessageId());
                    eventMap.put("compressedMessageId", event.getCompressedMessageId());
                    eventMap.put("metadata", event.getMetadata());
                    serialized.add(eventMap);
                }
                return serialized;
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize compression event list", e);
            }
        }
        return object;
    }

    /**
     * Deserializes a list of compression events from a JSON-compatible format.
     *
     * <p>Converts {@code List<Map<String, Object>>} back to {@code List<CompressionEvent>} for
     * state restoration.
     *
     * @param data the data to deserialize, expected to be {@code List<Map<String, Object>>}
     * @return a new {@code ArrayList} containing the deserialized {@code List<CompressionEvent>},
     *         or the original object if it's not a List
     * @throws RuntimeException if deserialization fails
     */
    @SuppressWarnings("unchecked")
    public static Object deserializeToCompressionEventList(Object data) {
        if (data instanceof List<?>) {
            try {
                List<Map<String, Object>> eventDataList = (List<Map<String, Object>>) data;
                List<CompressionEvent> restoredEvents = new ArrayList<>();
                for (Map<String, Object> eventMap : eventDataList) {
                    // Extract metadata, handling both new format (with metadata) and old format
                    // (with tokenBefore/tokenAfter)
                    Map<String, Object> metadata = new HashMap<>();
                    if (eventMap.containsKey("metadata")
                            && eventMap.get("metadata") instanceof Map) {
                        // New format: metadata is already a map
                        metadata.putAll((Map<String, Object>) eventMap.get("metadata"));
                    } else {
                        // Old format: migrate tokenBefore/tokenAfter to metadata for backward
                        // compatibility
                        if (eventMap.containsKey("tokenBefore")) {
                            metadata.put("tokenBefore", eventMap.get("tokenBefore"));
                        }
                        if (eventMap.containsKey("tokenAfter")) {
                            metadata.put("tokenAfter", eventMap.get("tokenAfter"));
                        }
                        if (eventMap.containsKey("inputToken")) {
                            metadata.put("inputToken", eventMap.get("inputToken"));
                        }
                        if (eventMap.containsKey("outputToken")) {
                            metadata.put("outputToken", eventMap.get("outputToken"));
                        }
                        if (eventMap.containsKey("time")) {
                            metadata.put("time", eventMap.get("time"));
                        }
                    }

                    CompressionEvent event =
                            new CompressionEvent(
                                    (String) eventMap.get("eventType"),
                                    ((Number) eventMap.get("timestamp")).longValue(),
                                    ((Number) eventMap.get("compressedMessageCount")).intValue(),
                                    (String) eventMap.get("previousMessageId"),
                                    (String) eventMap.get("nextMessageId"),
                                    (String) eventMap.get("compressedMessageId"),
                                    metadata);
                    restoredEvents.add(event);
                }
                return restoredEvents;
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize compression event list", e);
            }
        }
        return data;
    }

    /**
     * Calculates the total character count of a message, including all content blocks.
     *
     * <p>This method counts characters from:
     * <ul>
     *   <li>TextBlock: text content</li>
     *   <li>ToolUseBlock: tool name, ID, and input parameters (serialized as JSON)</li>
     *   <li>ToolResultBlock: tool name, ID, and output content (recursively processed)</li>
     * </ul>
     *
     * @param msg the message to calculate character count for
     * @return the total character count
     */
    public static int calculateMessageCharCount(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return 0;
        }

        int charCount = 0;
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock) {
                String text = ((TextBlock) block).getText();
                if (text != null) {
                    charCount += text.length();
                }
            } else if (block instanceof ToolUseBlock) {
                ToolUseBlock toolUse = (ToolUseBlock) block;
                // Count tool name
                if (toolUse.getName() != null) {
                    charCount += toolUse.getName().length();
                }
                // Count tool ID
                if (toolUse.getId() != null) {
                    charCount += toolUse.getId().length();
                }
                // Count input parameters (serialize to JSON string for accurate count)
                if (toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
                    try {
                        String inputJson = OBJECT_MAPPER.writeValueAsString(toolUse.getInput());
                        charCount += inputJson.length();
                    } catch (Exception e) {
                        // Fallback: estimate based on map size
                        charCount += toolUse.getInput().toString().length();
                    }
                }
                // Count raw content if present
                if (toolUse.getContent() != null) {
                    charCount += toolUse.getContent().length();
                }
            } else if (block instanceof ToolResultBlock) {
                ToolResultBlock toolResult = (ToolResultBlock) block;
                // Count tool name
                if (toolResult.getName() != null) {
                    charCount += toolResult.getName().length();
                }
                // Count tool ID
                if (toolResult.getId() != null) {
                    charCount += toolResult.getId().length();
                }
                // Recursively count output content blocks
                if (toolResult.getOutput() != null) {
                    for (ContentBlock outputBlock : toolResult.getOutput()) {
                        if (outputBlock instanceof TextBlock) {
                            String text = ((TextBlock) outputBlock).getText();
                            if (text != null) {
                                charCount += text.length();
                            }
                        }
                        // For other content block types in output, we can add more handling if
                        // needed
                    }
                }
            }
        }
        return charCount;
    }

    /**
     * Calculates the total character count of a list of messages.
     *
     * @param messages the list of messages to calculate character count for
     * @return the total character count across all messages
     */
    public static int calculateMessagesCharCount(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int totalCharCount = 0;
        for (Msg msg : messages) {
            totalCharCount += calculateMessageCharCount(msg);
        }
        return totalCharCount;
    }
}
