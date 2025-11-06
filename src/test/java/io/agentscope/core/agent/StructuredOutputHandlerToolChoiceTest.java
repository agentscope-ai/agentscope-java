/*
 * Copyright 2024-2025 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for ToolChoice support in StructuredOutputHandler. */
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

        handler =
                new StructuredOutputHandler(
                        TestResponse.class,
                        toolkit,
                        memory,
                        "TestAgent",
                        StructuredOutputReminder.TOOL_CHOICE);
        handler.prepare();
    }

    @Test
    void testCreateOptionsWithForcedToolWithNullBaseOptions() {
        GenerateOptions options = handler.createOptionsWithForcedTool(null);

        assertNotNull(options);
        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolWithEmptyBaseOptions() {
        GenerateOptions baseOptions = GenerateOptions.builder().build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        assertNotNull(options);
        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolPreservesOtherOptions() {
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
    void testCreateOptionsWithForcedToolOverridesExistingToolChoice() {
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
    void testCreateOptionsWithForcedToolMultipleTimes() {
        GenerateOptions baseOptions = GenerateOptions.builder().temperature(0.8).build();

        GenerateOptions options1 = handler.createOptionsWithForcedTool(baseOptions);
        GenerateOptions options2 = handler.createOptionsWithForcedTool(baseOptions);

        // Both should have the forced tool choice
        assertTrue(options1.getToolChoice() instanceof ToolChoice.Specific);
        assertTrue(options2.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options1.getToolChoice()).toolName());
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options2.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolAlwaysUsesGenerateResponse() {
        GenerateOptions baseOptions =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("other_tool")).build();

        GenerateOptions options = handler.createOptionsWithForcedTool(baseOptions);

        // Should override to generate_response
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(
                "generate_response", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testCreateOptionsWithForcedToolWithAllOptionsSet() {
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
    void testCreateOptionsWithForcedToolReturnsNewInstance() {
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
    void testCreateOptionsWithForcedToolDoesNotModifyBaseOptions() {
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
}
