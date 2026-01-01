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

package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for ToolChoice support in GenerateOptions. */
class GenerateOptionsToolChoiceTest {

    @Test
    void testBuilderWithToolChoiceAuto() {
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Auto);
    }

    @Test
    void testBuilderWithToolChoiceNone() {
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();

        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.None);
    }

    @Test
    void testBuilderWithToolChoiceRequired() {
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Required()).build();

        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Required);
    }

    @Test
    void testBuilderWithToolChoiceSpecific() {
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("my_tool")).build();

        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals("my_tool", ((ToolChoice.Specific) options.getToolChoice()).toolName());
    }

    @Test
    void testBuilderWithNullToolChoice() {
        GenerateOptions options = GenerateOptions.builder().toolChoice(null).build();

        assertNull(options.getToolChoice());
    }

    @Test
    void testBuilderWithoutToolChoice() {
        GenerateOptions options = GenerateOptions.builder().build();

        assertNull(options.getToolChoice());
    }

    @Test
    void testBuilderCombineToolChoiceWithOtherOptions() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .maxTokens(1000)
                        .toolChoice(new ToolChoice.Specific("generate_response"))
                        .build();

        assertEquals(0.7, options.getTemperature());
        assertEquals(1000, options.getMaxTokens());
        assertNotNull(options.getToolChoice());
        assertTrue(options.getToolChoice() instanceof ToolChoice.Specific);
    }

    @Test
    void testMergeOptionsWithToolChoicePrimaryNotNull() {
        GenerateOptions primary =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .toolChoice(new ToolChoice.Specific("tool1"))
                        .build();

        GenerateOptions fallback =
                GenerateOptions.builder()
                        .temperature(0.5)
                        .toolChoice(new ToolChoice.Auto())
                        .build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        assertEquals(0.8, merged.getTemperature());
        assertTrue(merged.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals("tool1", ((ToolChoice.Specific) merged.getToolChoice()).toolName());
    }

    @Test
    void testMergeOptionsWithToolChoicePrimaryNull() {
        GenerateOptions primary = GenerateOptions.builder().temperature(0.8).build();

        GenerateOptions fallback =
                GenerateOptions.builder().toolChoice(new ToolChoice.Required()).build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        assertEquals(0.8, merged.getTemperature());
        assertTrue(merged.getToolChoice() instanceof ToolChoice.Required);
    }

    @Test
    void testMergeOptionsWithBothToolChoiceNull() {
        GenerateOptions primary = GenerateOptions.builder().temperature(0.8).build();

        GenerateOptions fallback = GenerateOptions.builder().maxTokens(500).build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        assertEquals(0.8, merged.getTemperature());
        assertEquals(500, merged.getMaxTokens());
        assertNull(merged.getToolChoice());
    }

    @Test
    void testMergeOptionsWithNullFallback() {
        GenerateOptions primary =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, null);

        assertTrue(merged.getToolChoice() instanceof ToolChoice.None);
    }

    @Test
    void testMergeOptionsWithNullPrimary() {
        GenerateOptions fallback =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        GenerateOptions merged = GenerateOptions.mergeOptions(null, fallback);

        assertTrue(merged.getToolChoice() instanceof ToolChoice.Auto);
    }

    @Test
    void testMergeOptionsOverrideToolChoiceType() {
        GenerateOptions primary =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("new_tool")).build();

        GenerateOptions fallback =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();

        GenerateOptions merged = GenerateOptions.mergeOptions(primary, fallback);

        // Primary should override fallback
        assertTrue(merged.getToolChoice() instanceof ToolChoice.Specific);
        assertEquals("new_tool", ((ToolChoice.Specific) merged.getToolChoice()).toolName());
    }
}
