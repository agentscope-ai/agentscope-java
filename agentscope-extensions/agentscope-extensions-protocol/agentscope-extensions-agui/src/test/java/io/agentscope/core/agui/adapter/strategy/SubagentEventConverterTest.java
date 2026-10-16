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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.TextOutputDisposition;
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
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentEventConverterTest {

    @Test
    void parentEventsUnchanged() {
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
        AguiStreamContext context =
                new AguiStreamContext("t1", "r1", AguiAdapterConfig.defaultConfig());
        List<AguiEvent> events =
                registry.convert(new AgentStartEvent("sess", null, "main"), context);
        assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.RunStarted));
    }

    @Test
    void subagentEventsDowngradeToCustomByDefault() {
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
        AguiStreamContext context =
                new AguiStreamContext("t1", "r1", AguiAdapterConfig.defaultConfig());

        AgentStartEvent childStart = new AgentStartEvent("child-sess", "child-reply", "researcher");
        childStart.withSource("main/researcher");
        List<AguiEvent> startEvents = registry.convert(childStart, context);
        assertEquals(1, startEvents.size());
        AguiEvent.Custom custom = assertInstanceOf(AguiEvent.Custom.class, startEvents.get(0));
        assertEquals(SubagentEventConverter.NAME_LIFECYCLE, custom.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) custom.value();
        assertEquals("main/researcher", value.get("source"));
        assertEquals("AGENT_START", value.get("type"));
        assertEquals("child-reply", value.get("replyId"));

        AgentEndEvent childEnd = new AgentEndEvent("child-reply");
        childEnd.withSource("main/researcher");
        List<AguiEvent> endEvents = registry.convert(childEnd, context);
        AguiEvent.Custom endCustom = assertInstanceOf(AguiEvent.Custom.class, endEvents.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> endValue = (Map<String, Object>) endCustom.value();
        assertEquals("AGENT_END", endValue.get("type"));
        assertEquals("child-reply", endValue.get("replyId"));

        TextBlockDeltaEvent delta = new TextBlockDeltaEvent(null, "b1", "hi");
        delta.withSource("main/researcher");
        List<AguiEvent> textEvents = registry.convert(delta, context);
        assertEquals(1, textEvents.size());
        AguiEvent.Custom textCustom = assertInstanceOf(AguiEvent.Custom.class, textEvents.get(0));
        assertEquals(SubagentEventConverter.NAME_TEXT, textCustom.name());
    }

    @Test
    void taskIdOnlyEventsDowngradeToCustomByDefault() {
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
        AguiStreamContext context =
                new AguiStreamContext("t1", "r1", AguiAdapterConfig.defaultConfig());
        AgentEndEvent childEnd = new AgentEndEvent("child-reply");
        childEnd.withMetadataEntry(AgentEvent.METADATA_TASK_ID, "task-42");

        List<AguiEvent> events = registry.convert(childEnd, context);

        AguiEvent.Custom custom = assertInstanceOf(AguiEvent.Custom.class, events.get(0));
        assertEquals(SubagentEventConverter.NAME_LIFECYCLE, custom.name());
        assertEquals("AGENT_END", value(custom).get("type"));
        assertEquals("task-42", value(custom).get("taskId"));
    }

    @Test
    void completeSubagentSequencePreservesOrderIdentityAndStructuredPayloads() {
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
        AguiStreamContext context =
                new AguiStreamContext("thread-1", "run-1", AguiAdapterConfig.defaultConfig());
        Msg result =
                Msg.builder()
                        .id("child-result")
                        .role(MsgRole.ASSISTANT)
                        .textContent("final answer")
                        .generateReason(GenerateReason.MODEL_STOP)
                        .build();
        List<AgentEvent> sourceEvents =
                List.of(
                        new AgentStartEvent("child-session", "reply-1", "researcher"),
                        new ThinkingBlockStartEvent("reply-1", "thinking-1"),
                        new ThinkingBlockDeltaEvent("reply-1", "thinking-1", "analyzing"),
                        new ThinkingBlockEndEvent("reply-1", "thinking-1"),
                        new TextBlockStartEvent("reply-1", "text-1"),
                        new TextBlockDeltaEvent("reply-1", "text-1", "checking sources"),
                        new TextBlockEndEvent("reply-1", "text-1"),
                        new TextOutputDispositionEvent(
                                "reply-1", TextOutputDisposition.INTERMEDIATE, null),
                        new ToolCallStartEvent("reply-1", "tool-1", "search"),
                        new ToolCallStartEvent("reply-1", "tool-2", "lookup"),
                        new ToolCallDeltaEvent("reply-1", "tool-1", "search", "{\"q\":"),
                        new ToolCallDeltaEvent("reply-1", "tool-2", "lookup", "{\"id\":"),
                        new ToolCallEndEvent("reply-1", "tool-2", "lookup"),
                        new ToolCallEndEvent("reply-1", "tool-1", "search"),
                        new ToolResultStartEvent("reply-1", "tool-2", "lookup"),
                        new ToolResultStartEvent("reply-1", "tool-1", "search"),
                        new ToolResultTextDeltaEvent(
                                "reply-1", "tool-2", "lookup", "lookup result"),
                        new ToolResultEndEvent(
                                "reply-1", "tool-2", "lookup", ToolResultState.SUCCESS),
                        new ToolResultDataDeltaEvent(
                                "reply-1",
                                "tool-1",
                                "search",
                                TextBlock.builder().text("search data").build()),
                        new ToolResultEndEvent(
                                "reply-1", "tool-1", "search", ToolResultState.SUCCESS),
                        new TextBlockStartEvent("reply-2", "text-2"),
                        new TextBlockDeltaEvent("reply-2", "text-2", "final answer"),
                        new TextBlockEndEvent("reply-2", "text-2"),
                        new AgentResultEvent(result),
                        new TextOutputDispositionEvent(
                                "reply-2",
                                TextOutputDisposition.TERMINAL,
                                GenerateReason.MODEL_STOP),
                        new AgentEndEvent("reply-2"));

        List<AguiEvent> converted = new ArrayList<>();
        for (AgentEvent event : sourceEvents) {
            event.withSource("parent/researcher")
                    .withMetadataEntry(AgentEvent.METADATA_TASK_ID, "task-42");
            converted.addAll(registry.convert(event, context));
        }

        assertEquals(sourceEvents.size(), converted.size());
        List<AguiEvent.Custom> customEvents =
                converted.stream()
                        .map(event -> assertInstanceOf(AguiEvent.Custom.class, event))
                        .toList();
        assertEquals(
                List.of(
                        "subagent.lifecycle:AGENT_START",
                        "subagent.thinking:THINKING_BLOCK_START",
                        "subagent.thinking:THINKING_BLOCK_DELTA",
                        "subagent.thinking:THINKING_BLOCK_END",
                        "subagent.text:TEXT_BLOCK_START",
                        "subagent.text:TEXT_BLOCK_DELTA",
                        "subagent.text:TEXT_BLOCK_END",
                        "subagent.text_disposition:TEXT_OUTPUT_DISPOSITION",
                        "subagent.tool_call:TOOL_CALL_START",
                        "subagent.tool_call:TOOL_CALL_START",
                        "subagent.tool_call:TOOL_CALL_DELTA",
                        "subagent.tool_call:TOOL_CALL_DELTA",
                        "subagent.tool_call:TOOL_CALL_END",
                        "subagent.tool_call:TOOL_CALL_END",
                        "subagent.tool_result:TOOL_RESULT_START",
                        "subagent.tool_result:TOOL_RESULT_START",
                        "subagent.tool_result:TOOL_RESULT_TEXT_DELTA",
                        "subagent.tool_result:TOOL_RESULT_END",
                        "subagent.tool_result:TOOL_RESULT_DATA_DELTA",
                        "subagent.tool_result:TOOL_RESULT_END",
                        "subagent.text:TEXT_BLOCK_START",
                        "subagent.text:TEXT_BLOCK_DELTA",
                        "subagent.text:TEXT_BLOCK_END",
                        "subagent.result:AGENT_RESULT",
                        "subagent.text_disposition:TEXT_OUTPUT_DISPOSITION",
                        "subagent.lifecycle:AGENT_END"),
                customEvents.stream()
                        .map(event -> event.name() + ":" + value(event).get("type"))
                        .toList());
        assertTrue(
                customEvents.stream()
                        .allMatch(
                                event ->
                                        "parent/researcher".equals(value(event).get("source"))
                                                && "task-42".equals(value(event).get("taskId"))));

        Map<String, Object> toolArgs = value(customEvents.get(10));
        assertEquals("reply-1", toolArgs.get("replyId"));
        assertEquals("tool-1", toolArgs.get("toolCallId"));
        assertEquals("{\"q\":", toolArgs.get("argumentsDelta"));

        Map<String, Object> textResult = value(customEvents.get(16));
        assertEquals("lookup result", textResult.get("delta"));
        Map<String, Object> dataResult = value(customEvents.get(18));
        assertInstanceOf(TextBlock.class, dataResult.get("data"));

        Map<String, Object> agentResult = value(customEvents.get(23));
        assertEquals(result, agentResult.get("result"));
        Map<String, Object> terminal = value(customEvents.get(24));
        assertEquals("TERMINAL", terminal.get("disposition"));
        assertEquals("MODEL_STOP", terminal.get("generateReason"));
    }

    @Test
    void nullSubagentResultDoesNotInterruptEventConversion() {
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
        AguiStreamContext context =
                new AguiStreamContext("thread-1", "run-1", AguiAdapterConfig.defaultConfig());
        AgentResultEvent event = new AgentResultEvent(null);
        event.withSource("parent/researcher")
                .withMetadataEntry(AgentEvent.METADATA_TASK_ID, "task-42");

        List<AguiEvent> converted = registry.convert(event, context);

        AguiEvent.Custom custom = assertInstanceOf(AguiEvent.Custom.class, converted.get(0));
        assertEquals(SubagentEventConverter.NAME_RESULT, custom.name());
        assertEquals("AGENT_RESULT", value(custom).get("type"));
        assertEquals("parent/researcher", value(custom).get("source"));
        assertEquals("task-42", value(custom).get("taskId"));
        assertFalse(value(custom).containsKey("result"));
    }

    @Test
    void nativeModeKeepsLegacyBehavior() {
        AgentEventConverterRegistry registry =
                new AgentEventConverterRegistry(List.of(), List.of(), true);
        AguiStreamContext context =
                new AguiStreamContext(
                        "t1",
                        "r1",
                        AguiAdapterConfig.builder().emitSubagentEventsAsNative(true).build());

        AgentStartEvent childStart = new AgentStartEvent("child-sess", null, "researcher");
        childStart.withSource("main/researcher");
        List<AguiEvent> events = registry.convert(childStart, context);
        assertTrue(events.stream().anyMatch(e -> e instanceof AguiEvent.RunStarted));
    }

    @Test
    void configFlagDefaultsFalse() {
        assertTrue(!AguiAdapterConfig.defaultConfig().isEmitSubagentEventsAsNative());
        assertTrue(
                AguiAdapterConfig.builder()
                        .emitSubagentEventsAsNative(true)
                        .build()
                        .isEmitSubagentEventsAsNative());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(AguiEvent.Custom event) {
        return (Map<String, Object>) event.value();
    }
}
