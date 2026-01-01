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

package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for ToolChoice support in StructuredOutputHandler (deferred forcing mode). */
class StructuredOutputHandlerToolChoiceTest {

    private StructuredOutputHandler handler;
    private Toolkit toolkit;
    private Memory memory;

    static class TestResponse {
        public String result;
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        memory = mock(Memory.class);
        when(memory.getMessages()).thenReturn(new ArrayList<>());

        handler =
                new StructuredOutputHandler(
                        TestResponse.class,
                        null,
                        toolkit,
                        memory,
                        "TestAgent",
                        StructuredOutputReminder.TOOL_CHOICE);
        handler.prepare();
    }

    // ==================== First Round: No Forcing ====================

    @Test
    void testCreateOptionsWithForcedToolReturnsNullOnFirstRound() {
        // First round should not force tool choice
        GenerateOptions options = handler.createOptionsWithForcedTool(null);
        assertNull(options);
    }

    @Test
    void testCreateOptionsWithForcedToolReturnsSameOptionsOnFirstRound() {
        GenerateOptions baseOptions = GenerateOptions.builder().temperature(0.7).build();

        // First round should return the same options without modification
        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        assertSame(baseOptions, options);
        assertNull(options.getToolChoice());
    }

    @Test
    void testCreateOptionsWithForcedToolPreservesExistingToolChoiceOnFirstRound() {
        GenerateOptions baseOptions =
                GenerateOptions.builder()
                        .temperature(0.5)
                        .toolChoice(new ToolChoice.Auto())
                        .build();

        // First round should preserve existing tool choice
        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        assertSame(baseOptions, options);
        assertTrue(options.getToolChoice() instanceof ToolChoice.Auto);
    }

    // ==================== After Retry: Forced Tool Choice ====================

    @Test
    void testNeedsRetrySetsForcedToolChoiceFlag() {
        // Mock memory with assistant message but no tool calls
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);

        // needsRetry should return true and set the flag
        assertTrue(handler.needsRetry());
    }

    @Test
    void testCreateOptionsWithForcedToolAfterRetry() {
        // Simulate first round failure (no tool calls)
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);

        // Trigger retry to set the flag
        assertTrue(handler.needsRetry());

        // Now createOptionsWithForcedTool should apply forced tool choice
        GenerateOptions options = handler.createOptionsWithForcedTool(null);

        assertNotNull(options);
        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolPreservesOtherOptionsAfterRetry() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        GenerateOptions baseOptions =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).topP(0.9).build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        assertNotNull(options);
        assertEquals(0.7, options.getTemperature());
        assertEquals(1000, options.getMaxTokens());
        assertEquals(0.9, options.getTopP());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolOverridesExistingToolChoiceAfterRetry() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        GenerateOptions baseOptions =
                GenerateOptions.builder()
                        .temperature(0.5)
                        .toolChoice(new ToolChoice.Auto())
                        .build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        assertNotNull(options);
        assertEquals(0.5, options.getTemperature());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolAlwaysUsesGenerateResponseAfterRetry() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        GenerateOptions baseOptions =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("other_tool")).build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        // Should override to generate_response
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolWithAllOptionsSetAfterRetry() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        GenerateOptions baseOptions =
                GenerateOptions.builder()
                        .temperature(0.6)
                        .topP(0.95)
                        .maxTokens(2000)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        // All options should be preserved
        assertEquals(0.6, options.getTemperature());
        assertEquals(0.95, options.getTopP());
        assertEquals(2000, options.getMaxTokens());
        assertEquals(0.5, options.getFrequencyPenalty());
        assertEquals(0.3, options.getPresencePenalty());

        // Plus the forced tool choice
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolReturnsNewInstanceAfterRetry() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        GenerateOptions baseOptions = GenerateOptions.builder().temperature(0.7).build();

        GenerateOptions options1 = handler.createOptionsWithForcedTool(baseOptions);
        GenerateOptions options2 = handler.createOptionsWithForcedTool(baseOptions);

        // Should be different instances
        assertNotSame(options1, options2);

        // But with same values
        assertEquals(options1.getTemperature(), options2.getTemperature());
        assertEquals(
                ((ToolChoice.Specific) options1.getToolChoice()).toolName(),
                ((ToolChoice.Specific) options2.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolDoesNotModifyBaseOptionsAfterRetry() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        GenerateOptions baseOptions =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .toolChoice(new ToolChoice.Auto())
                        .build();

        handler.createOptionsWithForcedTool(baseOptions);

        // Base options should remain unchanged
        assertEquals(0.7, baseOptions.getTemperature());
        assertTrue(baseOptions.getToolChoice() instanceof ToolChoice.Auto);
    }

    // ==================== Cleanup Resets State ====================

    @Test
    void testCleanupResetsForcedToolChoiceFlag() {
        // Simulate first round failure
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Some response").build())
                        .build());
        when(memory.getMessages()).thenReturn(messages);
        handler.needsRetry();

        // Verify flag is set
        GenerateOptions optionsBeforeCleanup = handler.createOptionsWithForcedTool(null);
        assertNotNull(optionsBeforeCleanup);

        // Cleanup
        handler.cleanup();

        // Re-prepare for next use
        handler =
                new StructuredOutputHandler(
                        TestResponse.class,
                        null,
                        toolkit,
                        memory,
                        "TestAgent",
                        StructuredOutputReminder.TOOL_CHOICE);
        when(memory.getMessages()).thenReturn(new ArrayList<>());
        handler.prepare();

        // After cleanup and re-prepare, should not force on first round
        GenerateOptions optionsAfterCleanup = handler.createOptionsWithForcedTool(null);
        assertNull(optionsAfterCleanup);
    }

    @Test
    void testTargetClassAndSchemaDescBothNull() {
        StructuredOutputHandler handler =
                new StructuredOutputHandler(
                        null,
                        null,
                        toolkit,
                        memory,
                        "TestAgent",
                        StructuredOutputReminder.TOOL_CHOICE);
        Assertions.assertThrowsExactly(
                IllegalStateException.class,
                handler::prepare,
                "Can not prepare,because targetClass and schemaDesc both not exists");
    }

    @Test
    void testTargetClassAndSchemaDescBothNotNull() throws JsonProcessingException {
        StructuredOutputHandler handler =
                new StructuredOutputHandler(
                        Object.class,
                        new ObjectMapper().readTree("{}"),
                        toolkit,
                        memory,
                        "TestAgent",
                        StructuredOutputReminder.TOOL_CHOICE);
        Assertions.assertThrowsExactly(
                IllegalStateException.class,
                handler::prepare,
                "Can not prepare,because targetClass and schemaDesc both exists");
    }
}
