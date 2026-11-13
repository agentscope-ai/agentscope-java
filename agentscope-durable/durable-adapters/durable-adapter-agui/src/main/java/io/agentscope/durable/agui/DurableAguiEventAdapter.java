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
package io.agentscope.durable.agui;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stateful Durable-to-AG-UI 1.0 mapping. One adapter belongs to one stream and reconciles live
 * message previews with their authoritative committed message.
 */
public final class DurableAguiEventAdapter {
    private final Set<String> messages = new HashSet<>();
    private final Map<String, StringBuilder> messageText = new HashMap<>();
    private final Set<String> tools = new HashSet<>();

    public List<AguiEvent> map(SessionStreamEvent event) {
        Objects.requireNonNull(event);
        if (event instanceof SessionStreamEvent.Committed committed)
            return committed(committed.event());
        if (event instanceof SessionStreamEvent.PreviewStarted preview) {
            if (preview.previewType() != EventDeltaType.MESSAGE) return List.of();
            return startMessage(
                    preview.threadId(), require(preview.runId(), "runId"), preview.data());
        }
        if (event instanceof SessionStreamEvent.EventDelta delta) {
            String runId = require(delta.runId(), "runId");
            if (delta.deltaType() == EventDeltaType.MESSAGE)
                return messageDelta(delta.threadId(), runId, delta.data());
            if (delta.deltaType() == EventDeltaType.TOOL_ARGUMENTS)
                return toolArguments(delta.threadId(), runId, delta.data());
        }
        return List.of();
    }

    List<AguiEvent> committed(PublicEvent event) {
        Objects.requireNonNull(event);
        String threadId = event.threadId();
        String runId = event.runId();
        return switch (event.type()) {
            case "run.started" ->
                    List.of(new AguiEvent.RunStarted(threadId, require(runId, "runId")));
            case "run.ended" -> runEnded(event);
            case "step.started" ->
                    List.of(
                            new AguiEvent.StepStarted(
                                    threadId,
                                    require(runId, "runId"),
                                    require(event.stepId(), "stepId")));
            case "step.ended" ->
                    List.of(
                            new AguiEvent.StepFinished(
                                    threadId,
                                    require(runId, "runId"),
                                    require(event.stepId(), "stepId")));
            case "message.completed" -> message(event);
            case "tool.call.updated" -> tool(event);
            default -> List.of();
        };
    }

    List<AguiEvent> live(LiveEvent event) {
        Objects.requireNonNull(event);
        String threadId = event.threadId();
        String runId = require(event.runId(), "runId");
        var data = event.data();
        return switch (event.type()) {
            case "message.started" -> startMessage(threadId, runId, data);
            case "message.delta" -> messageDelta(threadId, runId, data);
            case "tool.arguments.delta" -> toolArguments(threadId, runId, data);
            default -> List.of();
        };
    }

    private List<AguiEvent> runEnded(PublicEvent event) {
        String status = event.data().path("status").asText();
        if ("FAILED".equals(status) || "LOST".equals(status))
            return List.of(
                    new AguiEvent.RunError(
                            event.threadId(),
                            require(event.runId(), "runId"),
                            event.data().path("reason").asText("Durable Run failed"),
                            status));
        return List.of(
                new AguiEvent.RunFinished(event.threadId(), require(event.runId(), "runId")));
    }

    private List<AguiEvent> startMessage(String threadId, String runId, JsonNode data) {
        String messageId = data.required("messageId").asText();
        if (!messages.add(messageId)) return List.of();
        messageText.put(messageId, new StringBuilder());
        return List.of(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
    }

    private List<AguiEvent> messageDelta(String threadId, String runId, JsonNode data) {
        String messageId = data.required("messageId").asText();
        var result = new ArrayList<AguiEvent>();
        if (!messages.contains(messageId)) result.addAll(startMessage(threadId, runId, data));
        String text = data.path("text").asText();
        messageText.get(messageId).append(text);
        result.add(new AguiEvent.TextMessageContent(threadId, runId, messageId, text));
        return List.copyOf(result);
    }

    private List<AguiEvent> message(PublicEvent event) {
        JsonNode message = event.data().required("message");
        if (!"assistant".equals(message.path("role").asText()) || event.runId() == null)
            return List.of();
        String messageId = event.data().required("messageId").asText();
        String text = text(message.required("content"));
        var result = new ArrayList<AguiEvent>();
        if (!messages.contains(messageId))
            result.addAll(startMessage(event.threadId(), event.runId(), event.data()));
        String preview = messageText.get(messageId).toString();
        if (text.startsWith(preview) && text.length() > preview.length())
            result.add(
                    new AguiEvent.TextMessageContent(
                            event.threadId(),
                            event.runId(),
                            messageId,
                            text.substring(preview.length())));
        else if (!text.equals(preview))
            result.add(
                    new AguiEvent.Custom(
                            event.threadId(),
                            event.runId(),
                            "agentscope.message_reconciled",
                            DurableJson.convert(message, Object.class)));
        result.add(new AguiEvent.TextMessageEnd(event.threadId(), event.runId(), messageId));
        messages.remove(messageId);
        messageText.remove(messageId);
        return List.copyOf(result);
    }

    private List<AguiEvent> toolArguments(String threadId, String runId, JsonNode data) {
        String callId = data.required("toolCallId").asText();
        var result = new ArrayList<AguiEvent>();
        if (tools.add(callId))
            result.add(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, callId, data.path("name").asText("tool")));
        result.add(new AguiEvent.ToolCallArgs(threadId, runId, callId, data.path("text").asText()));
        return List.copyOf(result);
    }

    private List<AguiEvent> tool(PublicEvent event) {
        if (event.runId() == null) return List.of();
        var data = event.data();
        String callId = data.required("callId").asText();
        String status = data.required("status").asText();
        var result = new ArrayList<AguiEvent>();
        if ("REQUESTED".equals(status)) {
            if (tools.add(callId))
                result.add(
                        new AguiEvent.ToolCallStart(
                                event.threadId(),
                                event.runId(),
                                callId,
                                data.path("name").asText("tool")));
            if (data.has("arguments"))
                result.add(
                        new AguiEvent.ToolCallArgs(
                                event.threadId(),
                                event.runId(),
                                callId,
                                data.get("arguments").toString()));
            result.add(new AguiEvent.ToolCallEnd(event.threadId(), event.runId(), callId));
        } else if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "DENIED", "UNKNOWN")
                .contains(status)) {
            String content = data.has("content") ? data.get("content").toString() : status;
            result.add(
                    new AguiEvent.ToolCallResult(
                            event.threadId(), event.runId(), callId, content, "tool", null));
            tools.remove(callId);
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode content) {
        var result = new StringBuilder();
        for (var block : content)
            if ("text".equals(block.path("type").asText()))
                result.append(block.path("text").asText());
        return result.toString();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("AG-UI event requires " + name);
        return value;
    }
}
