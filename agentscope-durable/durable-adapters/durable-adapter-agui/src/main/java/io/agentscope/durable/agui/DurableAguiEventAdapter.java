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
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stateful Durable-to-AG-UI 1.0 mapping. One adapter belongs to one stream and reconciles live
 * message previews with their authoritative committed message.
 *
 * <p>A Run that parks for human input reports {@code run.ended} with {@code STOPPED} before the
 * committed {@code turn.waiting} event, so that end is not terminal. Only {@code turn.waiting}
 * closes the stream and carries the pending interactions: it maps to one {@link
 * AguiEvent.RunFinished} with a {@link AguiEvent.RunFinishedInterruptOutcome}, after closing the
 * steps, messages and tool calls the Run opened. Individual {@code interaction.requested} events
 * are not terminal and are not mapped on their own. Each interrupt id is the Durable interaction
 * id, and its {@code toolCallId} is the interaction request id (the tool call id), which the client
 * echoes as {@code interruptId} to resume.
 */
public final class DurableAguiEventAdapter {
    private static final Map<String, Object> CONFIRM_RESPONSE_SCHEMA =
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "approved",
                            Map.of("type", "boolean"),
                            "editedArgs",
                            Map.of(
                                    "type",
                                    "object",
                                    "description",
                                    "Full replacement of the tool args. Not merged."),
                            "reason",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional explanation supplied when the tool call is"
                                            + " denied.")),
                    "required",
                    List.of("approved"));

    private final Set<String> messages = new HashSet<>();
    private final Map<String, StringBuilder> messageText = new HashMap<>();
    private final Set<String> tools = new HashSet<>();
    private final Set<String> openToolCalls = new HashSet<>();
    private final Set<String> steps = new HashSet<>();
    private final Set<String> finishedRuns = new HashSet<>();

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
        return switch (event.type()) {
            case "run.started" -> runStarted(event);
            case "run.ended" -> runEnded(event);
            case "step.started" -> stepStarted(event);
            case "step.ended" -> stepEnded(event);
            case "message.completed" -> message(event);
            case "tool.call.updated" -> tool(event);
            case "turn.waiting" -> waiting(event);
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

    private List<AguiEvent> runStarted(PublicEvent event) {
        clear();
        return List.of(new AguiEvent.RunStarted(event.threadId(), require(event.runId(), "runId")));
    }

    private List<AguiEvent> runEnded(PublicEvent event) {
        String status = event.data().path("status").asText();
        String reason = event.data().path("reason").asText();
        if ("STOPPED".equals(status)
                && ("suspended".equals(reason) || "PERMISSION_ASKING".equals(reason)))
            return List.of();
        String runId = require(event.runId(), "runId");
        if ("FAILED".equals(status) || "LOST".equals(status)) {
            finishedRuns.add(runId);
            var result = new ArrayList<>(closeOpen(event.threadId(), runId));
            result.add(
                    new AguiEvent.RunError(
                            event.threadId(),
                            runId,
                            event.data().path("reason").asText("Durable Run failed"),
                            status));
            return List.copyOf(result);
        }
        if (!finishedRuns.add(runId)) return List.of();
        var result = new ArrayList<>(closeOpen(event.threadId(), runId));
        result.add(
                new AguiEvent.RunFinished(
                        event.threadId(),
                        runId,
                        null,
                        "STOPPED".equals(status)
                                ? new AguiEvent.RunFinishedCancelledOutcome()
                                : new AguiEvent.RunFinishedSuccessOutcome()));
        return List.copyOf(result);
    }

    private List<AguiEvent> stepStarted(PublicEvent event) {
        String stepId = require(event.stepId(), "stepId");
        steps.add(stepId);
        return List.of(
                new AguiEvent.StepStarted(
                        event.threadId(), require(event.runId(), "runId"), stepId));
    }

    private List<AguiEvent> stepEnded(PublicEvent event) {
        String stepId = require(event.stepId(), "stepId");
        steps.remove(stepId);
        return List.of(
                new AguiEvent.StepFinished(
                        event.threadId(), require(event.runId(), "runId"), stepId));
    }

    /**
     * A Turn entered WAITING: the Run ends with every pending interaction, because a later {@code
     * run.ended} never arrives while the Turn is parked. Everything the Run opened is closed first.
     */
    private List<AguiEvent> waiting(PublicEvent event) {
        var interactions = event.data().path("interactions");
        String runId = event.runId();
        if (runId == null || !interactions.isArray() || interactions.isEmpty()) return List.of();
        if (!finishedRuns.add(runId)) return List.of();
        var result = new ArrayList<>(closeOpen(event.threadId(), runId));
        var interrupts = new ArrayList<AguiEvent.Interrupt>();
        for (var interaction : interactions) interrupts.add(interrupt(interaction));
        result.add(
                new AguiEvent.RunFinished(
                        event.threadId(),
                        runId,
                        null,
                        new AguiEvent.RunFinishedInterruptOutcome(List.copyOf(interrupts))));
        return List.copyOf(result);
    }

    /** AG-UI requires every step, message and tool call a Run opened to be closed first. */
    private List<AguiEvent> closeOpen(String threadId, String runId) {
        var result = new ArrayList<AguiEvent>();
        for (String stepId : List.copyOf(steps))
            result.add(new AguiEvent.StepFinished(threadId, runId, stepId));
        for (String messageId : List.copyOf(messages))
            result.add(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
        for (String callId : List.copyOf(openToolCalls))
            result.add(new AguiEvent.ToolCallEnd(threadId, runId, callId));
        messages.clear();
        messageText.clear();
        tools.clear();
        openToolCalls.clear();
        steps.clear();
        return List.copyOf(result);
    }

    private void clear() {
        messages.clear();
        messageText.clear();
        tools.clear();
        openToolCalls.clear();
        steps.clear();
        finishedRuns.clear();
    }

    private static AguiEvent.Interrupt interrupt(JsonNode interaction) {
        String interruptId = interaction.required("id").asText();
        String requestId = interaction.path("requestId").asText(null);
        String kind = interaction.path("kind").asText();
        var call = interaction.path("call");
        String toolName = call.path("name").asText("tool");
        boolean confirmation = "confirmation".equals(kind);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AguiInterruptConstants.METADATA_AGENTSCOPE_INTERRUPT_KIND, kind);
        metadata.put(AguiInterruptConstants.METADATA_TOOL_NAME, toolName);
        metadata.put("automatic", interaction.path("automatic").asBoolean());
        if (requestId != null) metadata.put("requestId", requestId);
        if (call.hasNonNull("arguments"))
            metadata.put(
                    AguiInterruptConstants.METADATA_TOOL_INPUT,
                    DurableJson.convert(call.get("arguments"), Object.class));
        return new AguiEvent.Interrupt(
                interruptId,
                AguiInterruptConstants.TOOL_CALL_INTERRUPT_REASON,
                confirmation
                        ? "Tool '" + toolName + "' requires user confirmation before execution"
                        : "Tool '" + toolName + "' requires external execution",
                requestId,
                confirmation ? CONFIRM_RESPONSE_SCHEMA : null,
                null,
                Collections.unmodifiableMap(metadata));
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
        openToolCalls.add(callId);
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
            openToolCalls.remove(callId);
        } else if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "DENIED", "UNKNOWN")
                .contains(status)) {
            String content = data.has("content") ? data.get("content").toString() : status;
            result.add(
                    new AguiEvent.ToolCallResult(
                            event.threadId(),
                            event.runId(),
                            callId,
                            content,
                            "tool",
                            callId + ":result"));
            tools.remove(callId);
            openToolCalls.remove(callId);
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
