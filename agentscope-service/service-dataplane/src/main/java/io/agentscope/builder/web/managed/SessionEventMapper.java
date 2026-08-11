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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Maps harness {@link AgentEvent}s onto persisted session event types / payloads and stream-only
 * preview frames.
 *
 * <p>Streaming token/arg deltas are never persisted as rows. Tool call input and tool result bodies
 * are accumulated across delta events and persisted on End. Preview {@code event_id} values are
 * stable {@code evt_*} ids reused when the matching buffered event is appended, so clients can
 * reconcile typewriter previews with the authoritative record.
 */
@Component
public class SessionEventMapper {

    private static final int MAX_TOOL_PAYLOAD_CHARS = 64 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public SessionEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Outcome of mapping one harness event. */
    public record MappingResult(
            Optional<PersistedEvent> persisted, Optional<PreviewFrame> preview) {

        public static MappingResult empty() {
            return new MappingResult(Optional.empty(), Optional.empty());
        }

        public static MappingResult persist(PersistedEvent event) {
            return new MappingResult(Optional.of(event), Optional.empty());
        }

        public static MappingResult persist(String type, Map<String, Object> payload) {
            return persist(new PersistedEvent(type, payload, null));
        }

        public static MappingResult persist(
                String type, Map<String, Object> payload, String eventId) {
            return persist(new PersistedEvent(type, payload, eventId));
        }

        public static MappingResult previewOnly(PreviewFrame frame) {
            return new MappingResult(Optional.empty(), Optional.of(frame));
        }

        public static MappingResult both(PersistedEvent persisted, PreviewFrame preview) {
            return new MappingResult(Optional.of(persisted), Optional.of(preview));
        }
    }

    /**
     * Persisted event. When {@code eventId} is non-null, {@link
     * io.agentscope.builder.web.managed.service.SessionEventLog} must reuse it so previews reconcile.
     */
    public record PersistedEvent(String type, Map<String, Object> payload, String eventId) {
        public PersistedEvent(String type, Map<String, Object> payload) {
            this(type, payload, null);
        }
    }

    /**
     * Stream-only preview frame ({@code event_start} / {@code event_delta}).
     *
     * <p>When {@code delta} is null, callers should emit start only (no delta frame).
     */
    public record PreviewFrame(
            String streamType, String targetType, String eventId, String delta) {}

