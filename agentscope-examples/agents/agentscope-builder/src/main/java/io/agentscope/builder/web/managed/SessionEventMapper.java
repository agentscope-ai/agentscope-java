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

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps harness {@link AgentEvent}s onto persisted session event types / payloads and stream-only
 * preview frames. Streaming deltas are never persisted.
 */
@Component
public class SessionEventMapper {

    /** Outcome of mapping one harness event. */
    public record MappingResult(
            Optional<PersistedEvent> persisted, Optional<PreviewFrame> preview) {

        public static MappingResult empty() {
            return new MappingResult(Optional.empty(), Optional.empty());
        }

        public static MappingResult persist(String type, Map<String, Object> payload) {
            return new MappingResult(
                    Optional.of(new PersistedEvent(type, payload)), Optional.empty());
        }

        public static MappingResult previewOnly(PreviewFrame frame) {
            return new MappingResult(Optional.empty(), Optional.of(frame));
        }

        public static MappingResult both(PersistedEvent persisted, PreviewFrame preview) {
            return new MappingResult(Optional.of(persisted), Optional.of(preview));
        }
    }

    public record PersistedEvent(String type, Map<String, Object> payload) {}

    /** Stream-only preview frame ({@code event_start} / {@code event_delta}). */
    public record PreviewFrame(
            String streamType, String targetType, String eventId, String delta) {}

    /**
     * Maps a harness event. Text/thinking/tool deltas produce preview frames only; complete
     * messages and tool boundaries produce persisted events.
     */
    public MappingResult map(AgentEvent event, PreviewIds previewIds) {
        if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() == null || delta.getDelta().isEmpty()) {
                return MappingResult.empty();
            }
            String eventId = previewIds.messageEventId();
            return MappingResult.previewOnly(
                    new PreviewFrame(
                            SessionEventTypes.EVENT_DELTA,
                            SessionEventTypes.AGENT_MESSAGE,
                            eventId,
                            delta.getDelta()));
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            if (thinking.getDelta() == null || thinking.getDelta().isEmpty()) {
                return MappingResult.empty();
            }
            String eventId = previewIds.thinkingEventId();
            return MappingResult.previewOnly(
                    new PreviewFrame(
                            SessionEventTypes.EVENT_DELTA,
                            SessionEventTypes.AGENT_THINKING,
                            eventId,
                            thinking.getDelta()));
        }
        if (event instanceof AgentResultEvent result) {
            String text =
                    result.getResult() != null && result.getResult().getTextContent() != null
                            ? result.getResult().getTextContent()
                            : "";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            payload.put("content", List.of(Map.of("type", "text", "text", text)));
            return MappingResult.persist(SessionEventTypes.AGENT_MESSAGE, payload);
        }
        if (event instanceof ToolCallStartEvent toolUse) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", toolUse.getToolCallId());
            payload.put("name", toolUse.getToolCallName());
            payload.put("input", Map.of());
            // Compat aliases used by current ChatPanel.
            payload.put("toolCallId", toolUse.getToolCallId());
            payload.put("toolName", toolUse.getToolCallName());
            return MappingResult.persist(SessionEventTypes.AGENT_TOOL_USE, payload);
        }
        if (event instanceof ToolCallDeltaEvent toolDelta) {
            if (toolDelta.getDelta() == null || toolDelta.getDelta().isEmpty()) {
                return MappingResult.empty();
            }
            String eventId = previewIds.toolUseEventId(toolDelta.getToolCallId());
            return MappingResult.previewOnly(
                    new PreviewFrame(
                            SessionEventTypes.EVENT_DELTA,
                            SessionEventTypes.AGENT_TOOL_USE,
                            eventId,
                            toolDelta.getDelta()));
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool_use_id", toolResult.getToolCallId());
            payload.put("id", toolResult.getToolCallId());
            payload.put("name", toolResult.getToolCallName());
            payload.put("toolCallId", toolResult.getToolCallId());
            payload.put("toolName", toolResult.getToolCallName());
            if (toolResult.getState() != null) {
                payload.put("state", toolResult.getState().name());
            }
            return MappingResult.persist(SessionEventTypes.AGENT_TOOL_RESULT, payload);
        }
        if (event instanceof ModelCallStartEvent) {
            return MappingResult.persist(SessionEventTypes.SPAN_MODEL_REQUEST_START, Map.of());
        }
        if (event instanceof ModelCallEndEvent modelEnd) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (modelEnd.getUsage() != null) {
                payload.put("usage", modelEnd.getUsage());
            }
            return MappingResult.persist(SessionEventTypes.SPAN_MODEL_REQUEST_END, payload);
        }
        if (event instanceof AgentStartEvent || event instanceof AgentEndEvent) {
            // Folded into session.status_*; do not emit separate agent_start/end aliases.
            return MappingResult.empty();
        }
        return MappingResult.empty();
    }

    /** Allocates stable preview event ids for a turn. */
    public static final class PreviewIds {
        private String messageId;
        private String thinkingId;
        private final Map<String, String> toolUseIds = new LinkedHashMap<>();

        public String messageEventId() {
            if (messageId == null) {
                messageId = "evt_preview_msg_" + System.nanoTime();
            }
            return messageId;
        }

        public String thinkingEventId() {
            if (thinkingId == null) {
                thinkingId = "evt_preview_think_" + System.nanoTime();
            }
            return thinkingId;
        }

        public String toolUseEventId(String toolCallId) {
            return toolUseIds.computeIfAbsent(
                    toolCallId == null ? "_" : toolCallId,
                    id -> "evt_preview_tool_" + id + "_" + System.nanoTime());
        }

        public void resetMessage() {
            messageId = null;
        }

        public void resetThinking() {
            thinkingId = null;
        }
    }
}
