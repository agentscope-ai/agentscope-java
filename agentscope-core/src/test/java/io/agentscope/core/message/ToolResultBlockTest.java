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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolResultBlock}, focusing on title field and related methods.
 */
@Tag("unit")
@DisplayName("ToolResultBlock Unit Tests")
class ToolResultBlockTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should create block with id, name, title via constructor")
    void testConstructorWithTitle() {
        ToolResultBlock block =
                new ToolResultBlock("call-1", "search_tool", "Search Tool", List.of(), null);

        assertEquals("call-1", block.getId());
        assertEquals("search_tool", block.getName());
        assertEquals("Search Tool", block.getTitle());
        assertTrue(block.getOutput().isEmpty());
        assertTrue(block.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should create block with title and metadata")
    void testConstructorWithTitleAndMetadata() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "call-2",
                        "calc_tool",
                        "Calculator",
                        List.of(TextBlock.builder().text("result: 42").build()),
                        Map.of("duration_ms", 150));

        assertEquals("call-2", block.getId());
        assertEquals("calc_tool", block.getName());
        assertEquals("Calculator", block.getTitle());
        assertEquals(1, block.getOutput().size());
        assertEquals("result: 42", ((TextBlock) block.getOutput().get(0)).getText());
        assertEquals(150, block.getMetadata().get("duration_ms"));
    }

    @Test
    @DisplayName("Should create block with title and single output")
    void testConstructorWithTitleAndSingleOutput() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "call-3",
                        "fetch_tool",
                        "Fetch Data",
                        TextBlock.builder().text("data").build());

        assertEquals("call-3", block.getId());
        assertEquals("fetch_tool", block.getName());
        assertEquals("Fetch Data", block.getTitle());
        assertEquals(1, block.getOutput().size());
        assertEquals("data", ((TextBlock) block.getOutput().get(0)).getText());
    }

    @Test
    @DisplayName("Should use RUNNING state by default")
    void testDefaultStateIsRunning() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "call-4", "tool", "Tool", TextBlock.builder().text("test").build());

        assertEquals(ToolResultState.RUNNING, block.getState());
    }

    @Test
    @DisplayName("Should propagate title through withIdAndNameAndTitle")
    void testWithIdAndNameAndTitle() {
        ToolResultBlock original =
                ToolResultBlock.of(
                        "result", "original_tool", TextBlock.builder().text("ok").build());

        ToolResultBlock updated = original.withIdAndNameAndTitle("new-id", "new_name", "New Title");

        assertEquals("new-id", updated.getId());
        assertEquals("new_name", updated.getName());
        assertEquals("New Title", updated.getTitle());
    }

    @Test
    @DisplayName("Should preserve output when using withIdAndNameAndTitle")
    void testWithIdAndNameAndTitlePreservesOutput() {
        ToolResultBlock original =
                ToolResultBlock.of(
                        "orig", "tool", List.of(TextBlock.builder().text("hello").build()));

        ToolResultBlock updated = original.withIdAndNameAndTitle("new-id", "new_name", "Title");

        assertEquals(1, updated.getOutput().size());
        assertEquals("hello", ((TextBlock) updated.getOutput().get(0)).getText());
        assertEquals(ToolResultState.RUNNING, updated.getState());
    }

    @Test
    @DisplayName("Should propagate title through withState")
    void testWithStatePreservesTitle() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "call-5", "tool", "My Title", TextBlock.builder().text("test").build());

        ToolResultBlock successBlock = block.withState(ToolResultState.SUCCESS);

        assertEquals("call-5", successBlock.getId());
        assertEquals("tool", successBlock.getName());
        assertEquals("My Title", successBlock.getTitle());
        assertEquals(ToolResultState.SUCCESS, successBlock.getState());
    }

    @Test
    @DisplayName("Should serialize and deserialize title via JSON")
    void testJsonSerializationWithTitle() throws JsonProcessingException {
        ToolResultBlock block =
                new ToolResultBlock(
                        "call-6",
                        "data_tool",
                        "Data Tool",
                        List.of(TextBlock.builder().text("output text").build()),
                        null);

        String json = objectMapper.writeValueAsString(block);
        assertTrue(json.contains("\"title\":\"Data Tool\""));
        assertTrue(json.contains("\"id\":\"call-6\""));
        assertTrue(json.contains("\"name\":\"data_tool\""));
    }

    @Test
    @DisplayName("Should deserialize title from JSON")
    void testJsonDeserializationWithTitle() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_result",
                    "id": "call-7",
                    "name": "search_tool",
                    "title": "Search Engine",
                    "output": [{"type": "text", "text": "results"}],
                    "state": "success"
                }
                """;

        ToolResultBlock block = objectMapper.readValue(json, ToolResultBlock.class);

        assertEquals("call-7", block.getId());
        assertEquals("search_tool", block.getName());
        assertEquals("Search Engine", block.getTitle());
        assertEquals(1, block.getOutput().size());
        assertEquals(ToolResultState.SUCCESS, block.getState());
    }

    @Test
    @DisplayName("Should handle null title in JSON")
    void testJsonDeserializationWithNullTitle() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_result",
                    "id": "call-8",
                    "name": "tool",
                    "title": null,
                    "output": [],
                    "state": "running"
                }
                """;

        ToolResultBlock block = objectMapper.readValue(json, ToolResultBlock.class);

        assertEquals("call-8", block.getId());
        assertEquals("tool", block.getName());
        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("Should handle missing title in JSON gracefully")
    void testJsonDeserializationWithoutTitle() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_result",
                    "id": "call-9",
                    "name": "tool",
                    "output": []
                }
                """;

        ToolResultBlock block = objectMapper.readValue(json, ToolResultBlock.class);

        assertEquals("call-9", block.getId());
        assertEquals("tool", block.getName());
        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("Should have immutable output list")
    void testOutputImmutability() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "id", "tool", "Title", TextBlock.builder().text("test").build());

        assertThrows(
                UnsupportedOperationException.class,
                () -> block.getOutput().add(TextBlock.builder().text("more").build()));
    }

    @Test
    @DisplayName("Builder should set title correctly")
    void testBuilderWithTitle() {
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("bid-1")
                        .name("b_tool")
                        .title("Builder Tool")
                        .output(TextBlock.builder().text("built").build())
                        .state(ToolResultState.SUCCESS)
                        .build();

        assertEquals("bid-1", block.getId());
        assertEquals("b_tool", block.getName());
        assertEquals("Builder Tool", block.getTitle());
        assertEquals("built", ((TextBlock) block.getOutput().get(0)).getText());
        assertEquals(ToolResultState.SUCCESS, block.getState());
    }

    @Test
    @DisplayName("Builder should handle null title")
    void testBuilderWithNullTitle() {
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("id")
                        .name("tool")
                        .output(TextBlock.builder().text("test").build())
                        .build();

        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("of() factory should not set title")
    void testOfFactoryDoesNotSetTitle() {
        ToolResultBlock block =
                ToolResultBlock.of("id", "tool", TextBlock.builder().text("result").build());

        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("of() factory with list should not set title")
    void testOfFactoryWithListDoesNotSetTitle() {
        ToolResultBlock block =
                ToolResultBlock.of(
                        "id", "tool", List.of(TextBlock.builder().text("result").build()));

        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("suspended() should create block with title from ToolUseBlock")
    void testSuspendedWithTitle() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("sid-1")
                        .name("external_tool")
                        .title("External Tool")
                        .input(Map.of("key", "value"))
                        .build();

        ToolResultBlock suspended = ToolResultBlock.suspended(toolUse);

        assertEquals("sid-1", suspended.getId());
        assertEquals("external_tool", suspended.getName());
        assertEquals("External Tool", suspended.getTitle());
        assertEquals(1, suspended.getOutput().size());
        assertEquals(
                "[Awaiting external execution]",
                ((TextBlock) suspended.getOutput().get(0)).getText());
        assertTrue(suspended.isSuspended());
    }
}