    /**
     * Maps a harness event. Text/thinking/tool deltas produce preview frames only; complete
     * messages and tool End boundaries produce persisted events with full payloads.
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
            // Reuse the preview id when deltas already streamed for this message.
            String eventId = previewIds.consumeMessageEventId();
            return MappingResult.persist(SessionEventTypes.AGENT_MESSAGE, payload, eventId);
        }
        if (event instanceof ToolCallStartEvent toolUse) {
            previewIds.beginToolUse(toolUse.getToolCallId(), toolUse.getToolCallName());
            String eventId = previewIds.toolUseEventId(toolUse.getToolCallId());
            // Start announces the upcoming tool_use; args arrive via deltas and persist on End.
            return MappingResult.previewOnly(
                    new PreviewFrame(
                            SessionEventTypes.EVENT_START,
                            SessionEventTypes.AGENT_TOOL_USE,
                            eventId,
                            null));
        }
        if (event instanceof ToolCallDeltaEvent toolDelta) {
            if (toolDelta.getDelta() == null || toolDelta.getDelta().isEmpty()) {
                return MappingResult.empty();
            }
            previewIds.appendToolInput(
                    toolDelta.getToolCallId(), toolDelta.getToolCallName(), toolDelta.getDelta());
            String eventId = previewIds.toolUseEventId(toolDelta.getToolCallId());
            return MappingResult.previewOnly(
                    new PreviewFrame(
                            SessionEventTypes.EVENT_DELTA,
                            SessionEventTypes.AGENT_TOOL_USE,
                            eventId,
                            toolDelta.getDelta()));
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            ToolBuffers.ToolUseBuffer buf =
                    previewIds.finishToolUse(toolEnd.getToolCallId(), toolEnd.getToolCallName());
            Map<String, Object> input = parseToolInput(buf.inputJson());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", toolEnd.getToolCallId());
            payload.put("name", toolEnd.getToolCallName());
            payload.put("input", input);
            payload.put("toolCallId", toolEnd.getToolCallId());
            payload.put("toolName", toolEnd.getToolCallName());
            if (buf.truncated()) {
                payload.put("truncated", true);
                payload.put("originalSize", buf.originalInputSize());
            }
            return MappingResult.persist(SessionEventTypes.AGENT_TOOL_USE, payload, buf.eventId());
        }
        if (event instanceof ToolResultTextDeltaEvent textDelta) {
            if (textDelta.getDelta() == null || textDelta.getDelta().isEmpty()) {
                return MappingResult.empty();
            }
            previewIds.appendToolResultText(
                    textDelta.getToolCallId(), textDelta.getToolCallName(), textDelta.getDelta());
            return MappingResult.empty();
        }
        if (event instanceof ToolResultDataDeltaEvent dataDelta) {
            String fragment = stringifyContentBlock(dataDelta.getData());
            if (fragment == null || fragment.isEmpty()) {
                return MappingResult.empty();
            }
            previewIds.appendToolResultText(
                    dataDelta.getToolCallId(), dataDelta.getToolCallName(), fragment);
            return MappingResult.empty();
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            ToolBuffers.ToolResultBuffer buf =
                    previewIds.finishToolResult(
                            toolResult.getToolCallId(), toolResult.getToolCallName());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool_use_id", toolResult.getToolCallId());
            payload.put("id", toolResult.getToolCallId());
            payload.put("name", toolResult.getToolCallName());
            payload.put("toolCallId", toolResult.getToolCallId());
            payload.put("toolName", toolResult.getToolCallName());
            if (toolResult.getState() != null) {
                payload.put("state", toolResult.getState().name());
            }
            String output = buf.outputText();
            payload.put("output", output);
            payload.put("text", output);
            payload.put("content", List.of(Map.of("type", "text", "text", output)));
            if (buf.truncated()) {
                payload.put("truncated", true);
                payload.put("originalSize", buf.originalOutputSize());
            }
            return MappingResult.persist(
                    SessionEventTypes.AGENT_TOOL_RESULT, payload, buf.eventId());
        }
        if (event instanceof ModelCallStartEvent) {
            // Opening a model request opens a fresh preview window. The previous window must stay
            // readable until then: AgentResultEvent arrives only at the end of the turn and needs
            // the last window's id to reconcile with the streamed preview.
            previewIds.resetMessage();
            previewIds.resetThinking();
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
            return MappingResult.empty();
        }
        return MappingResult.empty();
    }

    private Map<String, Object> parseToolInput(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, MAP_TYPE);
            return parsed != null ? parsed : Map.of("_raw", raw);
        } catch (Exception ex) {
            return Map.of("_raw", raw);
        }
    }

    private static String stringifyContentBlock(ContentBlock block) {
        if (block == null) {
            return null;
        }
        if (block instanceof TextBlock text) {
            return text.getText();
        }
        return String.valueOf(block);
    }

    /** Allocates stable preview / persist event ids for a turn and accumulates tool buffers. */
    public static final class PreviewIds {
        private String messageId;
        private String thinkingId;
        private final Map<String, ToolBuffers.ToolUseBuffer> toolUses = new LinkedHashMap<>();
        private final Map<String, ToolBuffers.ToolResultBuffer> toolResults = new LinkedHashMap<>();

        public String messageEventId() {
            if (messageId == null) {
                messageId = newEventId();
            }
            return messageId;
        }

        /** Returns and clears the in-flight message preview id (null when no deltas streamed). */
        public String consumeMessageEventId() {
            String id = messageId;
            messageId = null;
            return id;
        }

        public String thinkingEventId() {
            if (thinkingId == null) {
                thinkingId = newEventId();
            }
            return thinkingId;
        }

        public String toolUseEventId(String toolCallId) {
            return beginToolUse(toolCallId, null).eventId();
        }

