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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
}
