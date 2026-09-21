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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockEndEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
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
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Full-fidelity forwarding of remote subagent events: every {@link AgentEvent} survives the wire,
 * either through its dedicated wire type or as an {@link RemoteEventType#AGENT_EVENT} passthrough.
 */
class RemoteEventCodecPassthroughTest {

    /** One instance of every {@link AgentEvent} subclass registered on the base class. */
    private static Map<AgentEventType, AgentEvent> sampleEvents() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("read_file")
                        .input(Map.of("p", "a"))
                        .build();
        ToolResultBlock toolResult =
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("read_file")
                        .output(TextBlock.builder().text("contents").build())
                        .build();

        Map<AgentEventType, AgentEvent> events = new LinkedHashMap<>();
        events.put(AgentEventType.AGENT_START, new AgentStartEvent("sess", "reply", "researcher"));
        events.put(AgentEventType.AGENT_END, new AgentEndEvent("reply"));
        events.put(
                AgentEventType.AGENT_RESULT,
                new AgentResultEvent(
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build()));
        events.put(AgentEventType.MODEL_CALL_START, new ModelCallStartEvent("reply"));
        events.put(
                AgentEventType.MODEL_CALL_END,
                new ModelCallEndEvent("reply", new ChatUsage(120, 45, 12, 1.5)));
        events.put(AgentEventType.TEXT_BLOCK_START, new TextBlockStartEvent("reply", "b1"));
        events.put(AgentEventType.TEXT_BLOCK_DELTA, new TextBlockDeltaEvent("reply", "b1", "hi"));
        events.put(AgentEventType.TEXT_BLOCK_END, new TextBlockEndEvent("reply", "b1"));
        events.put(AgentEventType.THINKING_BLOCK_START, new ThinkingBlockStartEvent("reply", "b2"));
        events.put(
                AgentEventType.THINKING_BLOCK_DELTA,
                new ThinkingBlockDeltaEvent("reply", "b2", "hmm"));
        events.put(AgentEventType.THINKING_BLOCK_END, new ThinkingBlockEndEvent("reply", "b2"));
        events.put(AgentEventType.DATA_BLOCK_START, new DataBlockStartEvent("reply", "b3"));
        events.put(AgentEventType.DATA_BLOCK_DELTA, new DataBlockDeltaEvent("reply", "b3", "{}"));
        events.put(AgentEventType.DATA_BLOCK_END, new DataBlockEndEvent("reply", "b3"));
        events.put(
                AgentEventType.TOOL_CALL_START,
                new ToolCallStartEvent("reply", "call-1", "read_file"));
        events.put(
                AgentEventType.TOOL_CALL_DELTA,
                new ToolCallDeltaEvent("reply", "call-1", "read_file", "{\"p\":"));
        events.put(
                AgentEventType.TOOL_CALL_END, new ToolCallEndEvent("reply", "call-1", "read_file"));
        events.put(
                AgentEventType.TOOL_RESULT_START,
                new ToolResultStartEvent("reply", "call-1", "read_file"));
        events.put(
                AgentEventType.TOOL_RESULT_TEXT_DELTA,
                new ToolResultTextDeltaEvent("reply", "call-1", "read_file", "line one"));
        events.put(
                AgentEventType.TOOL_RESULT_DATA_DELTA,
                new ToolResultDataDeltaEvent(
                        "reply", "call-1", "read_file", TextBlock.builder().text("d").build()));
        events.put(
                AgentEventType.TOOL_RESULT_END,
                new ToolResultEndEvent("reply", "call-1", "read_file", ToolResultState.SUCCESS));
        events.put(AgentEventType.EXCEED_MAX_ITERS, new ExceedMaxItersEvent("reply", 10, 10));
        events.put(
                AgentEventType.REQUIRE_USER_CONFIRM,
                new RequireUserConfirmEvent("reply", List.of(toolUse)));
        events.put(
                AgentEventType.REQUIRE_EXTERNAL_EXECUTION,
                new RequireExternalExecutionEvent("reply", List.of(toolUse)));
        events.put(
                AgentEventType.USER_CONFIRM_RESULT,
                new UserConfirmResultEvent("reply", List.of(new ConfirmResult(true, toolUse))));
        events.put(
                AgentEventType.EXTERNAL_EXECUTION_RESULT,
                new ExternalExecutionResultEvent("reply", List.of(toolResult)));
        events.put(
                AgentEventType.REQUEST_STOP,
                new RequestStopEvent("stop", GenerateReason.ALL_TOOLS_DENIED));
        events.put(
                AgentEventType.SUBAGENT_EXPOSED,
                new SubagentExposedEvent("sub-1", "worker", "sess", "Worker"));
        events.put(
                AgentEventType.HINT_BLOCK,
                new HintBlockEvent("reply", "b4", "tool_output", "note"));
        events.put(AgentEventType.ALL_TOOLS_DENIED, new AllToolsDeniedEvent(List.of(toolUse)));
        events.put(AgentEventType.CUSTOM, new CustomEvent("my_event", Map.of("k", "v")));
        return events;
    }

    @Test
    void everyEventTypeSurvivesTheRoundTrip() {
        List<String> lost = new ArrayList<>();
        sampleEvents()
                .forEach(
                        (type, event) -> {
                            Optional<RemoteAgentEvent> dto = RemoteEventCodec.fromAgentEvent(event);
                            if (dto.isEmpty()) {
                                lost.add(type + " (not encoded)");
                                return;
                            }
                            Optional<AgentEvent> back = RemoteEventCodec.toAgentEvent(dto.get());
                            if (back.isEmpty()) {
                                lost.add(type + " (not decoded)");
                                return;
                            }
                            if (back.get().getType() != type) {
                                lost.add(type + " (decoded as " + back.get().getType() + ")");
                            }
                        });
        assertTrue(lost.isEmpty(), "events lost on the wire: " + lost);
    }

    @Test
    void everyEventTypeIsCoveredBySamples() {
        Set<AgentEventType> missing = EnumSet.allOf(AgentEventType.class);
        missing.removeAll(sampleEvents().keySet());
        assertTrue(missing.isEmpty(), "no sample for: " + missing);
    }

    @Test
    void passthroughPreservesIdentityAndPayloadFields() {
        ModelCallEndEvent original =
                new ModelCallEndEvent("reply", new ChatUsage(120, 45, 12, 1.5));
        original.withSource("parent/worker");
        original.withMetadataEntry("keep", "me");

        RemoteAgentEvent dto = RemoteEventCodec.fromAgentEvent(original).orElseThrow();
        assertEquals(RemoteEventType.AGENT_EVENT, dto.getType());
        assertEquals("MODEL_CALL_END", dto.getEventType());
        assertNotNull(dto.getPayload());

        ModelCallEndEvent back =
                assertInstanceOf(
                        ModelCallEndEvent.class, RemoteEventCodec.toAgentEvent(dto).orElseThrow());
        assertEquals(original.getId(), back.getId());
        assertEquals(original.getCreatedAt(), back.getCreatedAt());
        assertEquals("reply", back.getReplyId());
        assertEquals(120, back.getUsage().getInputTokens());
        assertEquals(12, back.getUsage().getCachedTokens());
        assertEquals("me", back.getMetadata().get("keep"));
    }

    @Test
    void typedEventsAlsoDecodeFromPayloadWithoutLosingBlockIds() {
        TextBlockDeltaEvent original = new TextBlockDeltaEvent("reply-7", "block-9", "hello");

        RemoteAgentEvent dto = RemoteEventCodec.fromAgentEvent(original).orElseThrow();
        assertEquals(
                RemoteEventType.TEXT_DELTA, dto.getType(), "typed wire type stays for old clients");
        assertEquals("hello", dto.getText());

        TextBlockDeltaEvent back =
                assertInstanceOf(
                        TextBlockDeltaEvent.class,
                        RemoteEventCodec.toAgentEvent(dto).orElseThrow());
        assertEquals("hello", back.getDelta());
        assertEquals("block-9", back.getBlockId());
        assertEquals(original.getId(), back.getId());
    }

    @Test
    void detailLevelsGateDeltasAndPassthrough() {
        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.RUN_STARTED, "status"));
        assertFalse(RemoteEventCodec.matchesDetail(RemoteEventType.TEXT_DELTA, "status"));
        assertFalse(RemoteEventCodec.matchesDetail(RemoteEventType.AGENT_EVENT, "status"));

        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.TEXT_DELTA, "full"));
        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.THINKING_DELTA, "full"));
        assertFalse(RemoteEventCodec.matchesDetail(RemoteEventType.AGENT_EVENT, "full"));

        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.AGENT_EVENT, "verbose"));
        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.TEXT_DELTA, "verbose"));

        assertFalse(RemoteEventCodec.matchesDetail((RemoteAgentEvent) null, "verbose"));
        assertFalse(
                RemoteEventCodec.matchesDetail(RemoteEventType.AGENT_EVENT, "unknown-level"),
                "an unrecognized level is treated as status");
    }

    @Test
    void eventFromAnOlderServerStillDecodesWithoutPayload() {
        RemoteAgentEvent legacy = new RemoteAgentEvent();
        legacy.setType(RemoteEventType.TOOL_CALL_START);
        legacy.setToolCallId("call-1");
        legacy.setToolName("read_file");
        legacy.setTaskId("task_legacy");

        ToolCallStartEvent back =
                assertInstanceOf(
                        ToolCallStartEvent.class,
                        RemoteEventCodec.toAgentEvent(legacy).orElseThrow());
        assertEquals("call-1", back.getToolCallId());
        assertEquals("read_file", back.getToolCallName());
        assertEquals("task_legacy", back.getMetadata().get(AgentEvent.METADATA_TASK_ID));
    }

    @Test
    void payloadDecodesEvenWhenTheWireTypeIsUnknownToThisClient() {
        RemoteAgentEvent dto =
                RemoteEventCodec.fromAgentEvent(new HintBlockEvent("reply", "b1", "src", "note"))
                        .orElseThrow();
        dto.setType(null); // an unknown future type deserializes to null on an older client
        dto.setTaskId("task_1");

        AgentEvent back = RemoteEventCodec.toAgentEvent(dto).orElseThrow();
        assertInstanceOf(HintBlockEvent.class, back);
        assertEquals("task_1", back.getMetadata().get(AgentEvent.METADATA_TASK_ID));
    }
}