        public ToolBuffers.ToolUseBuffer beginToolUse(String toolCallId, String toolName) {
            String key = key(toolCallId);
            return toolUses.computeIfAbsent(
                    key, ignored -> new ToolBuffers.ToolUseBuffer(newEventId(), toolName));
        }

        public void appendToolInput(String toolCallId, String toolName, String delta) {
            ToolBuffers.ToolUseBuffer buf = beginToolUse(toolCallId, toolName);
            if (toolName != null) {
                buf.setToolName(toolName);
            }
            buf.appendInput(delta, MAX_TOOL_PAYLOAD_CHARS);
        }

        public ToolBuffers.ToolUseBuffer finishToolUse(String toolCallId, String toolName) {
            ToolBuffers.ToolUseBuffer buf = beginToolUse(toolCallId, toolName);
            if (toolName != null) {
                buf.setToolName(toolName);
            }
            toolUses.remove(key(toolCallId));
            return buf;
        }

        public void appendToolResultText(String toolCallId, String toolName, String delta) {
            ToolBuffers.ToolResultBuffer buf =
                    toolResults.computeIfAbsent(
                            key(toolCallId),
                            ignored -> new ToolBuffers.ToolResultBuffer(newEventId(), toolName));
            if (toolName != null) {
                buf.setToolName(toolName);
            }
            buf.appendOutput(delta, MAX_TOOL_PAYLOAD_CHARS);
        }

        public ToolBuffers.ToolResultBuffer finishToolResult(String toolCallId, String toolName) {
            ToolBuffers.ToolResultBuffer buf =
                    toolResults.computeIfAbsent(
                            key(toolCallId),
                            ignored -> new ToolBuffers.ToolResultBuffer(newEventId(), toolName));
            if (toolName != null) {
                buf.setToolName(toolName);
            }
            toolResults.remove(key(toolCallId));
            return buf;
        }

        public void resetMessage() {
            messageId = null;
        }

        public void resetThinking() {
            thinkingId = null;
        }

        private static String key(String toolCallId) {
            return toolCallId == null || toolCallId.isBlank() ? "_" : toolCallId;
        }

        private static String newEventId() {
            return "evt_" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    /** Mutable accumulation buffers for tool input / output within a turn. */
    static final class ToolBuffers {
        private ToolBuffers() {}

        static final class ToolUseBuffer {
            private final String eventId;
            private final StringBuilder input = new StringBuilder();
            private String toolName;
            private boolean truncated;
            private int originalInputSize;

            ToolUseBuffer(String eventId, String toolName) {
                this.eventId = eventId;
                this.toolName = toolName;
            }

            String eventId() {
                return eventId;
            }

            String inputJson() {
                return input.toString();
            }

            boolean truncated() {
                return truncated;
            }

            int originalInputSize() {
                return originalInputSize;
            }

            void setToolName(String toolName) {
                this.toolName = toolName;
            }

            void appendInput(String delta, int maxChars) {
                originalInputSize += delta.length();
                if (input.length() >= maxChars) {
                    truncated = true;
                    return;
                }
                int remaining = maxChars - input.length();
                if (delta.length() > remaining) {
                    input.append(delta, 0, remaining);
                    truncated = true;
                } else {
                    input.append(delta);
                }
            }
        }

        static final class ToolResultBuffer {
            private final String eventId;
            private final StringBuilder output = new StringBuilder();
            private String toolName;
            private boolean truncated;
            private int originalOutputSize;

            ToolResultBuffer(String eventId, String toolName) {
                this.eventId = eventId;
                this.toolName = toolName;
            }

            String eventId() {
                return eventId;
            }

            String outputText() {
                return output.toString();
            }

            boolean truncated() {
                return truncated;
            }

            int originalOutputSize() {
                return originalOutputSize;
            }

            void setToolName(String toolName) {
                this.toolName = toolName;
            }

            void appendOutput(String delta, int maxChars) {
                originalOutputSize += delta.length();
                if (output.length() >= maxChars) {
                    truncated = true;
                    return;
                }
                int remaining = maxChars - output.length();
                if (delta.length() > remaining) {
                    output.append(delta, 0, remaining);
                    truncated = true;
                } else {
                    output.append(delta);
                }
            }
        }
    }
}
