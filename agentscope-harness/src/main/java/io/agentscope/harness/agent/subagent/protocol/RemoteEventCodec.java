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

/**
 * Bidirectional mapping between internal {@link AgentEvent}s and stable {@link RemoteAgentEvent}
 * wire DTOs.
 */
public final class RemoteEventCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RemoteEventCodec() {}

    /**
     * Maps an internal agent event to a protocol DTO (without seq/taskId — those are assigned by
     * the server event bus).
     */
    public static Optional<RemoteAgentEvent> fromAgentEvent(AgentEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        RemoteAgentEvent dto = new RemoteAgentEvent();
        dto.setTimestamp(event.getCreatedAt());
        if (event instanceof AgentStartEvent start) {
            dto.setType(RemoteEventType.RUN_STARTED);
            dto.setAgentId(start.getName());
            return Optional.of(dto);
        }
        if (event instanceof AgentEndEvent) {
            dto.setType(RemoteEventType.RUN_FINISHED);
            return Optional.of(dto);
        }
        if (event instanceof TextBlockDeltaEvent text) {
            dto.setType(RemoteEventType.TEXT_DELTA);
            dto.setText(text.getDelta());
            return Optional.of(dto);
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            dto.setType(RemoteEventType.THINKING_DELTA);
            dto.setText(thinking.getDelta());
            return Optional.of(dto);
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            dto.setType(RemoteEventType.TOOL_CALL_START);
            dto.setToolCallId(toolStart.getToolCallId());
            dto.setToolName(toolStart.getToolCallName());
            return Optional.of(dto);
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            dto.setType(RemoteEventType.TOOL_CALL_END);
            dto.setToolCallId(toolEnd.getToolCallId());
            dto.setToolName(toolEnd.getToolCallName());
            return Optional.of(dto);
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            dto.setType(RemoteEventType.TOOL_RESULT);
            dto.setToolCallId(toolResult.getToolCallId());
            dto.setToolName(toolResult.getToolCallName());
            ToolResultState state = toolResult.getState();
            if (state != null) {
                dto.setStatus(state.name());
            }
            return Optional.of(dto);
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
            return Optional.of(dto);
        }
        return Optional.empty();
    }

    /**
     * Maps a protocol DTO back to an internal {@link AgentEvent}. Unknown or incomplete types are
     * dropped. When the DTO carries a {@code taskId}, it is copied onto {@link
     * AgentEvent#METADATA_TASK_ID}.
     */
    public static Optional<AgentEvent> toAgentEvent(RemoteAgentEvent remote) {
        if (remote == null || remote.getType() == null) {
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
                };
        return mapped.map(
                event -> {
                    if (remote.getTaskId() != null && !remote.getTaskId().isBlank()) {
                        event.withMetadataEntry(AgentEvent.METADATA_TASK_ID, remote.getTaskId());
                    }
                    return event;
                });
    }

    /**
     * Whether this event type should be emitted for the given detail level.
     *
     * @param type event type
     * @param detail {@code "full"} includes text/thinking deltas; anything else is status-level
     */
    public static boolean matchesDetail(RemoteEventType type, String detail) {
        if (type == null) {
            return false;
        }
        boolean full = detail != null && "full".equalsIgnoreCase(detail);
        return switch (type) {
            case TEXT_DELTA, THINKING_DELTA -> full;
            default -> true;
        };
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
