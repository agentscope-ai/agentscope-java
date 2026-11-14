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
package io.agentscope.durable.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stateful mapping from protocol-neutral Durable events to Claude Managed Agents session events.
 * One instance belongs to one stream so content block indexes and preview starts stay scoped to
 * that connection.
 */
public final class ClaudeEventAdapter {
    public static final String AGENT_MESSAGE = "agent.message";

    private final Clock clock;
    private final Set<String> previewed = new HashSet<>();
    private final Map<String, Map<String, Integer>> blockIndexes = new HashMap<>();

    public ClaudeEventAdapter() {
        this(Clock.systemUTC());
    }

    /** Map one item from the public typed Durable stream. */
    public List<JsonNode> map(SessionStreamEvent event) {
        Objects.requireNonNull(event);
        if (event instanceof SessionStreamEvent.Committed committed)
            return committed(committed.event());
        if (event instanceof SessionStreamEvent.PreviewStarted preview) {
            if (preview.previewType() != EventDeltaType.MESSAGE) return List.of();
            return start(preview.eventId());
        }
        if (event instanceof SessionStreamEvent.EventDelta delta) {
            if (delta.deltaType() != EventDeltaType.MESSAGE) return List.of();
            return delta(delta.eventId(), delta.blockId(), delta.data());
        }
        return List.of();
    }

    ClaudeEventAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** Map one committed Durable event. Unsupported internal facts are intentionally hidden. */
    List<JsonNode> committed(PublicEvent event) {
        Objects.requireNonNull(event);
        if (!"message.completed".equals(event.type())) return List.of();
        var data = event.data();
        String id = data.required("messageId").asText();
        JsonNode message = data.required("message");
        String role = message.required("role").asText();
        String type =
                switch (role) {
                    case "assistant" -> AGENT_MESSAGE;
                    case "user" -> "user.message";
                    case "system" -> "system.message";
                    default ->
                            throw new DurableException(
                                    DurableException.Code.INCOMPATIBLE,
                                    "Claude does not support Durable message role: " + role);
                };
        var output = DurableJson.value(Map.of("type", type, "id", id));
        ((ObjectNode) output).set("content", content(message.required("content")));
        ((ObjectNode) output).put("processed_at", Instant.now(clock).toString());
        previewed.remove(id);
        blockIndexes.remove(id);
        return List.of(output);
    }

    /** Map one best-effort live event. Preview events are emitted only when explicitly enabled. */
    List<JsonNode> live(LiveEvent event, Set<String> eventDeltas) {
        Objects.requireNonNull(event);
        if (!eventDeltas.contains(AGENT_MESSAGE)) return List.of();
        String messageId = event.data().path("messageId").asText(null);
        if (messageId == null) return List.of();
        if ("message.started".equals(event.type())) return start(messageId);
        if (!"message.delta".equals(event.type())) return List.of();
        var output = new ArrayList<JsonNode>();
        if (!previewed.contains(messageId)) output.addAll(start(messageId));
        output.addAll(delta(messageId, event.data().path("blockId").asText(null), event.data()));
        return List.copyOf(output);
    }

    private List<JsonNode> delta(String messageId, String blockId, JsonNode data) {
        var output = new ArrayList<JsonNode>();
        if (!previewed.contains(messageId)) output.addAll(start(messageId));
        String block = blockId == null ? "0" : blockId;
        int index =
                blockIndexes
                        .computeIfAbsent(messageId, ignored -> new HashMap<>())
                        .computeIfAbsent(block, ignored -> blockIndexes.get(messageId).size());
        var delta = DurableJson.value(Map.of("type", "event_delta", "event_id", messageId));
        ((ObjectNode) delta)
                .set(
                        "delta",
                        DurableJson.value(
                                Map.of(
                                        "type",
                                        "content_delta",
                                        "index",
                                        index,
                                        "content",
                                        Map.of(
                                                "type",
                                                "text",
                                                "text",
                                                data.path("text").asText()))));
        output.add(delta);
        return List.copyOf(output);
    }

    private List<JsonNode> start(String messageId) {
        if (!previewed.add(messageId)) return List.of();
        return List.of(
                DurableJson.value(
                        Map.of(
                                "type",
                                "event_start",
                                "event",
                                Map.of("type", AGENT_MESSAGE, "id", messageId))));
    }

    private static ArrayNode content(JsonNode content) {
        var result = (ArrayNode) DurableJson.value(List.of());
        for (var block : content) {
            String type = block.required("type").asText();
            if ("text".equals(type)) {
                result.add(block.deepCopy());
                continue;
            }
            if ("image".equals(type)) {
                var source = block.required("source");
                String sourceType = source.required("type").asText();
                ObjectNode mapped;
                if ("url".equals(sourceType)) {
                    mapped = (ObjectNode) DurableJson.value(Map.of("type", "url"));
                    mapped.put("url", source.required("url").asText());
                } else if ("base64".equals(sourceType)) {
                    mapped = (ObjectNode) DurableJson.value(Map.of("type", "base64"));
                    mapped.put("media_type", source.required("mimeType").asText());
                    mapped.put("data", source.required("data").asText());
                } else {
                    throw incompatible("image source", sourceType);
                }
                var image = (ObjectNode) DurableJson.value(Map.of("type", "image"));
                image.set("source", mapped);
                result.add(image);
                continue;
            }
            throw incompatible("content", type);
        }
        return result;
    }

    private static DurableException incompatible(String kind, String type) {
        return new DurableException(
                DurableException.Code.INCOMPATIBLE,
                "Claude does not support Durable " + kind + " type: " + type);
    }
}
