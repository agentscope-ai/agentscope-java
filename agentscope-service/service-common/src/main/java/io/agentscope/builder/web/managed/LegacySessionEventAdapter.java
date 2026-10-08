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
package io.agentscope.builder.web.managed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only compatibility view of the public event journal for the original session API.
 *
 * <p>One input row always produces one output row with the same id and sequence. Keeping even
 * unrecognized events preserves resume cursors and the old client's contiguous-sequence repair.
 * No execution facts, reasoning buffers, or tool output copies are written by this adapter.
 */
public final class LegacySessionEventAdapter {
    private LegacySessionEventAdapter() {}

    public static SessionEventDto adapt(SessionEventDto event) {
        Map<String, Object> payload =
                new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
        if (payload.get("source") instanceof Map<?, ?> source
                && (source.containsKey("native_seq") || source.containsKey("native_event_id")))
            payload.remove("source");
        String type = event.type();
        switch (type) {
            case "item.completed" -> {
                if (payload.get("item") instanceof Map<?, ?> item) {
                    Object content = item.get("content");
                    payload.put("message_id", item.get("id"));
                    payload.put("event_id", item.get("id"));
                    payload.put("content", content);
                    String text = text(content);
                    payload.put("text", text);
                    if ("tool_result".equals(item.get("type"))) {
                        List<Map<String, Object>> results = toolResults(content);
                        if (!results.isEmpty()) {
                            type = SessionEventTypes.AGENT_TOOL_RESULT;
                            payload.putAll(results.get(0));
                            payload.put("tool_results", results);
                        }
                    } else if (!text.isEmpty()
                            || Boolean.TRUE.equals(payload.get("final_output"))
                            || "user".equalsIgnoreCase(String.valueOf(item.get("role")))) {
                        type =
                                "user".equalsIgnoreCase(String.valueOf(item.get("role")))
                                        ? SessionEventTypes.USER_MESSAGE
                                        : SessionEventTypes.AGENT_MESSAGE;
                    }
                }
            }
            case "tool.requested" -> {
                type = SessionEventTypes.AGENT_TOOL_USE;
                payload.put("id", payload.get("tool_call_id"));
                payload.put("toolCallId", payload.get("tool_call_id"));
                payload.put("toolName", payload.get("name"));
            }
            case "usage.recorded" -> type = SessionEventTypes.SPAN_MODEL_REQUEST_END;
            case "context.compacted" -> type = SessionEventTypes.AGENT_THREAD_CONTEXT_COMPACTED;
            case "item.delta" -> {
                type = SessionEventTypes.EVENT_DELTA;
                payload.put("type", SessionEventTypes.AGENT_MESSAGE);
                payload.put("event_id", payload.getOrDefault("item_id", payload.get("preview_id")));
                if (payload.containsKey("content"))
                    payload.put("delta", text(payload.get("content")));
            }
            default -> {}
        }
        return new SessionEventDto(
                event.id(),
                event.sessionId(),
                event.seq(),
                type,
                payload,
                event.processedAt(),
                event.createdAt());
    }

    private static List<Map<String, Object>> toolResults(Object content) {
        if (!(content instanceof List<?> blocks)) return List.of();
        return blocks.stream()
                .filter(Map.class::isInstance)
                .map(block -> (Map<?, ?>) block)
                .filter(block -> "tool_result".equals(block.get("type")))
                .map(
                        block -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("id", block.get("id"));
                            result.put("tool_use_id", block.get("id"));
                            result.put("name", block.get("name"));
                            result.put(
                                    "state",
                                    block.get("state") == null
                                            ? block.get("status")
                                            : block.get("state"));
                            // Keep the legacy display compact; Agent API exposes the public
                            // result content in its own tool and item resources.
                            result.put(
                                    "output",
                                    "Tool execution " + String.valueOf(result.get("state")));
                            return result;
                        })
                .toList();
    }

    private static String text(Object content) {
        if (!(content instanceof List<?> blocks)) return "";
        StringBuilder text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> value
                    && "text".equals(value.get("type"))
                    && value.get("text") instanceof String valueText) text.append(valueText);
        }
        return text.toString();
    }
}
