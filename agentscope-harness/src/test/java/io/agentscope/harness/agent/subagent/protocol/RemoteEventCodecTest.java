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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteEventCodecTest {

    @Test
    void roundTripTextAndLifecycle() {
        Optional<RemoteAgentEvent> startDto =
                RemoteEventCodec.fromAgentEvent(new AgentStartEvent("sess", null, "researcher"));
        assertTrue(startDto.isPresent());
        assertEquals(RemoteEventType.RUN_STARTED, startDto.get().getType());
        assertEquals("researcher", startDto.get().getAgentId());

        AgentEvent startBack = RemoteEventCodec.toAgentEvent(startDto.get()).orElseThrow();
        assertInstanceOf(AgentStartEvent.class, startBack);

        Optional<RemoteAgentEvent> textDto =
                RemoteEventCodec.fromAgentEvent(new TextBlockDeltaEvent(null, "b1", "hello"));
        assertTrue(textDto.isPresent());
        assertEquals(RemoteEventType.TEXT_DELTA, textDto.get().getType());
        assertEquals("hello", textDto.get().getText());

        AgentEvent textBack = RemoteEventCodec.toAgentEvent(textDto.get()).orElseThrow();
        assertInstanceOf(TextBlockDeltaEvent.class, textBack);
        assertEquals("hello", ((TextBlockDeltaEvent) textBack).getDelta());

        Optional<RemoteAgentEvent> endDto =
                RemoteEventCodec.fromAgentEvent(new AgentEndEvent(null));
        assertTrue(endDto.isPresent());
        assertEquals(RemoteEventType.RUN_FINISHED, endDto.get().getType());
    }

    @Test
    void toAgentEvent_copiesWireTaskIdIntoMetadata() {
        RemoteAgentEvent dto = new RemoteAgentEvent();
        dto.setType(RemoteEventType.TEXT_DELTA);
        dto.setText("hi");
        dto.setTaskId("task_from_wire");

        AgentEvent back = RemoteEventCodec.toAgentEvent(dto).orElseThrow();
        assertEquals("task_from_wire", back.getMetadata().get(AgentEvent.METADATA_TASK_ID));
    }

    @Test
    void requireConfirmRoundTripPreservesAskState() {
        ToolUseBlock ask =
                ToolUseBlock.builder()
                        .id("tc1")
                        .name("bash")
                        .input(Map.of("cmd", "rm -rf /"))
                        .state(ToolCallState.ASKING)
                        .build();
        Optional<RemoteAgentEvent> dto =
                RemoteEventCodec.fromAgentEvent(new RequireUserConfirmEvent(null, List.of(ask)));
        assertTrue(dto.isPresent());
        assertEquals(RemoteEventType.REQUIRE_CONFIRM, dto.get().getType());
        assertEquals(1, dto.get().getPendingConfirms().size());
        assertEquals("tc1", dto.get().getPendingConfirms().get(0).getToolCallId());

        AgentEvent back = RemoteEventCodec.toAgentEvent(dto.get()).orElseThrow();
        RequireUserConfirmEvent confirm = assertInstanceOf(RequireUserConfirmEvent.class, back);
        assertEquals(1, confirm.getToolCalls().size());
        assertEquals(ToolCallState.ASKING, confirm.getToolCalls().get(0).getState());
        assertEquals("bash", confirm.getToolCalls().get(0).getName());
    }

    @Test
    void toolCallStartMaps() {
        Optional<RemoteAgentEvent> dto =
                RemoteEventCodec.fromAgentEvent(new ToolCallStartEvent(null, "id1", "read_file"));
        assertTrue(dto.isPresent());
        assertEquals(RemoteEventType.TOOL_CALL_START, dto.get().getType());
        AgentEvent back = RemoteEventCodec.toAgentEvent(dto.get()).orElseThrow();
        ToolCallStartEvent start = assertInstanceOf(ToolCallStartEvent.class, back);
        assertEquals("id1", start.getToolCallId());
        assertEquals("read_file", start.getToolCallName());
    }

    @Test
    void detailFilterHidesTextUnlessFull() {
        assertFalse(RemoteEventCodec.matchesDetail(RemoteEventType.TEXT_DELTA, "status"));
        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.TEXT_DELTA, "full"));
        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.TOOL_CALL_START, "status"));
        assertTrue(RemoteEventCodec.matchesDetail(RemoteEventType.REQUIRE_CONFIRM, null));
    }

    @Test
    void unknownInternalEventsDropped() {
        // Custom/other events without a codec mapping
        assertTrue(RemoteEventCodec.fromAgentEvent(null).isEmpty());
    }
}
