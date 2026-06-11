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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Misc message utilities used by auto context. */
public final class MsgUtils {

    private static final Set<String> PLAN_RELATED_TOOLS =
            Set.of(
                    "create_plan",
                    "update_plan_info",
                    "revise_current_plan",
                    "update_subtask_state",
                    "finish_subtask",
                    "view_subtasks",
                    "get_subtask_count",
                    "finish_plan",
                    "view_historical_plans",
                    "recover_historical_plan");

    private MsgUtils() {}

    public static Object serializeMsgList(Object messages) {
        if (!(messages instanceof List<?> list)) {
            return messages;
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Msg msg)) {
                continue;
            }
            serialized.add(
                    JsonUtils.getJsonCodec()
                            .convertValue(msg, new TypeReference<Map<String, Object>>() {}));
        }
        return serialized;
    }

    public static Object deserializeToMsgList(Object data) {
        if (!(data instanceof List<?> list)) {
            return data;
        }
        List<Msg> restored = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            restored.add(JsonUtils.getJsonCodec().convertValue(map, Msg.class));
        }
        return restored;
    }

    public static Object serializeMsgListMap(Object object) {
        if (!(object instanceof Map<?, ?> map)) {
            return object;
        }
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Msg> msgs = (List<Msg>) list;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> serialized =
                        (List<Map<String, Object>>) serializeMsgList(msgs);
                result.put(key, serialized);
            }
        }
        return result;
    }

    public static Object deserializeToMsgListMap(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return data;
        }
        Map<String, List<Msg>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                Object restored = deserializeToMsgList(entry.getValue());
                if (restored instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<Msg> msgs = (List<Msg>) list;
                    result.put(key, msgs);
                }
            }
        }
        return result;
    }

    public static Object serializeCompressionEventList(Object object) {
        if (!(object instanceof List<?> list)) {
            return object;
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof CompressionEvent event)) {
                continue;
            }
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
    }

    public static Object deserializeToCompressionEventList(Object data) {
        if (!(data instanceof List<?> list)) {
            return data;
        }
        List<CompressionEvent> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    map.put(key, entry.getValue());
                }
            }
            Map<String, Object> metadata = new HashMap<>();
            Object meta = map.get("metadata");
            if (meta instanceof Map<?, ?> metaMap) {
                for (Map.Entry<?, ?> entry : metaMap.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        metadata.put(key, entry.getValue());
                    }
                }
            } else {
                if (map.containsKey("tokenBefore")) {
                    metadata.put("tokenBefore", map.get("tokenBefore"));
                }
                if (map.containsKey("tokenAfter")) {
                    metadata.put("tokenAfter", map.get("tokenAfter"));
                }
                if (map.containsKey("inputToken")) {
                    metadata.put("inputToken", map.get("inputToken"));
                }
                if (map.containsKey("outputToken")) {
                    metadata.put("outputToken", map.get("outputToken"));
                }
                if (map.containsKey("time")) {
                    metadata.put("time", map.get("time"));
                }
            }
            result.add(
                    new CompressionEvent(
                            (String) map.get("eventType"),
                            ((Number) map.getOrDefault("timestamp", 0L)).longValue(),
                            ((Number) map.getOrDefault("compressedMessageCount", 0)).intValue(),
                            (String) map.get("previousMessageId"),
                            (String) map.get("nextMessageId"),
                            (String) map.get("compressedMessageId"),
                            metadata));
        }
        return result;
    }

    public static void replaceMsg(List<Msg> rawMessages, int startIndex, int endIndex, Msg newMsg) {
        if (rawMessages == null || newMsg == null || startIndex < 0 || endIndex < startIndex) {
            return;
        }
        if (startIndex >= rawMessages.size()) {
            return;
        }
        int actualEnd = Math.min(endIndex, rawMessages.size() - 1);
        rawMessages.subList(startIndex, actualEnd + 1).clear();
        rawMessages.add(startIndex, newMsg);
    }

    public static boolean isToolMessage(Msg msg) {
        return msg != null
                && (msg.getRole() == MsgRole.TOOL
                        || msg.hasContentBlocks(ToolUseBlock.class)
                        || msg.hasContentBlocks(ToolResultBlock.class));
    }

    public static boolean isToolUseMessage(Msg msg) {
        return msg != null
                && msg.getRole() == MsgRole.ASSISTANT
                && msg.hasContentBlocks(ToolUseBlock.class);
    }

    public static boolean isToolResultMessage(Msg msg) {
        return msg != null
                && (msg.getRole() == MsgRole.TOOL || msg.hasContentBlocks(ToolResultBlock.class));
    }

    public static boolean isCompressedMessage(Msg msg) {
        if (msg == null || msg.getMetadata() == null) {
            return false;
        }
        return msg.getMetadata().get("_compress_meta") instanceof Map<?, ?>;
    }

    public static boolean isFinalAssistantResponse(Msg msg) {
        if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
            return false;
        }
        if (msg.getMetadata() != null) {
            Object compressMeta = msg.getMetadata().get("_compress_meta");
            if (compressMeta instanceof Map<?, ?> meta
                    && Boolean.TRUE.equals(meta.get("compressed_current_round"))) {
                return false;
            }
        }
        return !msg.hasContentBlocks(ToolUseBlock.class)
                && !msg.hasContentBlocks(ToolResultBlock.class);
    }

    public static boolean isPlanRelatedTool(String toolName) {
        return toolName != null && PLAN_RELATED_TOOLS.contains(toolName);
    }

    public static boolean containsPlanRelatedToolCall(Msg msg) {
        if (msg == null) {
            return false;
        }
        List<ToolUseBlock> toolUseBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (toolUseBlocks == null) {
            return false;
        }
        for (ToolUseBlock toolUse : toolUseBlocks) {
            if (toolUse != null && isPlanRelatedTool(toolUse.getName())) {
                return true;
            }
        }
        return false;
    }

    public static List<Msg> filterPlanRelatedToolCalls(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Msg> filtered = new ArrayList<>();
        Set<String> planRelatedToolCallIds = new HashSet<>();
        for (Msg msg : messages) {
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            List<ToolUseBlock> toolUseBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (toolUseBlocks == null) {
                continue;
            }
            for (ToolUseBlock toolUse : toolUseBlocks) {
                if (toolUse != null && isPlanRelatedTool(toolUse.getName())) {
                    planRelatedToolCallIds.add(toolUse.getId());
                }
            }
        }
        for (Msg msg : messages) {
            boolean shouldInclude = true;
            if (msg.getRole() == MsgRole.ASSISTANT) {
                List<ToolUseBlock> toolUseBlocks = msg.getContentBlocks(ToolUseBlock.class);
                if (toolUseBlocks != null && !toolUseBlocks.isEmpty()) {
                    boolean allPlanRelated = true;
                    for (ToolUseBlock toolUse : toolUseBlocks) {
                        if (toolUse != null && !isPlanRelatedTool(toolUse.getName())) {
                            allPlanRelated = false;
                            break;
                        }
                    }
                    if (allPlanRelated) {
                        shouldInclude = false;
                    }
                }
            }
            if (msg.getRole() == MsgRole.TOOL) {
                List<ToolResultBlock> toolResultBlocks =
                        msg.getContentBlocks(ToolResultBlock.class);
                if (toolResultBlocks != null) {
                    for (ToolResultBlock toolResult : toolResultBlocks) {
                        if (toolResult != null
                                && planRelatedToolCallIds.contains(toolResult.getId())) {
                            shouldInclude = false;
                            break;
                        }
                    }
                }
            }
            if (shouldInclude) {
                filtered.add(msg);
            }
        }
        return filtered;
    }

    public static int calculateMessageCharCount(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return 0;
        }
        int charCount = 0;
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                if (textBlock.getText() != null) {
                    charCount += textBlock.getText().length();
                }
            } else if (block instanceof ToolUseBlock toolUse) {
                if (toolUse.getName() != null) {
                    charCount += toolUse.getName().length();
                }
                if (toolUse.getId() != null) {
                    charCount += toolUse.getId().length();
                }
                if (toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
                    try {
                        charCount += JsonUtils.getJsonCodec().toJson(toolUse.getInput()).length();
                    } catch (Exception e) {
                        charCount += toolUse.getInput().toString().length();
                    }
                }
                if (toolUse.getContent() != null) {
                    charCount += toolUse.getContent().length();
                }
            } else if (block instanceof ToolResultBlock toolResult) {
                if (toolResult.getName() != null) {
                    charCount += toolResult.getName().length();
                }
                if (toolResult.getId() != null) {
                    charCount += toolResult.getId().length();
                }
                if (toolResult.getOutput() != null) {
                    for (ContentBlock outputBlock : toolResult.getOutput()) {
                        if (outputBlock instanceof TextBlock textBlock
                                && textBlock.getText() != null) {
                            charCount += textBlock.getText().length();
                        }
                    }
                }
            }
        }
        return charCount;
    }

    public static int calculateMessagesCharCount(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Msg msg : messages) {
            total += calculateMessageCharCount(msg);
        }
        return total;
    }
}
