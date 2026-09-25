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

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts subagent-sourced {@link AgentEvent}s ({@code source != null}) into AG-UI {@code CUSTOM}
 * events under the {@code subagent.*} name namespace so they do not pollute the parent run lifecycle
 * or text stream.
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

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        // Not registered by type — invoked by AgentEventConverterRegistry when source != null.
        return Set.of();
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
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
