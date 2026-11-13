/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.agui.AguiProtocol;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts subagent-sourced {@link AgentEvent}s ({@code source != null}) into AG-UI events.
 *
 * <p>Presentation is negotiated from {@code RunAgentInput.protocolVersion}:
 *
 * <ul>
 *   <li>Absent / pre-1.0.0 consumer → {@code CUSTOM} events under the {@code subagent.*} namespace
 *       (AgentScope legacy path; avoids unknown {@code SUBAGENT_*} types on old clients).
 *   <li>Consumer {@code >= 1.0.0} → native {@code SUBAGENT_*} lifecycle plus attributed content
 *       events ({@code TEXT_MESSAGE_*}, tool calls, reasoning) carrying {@code subagentRunId}.
 * </ul>
 *
 * <p>When {@code emitSubagentEventsAsNative=true}, the registry bypasses this converter entirely.
 */
final class SubagentEventConverter implements AgentEventConverter {

    static final String NAME_LIFECYCLE = "subagent.lifecycle";
    static final String NAME_TEXT = "subagent.text";
    static final String NAME_THINKING = "subagent.thinking";
    static final String NAME_TOOL_CALL = "subagent.tool_call";
    static final String NAME_TOOL_RESULT = "subagent.tool_result";
    static final String NAME_CONFIRM = "subagent.require_confirm";
    static final String NAME_OTHER = "subagent.event";

