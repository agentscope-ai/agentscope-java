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
package io.agentscope.core.agent.accumulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ReasoningContext ChatUsage accumulation.
 */
@DisplayName("ReasoningContext ChatUsage Tests")
class ReasoningContextTest {

    private ReasoningContext context;

    @BeforeEach
    void setUp() {
        context = new ReasoningContext("TestAgent");
    }

    @Test
    @DisplayName("Should accumulate ChatUsage from single chunk")
    void testSingleChunkUsage() {
        ChatUsage usage = ChatUsage.builder().inputTokens(100).outputTokens(50).time(1.5).build();

        ChatResponse chunk =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .usage(usage)
                        .build();

        context.processChunk(chunk);
        Msg msg = context.buildFinalMessage();

        assertNotNull(msg);
        ChatUsage resultUsage = msg.getChatUsage();
        assertNotNull(resultUsage);
        assertEquals(100, resultUsage.getInputTokens());
        assertEquals(50, resultUsage.getOutputTokens());
        assertEquals(150, resultUsage.getTotalTokens());
        assertEquals(1.5, resultUsage.getTime(), 0.001);
    }

    @Test
    @DisplayName("Should accumulate ChatUsage from multiple chunks")
    void testMultipleChunksUsageAccumulation() {
        // First chunk
        ChatUsage usage1 = ChatUsage.builder().inputTokens(100).outputTokens(20).time(0.5).build();

        ChatResponse chunk1 =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .usage(usage1)
                        .build();

        // Second chunk
        ChatUsage usage2 = ChatUsage.builder().inputTokens(100).outputTokens(50).time(0.8).build();

        ChatResponse chunk2 =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text(" world").build()))
                        .usage(usage2)
                        .build();

        // Third chunk
        ChatUsage usage3 = ChatUsage.builder().inputTokens(130).outputTokens(60).time(1.2).build();

        ChatResponse chunk3 =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text("!").build()))
                        .usage(usage3)
                        .build();

        context.processChunk(chunk1);
        context.processChunk(chunk2);
        context.processChunk(chunk3);

        Msg msg = context.buildFinalMessage();

        assertNotNull(msg);
        ChatUsage resultUsage = msg.getChatUsage();
        assertNotNull(resultUsage);
        assertEquals(130, resultUsage.getInputTokens());
        assertEquals(60, resultUsage.getOutputTokens());
        assertEquals(190, resultUsage.getTotalTokens());
        assertEquals(1.2, resultUsage.getTime(), 0.001);
    }

    @Test
    @DisplayName("Should handle chunks without usage")
    void testChunksWithoutUsage() {
        ChatResponse chunk =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        context.processChunk(chunk);
        Msg msg = context.buildFinalMessage();

        assertNotNull(msg);
        ChatUsage resultUsage = msg.getChatUsage();
        assertNull(resultUsage);
    }

    @Test
    @DisplayName("Should handle mixed chunks with and without usage")
    void testMixedChunksWithAndWithoutUsage() {
        // First chunk without usage
        ChatResponse chunk1 =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        // Second chunk with usage
        ChatUsage usage = ChatUsage.builder().inputTokens(100).outputTokens(50).time(1.0).build();

        ChatResponse chunk2 =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text(" world").build()))
                        .usage(usage)
                        .build();

        // Third chunk without usage
        ChatResponse chunk3 =
                ChatResponse.builder()
                        .id("msg-1")
                        .content(List.of(TextBlock.builder().text("!").build()))
                        .build();

        context.processChunk(chunk1);
        context.processChunk(chunk2);
        context.processChunk(chunk3);

        Msg msg = context.buildFinalMessage();

        assertNotNull(msg);
        ChatUsage resultUsage = msg.getChatUsage();
        assertNotNull(resultUsage);
        assertEquals(100, resultUsage.getInputTokens());
        assertEquals(50, resultUsage.getOutputTokens());
        assertEquals(1.0, resultUsage.getTime(), 0.001);
    }

    @Test
    @DisplayName("Should emit ToolUseBlock chunk immediately")
    void testToolUseBlockChunkEmittedImmediately() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .content("{\"city\":\"Beijing\"}")
                        .build();

        ChatResponse chunk =
                ChatResponse.builder().id("msg-1").content(List.of(toolUseBlock)).build();

        List<Msg> streamingMsgs = context.processChunk(chunk);

        // ToolUseBlock should be emitted immediately
        assertEquals(1, streamingMsgs.size());
        Msg msg = streamingMsgs.get(0);
        assertTrue(msg.hasContentBlocks(ToolUseBlock.class));
        ToolUseBlock emittedBlock = msg.getFirstContentBlock(ToolUseBlock.class);
        assertEquals("call_1", emittedBlock.getId());
        assertEquals("weather", emittedBlock.getName());
    }

    @Test
    @DisplayName("Should handle multiple parallel tool calls")
    void testMultipleParallelToolCalls() {
        // First tool call chunk
        ToolUseBlock toolUse1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .content("{\"city\":\"Beijing\"}")
                        .build();

        // Second tool call chunk
        ToolUseBlock toolUse2 =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .content("{\"expr\":\"1+1\"}")
                        .build();

        ChatResponse chunk1 = ChatResponse.builder().id("msg-1").content(List.of(toolUse1)).build();

        ChatResponse chunk2 = ChatResponse.builder().id("msg-1").content(List.of(toolUse2)).build();

        List<Msg> msgs1 = context.processChunk(chunk1);
        List<Msg> msgs2 = context.processChunk(chunk2);

        // Both chunks should be emitted immediately
        assertEquals(1, msgs1.size());
        assertEquals(1, msgs2.size());

        // Verify accumulated tool calls
        List<ToolUseBlock> allToolCalls = context.getAllAccumulatedToolCalls();
        assertEquals(2, allToolCalls.size());
    }

    @Test
    @DisplayName("Should get accumulated tool call by ID")
    void testGetAccumulatedToolCallById() {
        ToolUseBlock toolUse1 =
                ToolUseBlock.builder().id("call_1").name("weather").content("{\"city\":").build();

        ToolUseBlock toolUse1Fragment =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("__fragment__")
                        .content("\"Beijing\"}")
                        .build();

        ChatResponse chunk1 = ChatResponse.builder().id("msg-1").content(List.of(toolUse1)).build();

        ChatResponse chunk2 =
                ChatResponse.builder().id("msg-1").content(List.of(toolUse1Fragment)).build();

        context.processChunk(chunk1);
        context.processChunk(chunk2);

        // Get accumulated tool call by ID
        ToolUseBlock accumulated = context.getAccumulatedToolCall("call_1");
        assertNotNull(accumulated);
        assertEquals("call_1", accumulated.getId());
        assertEquals("weather", accumulated.getName());
        assertEquals("{\"city\":\"Beijing\"}", accumulated.getContent());
    }

    @Test
    @DisplayName("Should not block text emission while streaming tool calls")
    void testToolCallsDoNotBlockTextEmission() {
        // Text chunk
        TextBlock textBlock = TextBlock.builder().text("Let me check the weather").build();
        ChatResponse textChunk =
                ChatResponse.builder().id("msg-1").content(List.of(textBlock)).build();

        // Tool call chunk
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .content("{\"city\":\"Beijing\"}")
                        .build();
        ChatResponse toolChunk =
                ChatResponse.builder().id("msg-1").content(List.of(toolUseBlock)).build();

        // More text chunk
        TextBlock textBlock2 = TextBlock.builder().text(" for you.").build();
        ChatResponse textChunk2 =
                ChatResponse.builder().id("msg-1").content(List.of(textBlock2)).build();

        List<Msg> msgs1 = context.processChunk(textChunk);
        List<Msg> msgs2 = context.processChunk(toolChunk);
        List<Msg> msgs3 = context.processChunk(textChunk2);

        // All chunks should be emitted immediately
        assertEquals(1, msgs1.size());
        assertEquals(1, msgs2.size());
        assertEquals(1, msgs3.size());

        // Verify text is accumulated correctly
        assertEquals("Let me check the weather for you.", context.getAccumulatedText());
    }
}
