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
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.TextOutputDispositionEvent;
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
 * Converts subagent-sourced {@link AgentEvent}s (a non-blank {@code source} or task id) into AG-UI
 * {@code CUSTOM} events under the {@code subagent.*} name namespace so they do not pollute the
 * parent run lifecycle or text stream.
 */
final class SubagentEventConverter implements AgentEventConverter {

    static final String NAME_LIFECYCLE = "subagent.lifecycle";
    static final String NAME_TEXT = "subagent.text";
    static final String NAME_THINKING = "subagent.thinking";
    static final String NAME_TOOL_CALL = "subagent.tool_call";
    static final String NAME_TOOL_RESULT = "subagent.tool_result";
    static final String NAME_TEXT_DISPOSITION = "subagent.text_disposition";
    static final String NAME_RESULT = "subagent.result";
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
        if (event instanceof AgentStartEvent start) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sessionId", nullToEmpty(start.getSessionId()));
            extra.put("name", nullToEmpty(start.getName()));
            extra.put("role", nullToEmpty(start.getRole()));
            putIfPresent(extra, "replyId", start.getReplyId());
            context.emit(custom(context, NAME_LIFECYCLE, value(event, "AGENT_START", extra)));
            return;
        }
        if (event instanceof AgentEndEvent end) {
            Map<String, Object> extra = new LinkedHashMap<>();
            putIfPresent(extra, "replyId", end.getReplyId());
            putMetadataIfPresent(extra, event, AgentEndEvent.METADATA_INVOCATION_OUTCOME);
            context.emit(custom(context, NAME_LIFECYCLE, value(event, "AGENT_END", extra)));
            return;
        }
        if (event instanceof AgentResultEvent result) {
            Map<String, Object> extra = new LinkedHashMap<>();
            putIfPresent(extra, "result", result.getResult());
            context.emit(custom(context, NAME_RESULT, value(event, "AGENT_RESULT", extra)));
            return;
        }
        if (event instanceof TextOutputDispositionEvent disposition) {
            Map<String, Object> extra = new LinkedHashMap<>();
            putIfPresent(extra, "replyId", disposition.getReplyId());
            extra.put("disposition", disposition.getDisposition().name());
            if (disposition.getGenerateReason() != null) {
                extra.put("generateReason", disposition.getGenerateReason().name());
            }
            context.emit(
                    custom(
                            context,
                            NAME_TEXT_DISPOSITION,
                            value(event, "TEXT_OUTPUT_DISPOSITION", extra)));
            return;
        }
        if (event instanceof TextBlockStartEvent textStart) {
            context.emit(
                    custom(
                            context,
                            NAME_TEXT,
                            value(
                                    event,
                                    "TEXT_BLOCK_START",
                                    block(textStart.getReplyId(), textStart.getBlockId()))));
            return;
        }
        if (event instanceof TextBlockDeltaEvent text) {
            Map<String, Object> extra = block(text.getReplyId(), text.getBlockId());
            extra.put("delta", nullToEmpty(text.getDelta()));
            context.emit(custom(context, NAME_TEXT, value(event, "TEXT_BLOCK_DELTA", extra)));
            return;
        }
        if (event instanceof TextBlockEndEvent textEnd) {
            context.emit(
                    custom(
                            context,
                            NAME_TEXT,
                            value(
                                    event,
                                    "TEXT_BLOCK_END",
                                    block(textEnd.getReplyId(), textEnd.getBlockId()))));
            return;
        }
        if (event instanceof ThinkingBlockStartEvent thinkingStart) {
            context.emit(
                    custom(
                            context,
                            NAME_THINKING,
                            value(
                                    event,
                                    "THINKING_BLOCK_START",
                                    block(
                                            thinkingStart.getReplyId(),
                                            thinkingStart.getBlockId()))));
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            Map<String, Object> extra = block(thinking.getReplyId(), thinking.getBlockId());
            extra.put("delta", nullToEmpty(thinking.getDelta()));
            context.emit(
                    custom(context, NAME_THINKING, value(event, "THINKING_BLOCK_DELTA", extra)));
            return;
        }
        if (event instanceof ThinkingBlockEndEvent thinkingEnd) {
            context.emit(
                    custom(
                            context,
                            NAME_THINKING,
                            value(
                                    event,
                                    "THINKING_BLOCK_END",
                                    block(thinkingEnd.getReplyId(), thinkingEnd.getBlockId()))));
            return;
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            context.emit(
                    custom(
                            context,
                            NAME_TOOL_CALL,
                            value(
                                    event,
                                    "TOOL_CALL_START",
                                    tool(
                                            toolStart.getReplyId(),
                                            toolStart.getToolCallId(),
                                            toolStart.getToolCallName()))));
            return;
        }
        if (event instanceof ToolCallDeltaEvent toolDelta) {
            Map<String, Object> extra =
                    tool(
                            toolDelta.getReplyId(),
                            toolDelta.getToolCallId(),
                            toolDelta.getToolCallName());
            extra.put("argumentsDelta", nullToEmpty(toolDelta.getDelta()));
            context.emit(custom(context, NAME_TOOL_CALL, value(event, "TOOL_CALL_DELTA", extra)));
            return;
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            context.emit(
                    custom(
                            context,
                            NAME_TOOL_CALL,
                            value(
                                    event,
                                    "TOOL_CALL_END",
                                    tool(
                                            toolEnd.getReplyId(),
                                            toolEnd.getToolCallId(),
                                            toolEnd.getToolCallName()))));
            return;
        }
        if (event instanceof ToolResultStartEvent toolResultStart) {
            context.emit(
                    custom(
                            context,
                            NAME_TOOL_RESULT,
                            value(
                                    event,
                                    "TOOL_RESULT_START",
                                    tool(
                                            toolResultStart.getReplyId(),
                                            toolResultStart.getToolCallId(),
                                            toolResultStart.getToolCallName()))));
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent textDelta) {
            Map<String, Object> extra =
                    tool(
                            textDelta.getReplyId(),
                            textDelta.getToolCallId(),
                            textDelta.getToolCallName());
            extra.put("delta", nullToEmpty(textDelta.getDelta()));
            context.emit(
                    custom(
                            context,
                            NAME_TOOL_RESULT,
                            value(event, "TOOL_RESULT_TEXT_DELTA", extra)));
            return;
        }
        if (event instanceof ToolResultDataDeltaEvent dataDelta) {
            Map<String, Object> extra =
                    tool(
                            dataDelta.getReplyId(),
                            dataDelta.getToolCallId(),
                            dataDelta.getToolCallName());
            extra.put("data", dataDelta.getData());
            context.emit(
                    custom(
                            context,
                            NAME_TOOL_RESULT,
                            value(event, "TOOL_RESULT_DATA_DELTA", extra)));
            return;
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            Map<String, Object> extra =
                    tool(
                            toolResult.getReplyId(),
                            toolResult.getToolCallId(),
                            toolResult.getToolCallName());
            if (toolResult.getState() != null) {
                extra.put("state", toolResult.getState().name());
            }
            context.emit(custom(context, NAME_TOOL_RESULT, value(event, "TOOL_RESULT_END", extra)));
            return;
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("toolCallCount", confirm.getToolCalls().size());
            context.emit(
                    custom(context, NAME_CONFIRM, value(event, "REQUIRE_USER_CONFIRM", extra)));
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
            AgentEvent event, String type, Map<String, Object> extra) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", event.getSource());
        putMetadataIfPresent(value, event, AgentEvent.METADATA_TASK_ID);
        value.put("type", type);
        if (extra != null) {
            value.putAll(extra);
        }
        return value;
    }

    private static Map<String, Object> block(String replyId, String blockId) {
        Map<String, Object> value = new LinkedHashMap<>();
        putIfPresent(value, "replyId", replyId);
        putIfPresent(value, "blockId", blockId);
        return value;
    }

    private static Map<String, Object> tool(String replyId, String toolCallId, String toolName) {
        Map<String, Object> value = new LinkedHashMap<>();
        putIfPresent(value, "replyId", replyId);
        value.put("toolCallId", nullToEmpty(toolCallId));
        value.put("toolName", nullToEmpty(toolName));
        return value;
    }

    private static void putMetadataIfPresent(
            Map<String, Object> target, AgentEvent event, String key) {
        if (event.getMetadata() != null) {
            putIfPresent(target, key, event.getMetadata().get(key));
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