    private final RawAgentEventConverter rawFallback = new RawAgentEventConverter();
    private final TextBlockEventConverter textConverter = new TextBlockEventConverter();
    private final ThinkingBlockEventConverter thinkingConverter = new ThinkingBlockEventConverter();
    private final ToolCallEventConverter toolCallConverter = new ToolCallEventConverter();
    private final ToolResultEventConverter toolResultConverter = new ToolResultEventConverter();

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        // Not registered by type — invoked by AgentEventConverterRegistry when source != null.
        return Set.of();
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (supportsAttributed(context)) {
            convertAttributed(event, context);
        } else {
            convertCustom(event, context);
        }
    }

    private void convertAttributed(AgentEvent event, AguiStreamContext context) {
        String source = event.getSource();
        if (event instanceof AgentStartEvent start) {
            String subagentRunId = newSubagentRunId(start);
            String name = subagentName(start, source);
            String parentSubagentRunId = parentSubagentRunId(context, source);
            context.registerSubagent(source, subagentRunId, start.getReplyId());
            context.emit(
                    new AguiEvent.SubagentStarted(
                            context.getThreadId(),
                            context.getRunId(),
                            subagentRunId,
                            name,
                            null,
                            parentSubagentRunId,
                            null,
                            null,
                            null,
                            null,
                            null));
            return;
        }
        if (event instanceof AgentEndEvent end) {
            String subagentRunId = context.resolveSubagentRunId(end.getReplyId(), source);
            if (subagentRunId == null) {
                subagentRunId = fallbackSubagentRunId(source, end.getReplyId(), end.getId());
            }
            context.emit(
                    new AguiEvent.SubagentFinished(
                            context.getThreadId(), context.getRunId(), subagentRunId));
            context.unregisterSubagent(source, end.getReplyId());
            return;
        }

        String subagentRunId = ensureAttribution(context, source, event);
        context.setEventAttribution(subagentRunId);
        try {
            if (event instanceof TextBlockStartEvent
                    || event instanceof TextBlockDeltaEvent
                    || event instanceof TextBlockEndEvent) {
                textConverter.convert(event, context);
                return;
            }
            if (event instanceof ThinkingBlockStartEvent
                    || event instanceof ThinkingBlockDeltaEvent
                    || event instanceof ThinkingBlockEndEvent) {
                thinkingConverter.convert(event, context);
                return;
            }
            if (event instanceof ToolCallStartEvent
                    || event instanceof ToolCallDeltaEvent
                    || event instanceof ToolCallEndEvent) {
                toolCallConverter.convert(event, context);
                return;
            }
            if (event instanceof ToolResultStartEvent
                    || event instanceof ToolResultTextDeltaEvent
                    || event instanceof ToolResultDataDeltaEvent
                    || event instanceof ToolResultEndEvent) {
                toolResultConverter.convert(event, context);
                return;
            }
            if (event instanceof RequireUserConfirmEvent confirm) {
                // HITL from a subagent stays CUSTOM; stamp subagentRunId on the envelope.
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("toolCallCount", confirm.getToolCalls().size());
                context.emit(
                        new AguiEvent.Custom(
                                context.getThreadId(),
                                context.getRunId(),
                                NAME_CONFIRM,
                                value(source, "REQUIRE_USER_CONFIRM", extra),
                                null,
                                null,
                                null,
                                subagentRunId));
                return;
            }
            context.emit(
                    new AguiEvent.Raw(
                            context.getThreadId(),
                            context.getRunId(),
                            event,
                            source,
                            null,
                            null,
                            null,
                            subagentRunId));
        } finally {
            context.clearEventAttribution();
        }
    }

    private void convertCustom(AgentEvent event, AguiStreamContext context) {
        String source = event.getSource();
        if (event instanceof AgentStartEvent start) {
            context.emit(
                    custom(
                            context,
                            NAME_LIFECYCLE,
                            value(
                                    source,
                                    "AGENT_START",
                                    Map.of(
                                            "name", nullToEmpty(start.getName()),
                                            "replyId", nullToEmpty(start.getReplyId())))));
            return;
        }
        if (event instanceof AgentEndEvent end) {
            context.emit(
                    custom(
                            context,
                            NAME_LIFECYCLE,
                            value(
                                    source,
                                    "AGENT_END",
                                    Map.of("replyId", nullToEmpty(end.getReplyId())))));
            return;
        }
        if (event instanceof TextBlockDeltaEvent text) {
            context.emit(
                    custom(
                            context,
                            NAME_TEXT,
                            value(
                                    source,
                                    "TEXT_BLOCK_DELTA",
                                    Map.of("delta", nullToEmpty(text.getDelta())))));
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            context.emit(
                    custom(
                            context,
                            NAME_THINKING,
                            value(
                                    source,
                                    "THINKING_BLOCK_DELTA",
                                    Map.of("delta", nullToEmpty(thinking.getDelta())))));
            return;
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("toolCallId", nullToEmpty(toolStart.getToolCallId()));
            extra.put("toolName", nullToEmpty(toolStart.getToolCallName()));
            context.emit(custom(context, NAME_TOOL_CALL, value(source, "TOOL_CALL_START", extra)));
            return;
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("toolCallId", nullToEmpty(toolEnd.getToolCallId()));
            extra.put("toolName", nullToEmpty(toolEnd.getToolCallName()));
            context.emit(custom(context, NAME_TOOL_CALL, value(source, "TOOL_CALL_END", extra)));
            return;
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("toolCallId", nullToEmpty(toolResult.getToolCallId()));
            extra.put("toolName", nullToEmpty(toolResult.getToolCallName()));
            if (toolResult.getState() != null) {
                extra.put("state", toolResult.getState().name());
            }
            context.emit(
                    custom(context, NAME_TOOL_RESULT, value(source, "TOOL_RESULT_END", extra)));
            return;
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("toolCallCount", confirm.getToolCalls().size());
            context.emit(
                    custom(context, NAME_CONFIRM, value(source, "REQUIRE_USER_CONFIRM", extra)));
            return;
        }
        // Unknown typed events: prefer Raw (already carries source) over opaque custom.
        rawFallback.convert(event, context);
    }

    private static boolean supportsAttributed(AguiStreamContext context) {
        RunAgentInput input = context.getRunInput();
        String consumerVersion = input != null ? input.getProtocolVersion() : null;
        return AguiProtocol.supportsSubagentAttribution(consumerVersion);
    }

    /**
     * Ensures a subagentRunId for attribution-only streams (no prior SUBAGENT_STARTED). Official
     * clients must accept an id never announced.
     */
    private static String ensureAttribution(
            AguiStreamContext context, String source, AgentEvent event) {
        String existing = context.resolveSubagentRunId(source);
        if (existing != null) {
            return existing;
        }
        String minted = fallbackSubagentRunId(source, null, event.getId());
        context.registerSubagent(source, minted, null);
        return minted;
    }

    private static String newSubagentRunId(AgentStartEvent start) {
        if (start.getReplyId() != null && !start.getReplyId().isBlank()) {
            return start.getReplyId();
        }
        return start.getId();
    }

    private static String fallbackSubagentRunId(String source, String replyId, String eventId) {
        if (replyId != null && !replyId.isBlank()) {
            return replyId;
        }
        if (source != null && !source.isBlank()) {
            return source;
        }
        return eventId != null ? eventId : "subagent";
    }

    private static String subagentName(AgentStartEvent start, String source) {
        if (start.getName() != null && !start.getName().isBlank()) {
            return start.getName();
        }
        if (source == null || source.isBlank()) {
            return "subagent";
        }
        int slash = source.lastIndexOf('/');
        return slash >= 0 && slash < source.length() - 1 ? source.substring(slash + 1) : source;
    }

    /**
     * Derives {@code parentSubagentRunId} from a nested {@code source} path.
     *
     * <p>AgentScope tags forwarded events as {@code parentSession/…/agentId}. Dropping the last
     * segment yields the enclosing source; if that source was registered as a subagent, its
     * invocation id becomes {@code parentSubagentRunId}. A single-level path such as {@code
     * main/researcher} resolves to the session root (not a subagent), so nesting is absent —
     * matching AG-UI's "spawned by the parent agent directly".
     */
    private static String parentSubagentRunId(AguiStreamContext context, String source) {
        String parentSource = parentSourcePath(source);
        if (parentSource == null) {
            return null;
        }
        return context.resolveSubagentRunId(parentSource);
    }

    /** Returns the source path without the last {@code /segment}, or null if not nested. */
    static String parentSourcePath(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        int slash = source.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        return source.substring(0, slash);
    }

    private static AguiEvent.Custom custom(
            AguiStreamContext context, String name, Map<String, Object> value) {
        return new AguiEvent.Custom(context.getThreadId(), context.getRunId(), name, value);
    }

    private static Map<String, Object> value(
            String source, String type, Map<String, Object> extra) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", source);
        value.put("type", type);
        if (extra != null) {
            value.putAll(extra);
        }
        return value;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
