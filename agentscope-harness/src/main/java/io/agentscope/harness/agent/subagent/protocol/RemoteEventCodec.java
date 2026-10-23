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
package io.agentscope.harness.agent.subagent.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bidirectional mapping between internal {@link AgentEvent}s and stable {@link RemoteAgentEvent}
 * wire DTOs.
 */
public final class RemoteEventCodec {

    private static final Logger log = LoggerFactory.getLogger(RemoteEventCodec.class);

    /**
     * Unknown properties are ignored so that a payload survives derived getters that no constructor
     * accepts (for example {@code ChatUsage.getTotalTokens()}) and fields added by a newer peer.
     */
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RemoteEventCodec() {}

    /**
     * Maps an internal agent event to a protocol DTO (without seq/taskId — those are assigned by
     * the server event bus).
     *
     * <p>Every event also carries its full serialization in {@link RemoteAgentEvent#getPayload()},
     * so a client can restore the original instance instead of the lossy flat fields. Event types
     * without a dedicated wire type are forwarded as {@link RemoteEventType#AGENT_EVENT} and are
     * only visible at {@code detail=verbose}.
     */
    public static Optional<RemoteAgentEvent> fromAgentEvent(AgentEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        RemoteAgentEvent dto = new RemoteAgentEvent();
        dto.setTimestamp(event.getCreatedAt());
        if (event.getType() != null) {
            dto.setEventType(event.getType().name());
        }
        dto.setPayload(serializeEvent(event));
        return Optional.of(withTypedFields(dto, event));
    }

