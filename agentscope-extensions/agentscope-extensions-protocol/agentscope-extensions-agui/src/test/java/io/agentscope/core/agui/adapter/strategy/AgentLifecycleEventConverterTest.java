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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AgentLifecycleEventConverter} edge branches. */
class AgentLifecycleEventConverterTest {

    @Test
    void suspendedResultWithoutMatchingToolUseDoesNotEmitInterrupt() {
        Msg result =
                AssistantMessage.builder()
                        .id("reply-orphan")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("orphan-tool")
                                                .name("lookup")
                                                .output(TextBlock.builder().text("orphan").build())
                                                .metadata(
                                                        Map.of(
                                                                ToolResultBlock.METADATA_SUSPENDED,
                                                                true))
                                                .build()))
                        .generateReason(GenerateReason.TOOL_SUSPENDED)
                        .build();
        AguiStreamContext context =
                new AguiStreamContext("thread-1", "run-1", AguiAdapterConfig.defaultConfig());
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();

        registry.convert(new AgentResultEvent(result), context);
        List<AguiEvent> events = registry.convert(new AgentEndEvent("reply-orphan"), context);

        AguiEvent.RunFinished finished =
                events.stream()
                        .filter(AguiEvent.RunFinished.class::isInstance)
                        .map(AguiEvent.RunFinished.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertNull(finished.outcome());
    }

    @Test
    void nullRunInputStillInterruptsBackendTool() {
        AguiStreamContext context =
                new AguiStreamContext("thread-1", "run-1", AguiAdapterConfig.defaultConfig());
        convertSuspendedLookup(context);
        assertInterruptToolCallId(context, "tool-1");
    }

    @Test
    void nullToolsOnRunInputStillInterruptsBackendTool() {
        RunAgentInput input = mock(RunAgentInput.class);
        when(input.getTools()).thenReturn(null);
        AguiStreamContext context =
                new AguiStreamContext(
                        "thread-1", "run-1", AguiAdapterConfig.defaultConfig(), input);
        convertSuspendedLookup(context);
        assertInterruptToolCallId(context, "tool-1");
    }

    private static void convertSuspendedLookup(AguiStreamContext context) {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-1")
                        .name("lookup")
                        .input(Map.of("city", "Paris"))
                        .build();
        Msg result =
                AssistantMessage.builder()
                        .id("reply-suspended")
                        .content(
                                List.<ContentBlock>of(
                                        toolUse,
                                        ToolResultBlock.builder()
                                                .id("tool-1")
                                                .name("lookup")
                                                .output(
                                                        TextBlock.builder()
                                                                .text("lookup externally")
                                                                .build())
                                                .metadata(
                                                        Map.of(
                                                                ToolResultBlock.METADATA_SUSPENDED,
                                                                true))
                                                .build()))
                        .generateReason(GenerateReason.TOOL_SUSPENDED)
                        .build();
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
        registry.convert(new AgentResultEvent(result), context);
        registry.convert(new AgentEndEvent("reply-suspended"), context);
    }

    private static void assertInterruptToolCallId(AguiStreamContext context, String toolCallId) {
        List<AguiEvent.Interrupt> interrupts = context.getPendingInterrupts();
        assertEquals(1, interrupts.size());
        assertEquals(toolCallId, interrupts.get(0).toolCallId());
    }
}