    /**
     * Fills the flat, type-specific fields kept for clients that do not read {@code payload}, and
     * assigns the wire type. Falls back to {@link RemoteEventType#AGENT_EVENT} for events that
     * never had a dedicated wire type.
     */
    private static RemoteAgentEvent withTypedFields(RemoteAgentEvent dto, AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            dto.setType(RemoteEventType.RUN_STARTED);
            dto.setAgentId(start.getName());
            return dto;
        }
        if (event instanceof AgentEndEvent) {
            dto.setType(RemoteEventType.RUN_FINISHED);
            return dto;
        }
        if (event instanceof TextBlockDeltaEvent text) {
            dto.setType(RemoteEventType.TEXT_DELTA);
            dto.setText(text.getDelta());
            return dto;
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            dto.setType(RemoteEventType.THINKING_DELTA);
            dto.setText(thinking.getDelta());
            return dto;
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            dto.setType(RemoteEventType.TOOL_CALL_START);
            dto.setToolCallId(toolStart.getToolCallId());
            dto.setToolName(toolStart.getToolCallName());
            return dto;
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            dto.setType(RemoteEventType.TOOL_CALL_END);
            dto.setToolCallId(toolEnd.getToolCallId());
            dto.setToolName(toolEnd.getToolCallName());
            return dto;
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            dto.setType(RemoteEventType.TOOL_RESULT);
            dto.setToolCallId(toolResult.getToolCallId());
            dto.setToolName(toolResult.getToolCallName());
            ToolResultState state = toolResult.getState();
            if (state != null) {
                dto.setStatus(state.name());
            }
            return dto;
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            dto.setType(RemoteEventType.REQUIRE_CONFIRM);
            List<RemotePendingConfirm> pending = new ArrayList<>();
            for (ToolUseBlock block : confirm.getToolCalls()) {
                pending.add(
                        new RemotePendingConfirm(
                                block.getId(), block.getName(), toJson(block.getInput())));
            }
            dto.setPendingConfirms(pending);
            return dto;
        }
        dto.setType(RemoteEventType.AGENT_EVENT);
        return dto;
    }

    /**
     * Maps a protocol DTO back to an internal {@link AgentEvent}. Unknown or incomplete types are
     * dropped. When the DTO carries a {@code taskId}, it is copied onto {@link
     * AgentEvent#METADATA_TASK_ID}.
     *
     * <p>A {@code payload} is preferred over the flat fields: it restores the original event with
     * its ids, timestamps and metadata intact, and is the only way to recover events sent as
     * {@link RemoteEventType#AGENT_EVENT}. Servers too old to send one still decode through the
     * per-type mapping below.
     */
    public static Optional<AgentEvent> toAgentEvent(RemoteAgentEvent remote) {
        if (remote == null) {
            return Optional.empty();
        }
        Optional<AgentEvent> fromPayload = deserializeEvent(remote.getPayload());
        if (fromPayload.isPresent()) {
            return fromPayload.map(event -> stampTaskId(event, remote));
        }
        if (remote.getType() == null) {
            return Optional.empty();
        }
        Optional<AgentEvent> mapped =
                switch (remote.getType()) {
                    case RUN_STARTED ->
                            Optional.of(
                                    new AgentStartEvent(
                                            null,
                                            null,
                                            remote.getAgentId() != null
                                                    ? remote.getAgentId()
                                                    : "remote"));
                    case RUN_FINISHED -> Optional.of(new AgentEndEvent(null));
                    case RUN_ERROR -> Optional.empty();
                    case TEXT_DELTA ->
                            Optional.of(
                                    new TextBlockDeltaEvent(
                                            null,
                                            null,
                                            remote.getText() != null ? remote.getText() : ""));
                    case THINKING_DELTA ->
                            Optional.of(
                                    new ThinkingBlockDeltaEvent(
                                            null,
                                            null,
                                            remote.getText() != null ? remote.getText() : ""));
                    case TOOL_CALL_START ->
                            Optional.of(
                                    new ToolCallStartEvent(
                                            null, remote.getToolCallId(), remote.getToolName()));
                    case TOOL_CALL_END ->
                            Optional.of(
                                    new ToolCallEndEvent(
                                            null, remote.getToolCallId(), remote.getToolName()));
                    case TOOL_RESULT ->
                            Optional.of(
                                    new ToolResultEndEvent(
                                            null,
                                            remote.getToolCallId(),
                                            remote.getToolName(),
                                            parseToolResultState(remote.getStatus())));
                    case REQUIRE_CONFIRM -> Optional.of(toRequireConfirm(remote));
                    case STATUS -> Optional.empty();
                    case AGENT_EVENT -> Optional.empty();
                };
        return mapped.map(event -> stampTaskId(event, remote));
    }

    private static AgentEvent stampTaskId(AgentEvent event, RemoteAgentEvent remote) {
        if (remote.getTaskId() != null && !remote.getTaskId().isBlank()) {
            event.withMetadataEntry(AgentEvent.METADATA_TASK_ID, remote.getTaskId());
        }
        return event;
    }

    /**
     * Whether this event type should be emitted for the given detail level.
     *
     * @param type event type
     * @param detail see {@link RemoteStreamDetail}
     */
    public static boolean matchesDetail(RemoteEventType type, String detail) {
        if (type == null) {
            return false;
        }
        RemoteStreamDetail level = RemoteStreamDetail.parse(detail);
        return switch (type) {
            case TEXT_DELTA, THINKING_DELTA -> level.includes(RemoteStreamDetail.FULL);
            case AGENT_EVENT -> level.includes(RemoteStreamDetail.VERBOSE);
            default -> true;
        };
    }

    /** Detail check for a whole DTO; equivalent to {@link #matchesDetail(RemoteEventType, String)}. */
    public static boolean matchesDetail(RemoteAgentEvent event, String detail) {
        return event != null && matchesDetail(event.getType(), detail);
    }

    private static RequireUserConfirmEvent toRequireConfirm(RemoteAgentEvent remote) {
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        List<RemotePendingConfirm> pending =
                remote.getPendingConfirms() != null ? remote.getPendingConfirms() : List.of();
        for (RemotePendingConfirm p : pending) {
            Map<String, Object> input = parseInput(p.getToolInputJson());
            toolCalls.add(
                    ToolUseBlock.builder()
                            .id(p.getToolCallId())
                            .name(p.getToolName())
                            .input(input)
                            .state(ToolCallState.ASKING)
                            .build());
        }
        return new RequireUserConfirmEvent(null, toolCalls);
    }

    private static ToolResultState parseToolResultState(String status) {
        if (status == null || status.isBlank()) {
            return ToolResultState.SUCCESS;
        }
        try {
            return ToolResultState.valueOf(status);
        } catch (IllegalArgumentException e) {
            for (ToolResultState s : ToolResultState.values()) {
                if (s.getValue().equalsIgnoreCase(status)) {
                    return s;
                }
            }
            return ToolResultState.SUCCESS;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInput(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = JSON.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new HashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return out;
            }
            return Map.of("raw", parsed);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    /**
     * Serializes an event for {@link RemoteAgentEvent#getPayload()}. Returns {@code null} when the
     * event cannot be represented as JSON, in which case the receiver falls back to the flat wire
     * fields rather than the run failing.
     */
    private static String serializeEvent(AgentEvent event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (Exception e) {
            log.debug(
                    "Skipping payload for event {}: {}",
                    event.getClass().getSimpleName(),
                    e.toString());
            return null;
        }
    }

    private static Optional<AgentEvent> deserializeEvent(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(payload, AgentEvent.class));
        } catch (Exception e) {
            log.debug("Skipping unreadable event payload: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
