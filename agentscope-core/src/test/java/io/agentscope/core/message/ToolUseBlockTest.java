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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolUseBlock} class, focusing on JSON serialization and deserialization
 * with Jackson.
 */
class ToolUseBlockTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testJsonSerializationWithAllFields() throws JsonProcessingException {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("tool-123")
                        .name("calculator")
                        .input(Map.of("x", 5, "y", 3, "operation", "add"))
                        .content("Raw streaming content")
                        .metadata(Map.of(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, "signature123"))
                        .build();

        String json = objectMapper.writeValueAsString(toolUseBlock);
        assertNotNull(json);
        assertTrue(json.contains("\"id\":\"tool-123\""));
        assertTrue(json.contains("\"name\":\"calculator\""));
        assertTrue(json.contains("\"content\":\"Raw streaming content\""));
    }

    @Test
    void testJsonDeserializationWithAllFields() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "tool-123",
                    "name": "calculator",
                    "input": {"x": 5, "y": 3, "operation": "add"},
                    "content": "Raw streaming content",
                    "metadata": {"thoughtSignature": "signature123", "key": "value"}
                }
                """;

        ToolUseBlock toolUseBlock = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals("tool-123", toolUseBlock.getId());
        assertEquals("calculator", toolUseBlock.getName());
        assertEquals(3, toolUseBlock.getInput().size());
        assertEquals(5, toolUseBlock.getInput().get("x"));
        assertEquals(3, toolUseBlock.getInput().get("y"));
        assertEquals("add", toolUseBlock.getInput().get("operation"));
        assertEquals("Raw streaming content", toolUseBlock.getContent());
        assertEquals(2, toolUseBlock.getMetadata().size());
        assertEquals("signature123", toolUseBlock.getMetadata().get("thoughtSignature"));
        assertEquals("value", toolUseBlock.getMetadata().get("key"));
    }

    @Test
    void testJsonDeserializationWithoutContent() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "tool-456",
                    "name": "search",
                    "input": {"query": "test search"},
                    "metadata": {"source": "web"}
                }
                """;

        ToolUseBlock toolUseBlock = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals("tool-456", toolUseBlock.getId());
        assertEquals("search", toolUseBlock.getName());
        assertEquals(1, toolUseBlock.getInput().size());
        assertEquals("test search", toolUseBlock.getInput().get("query"));
        assertEquals(null, toolUseBlock.getContent());
        assertEquals(1, toolUseBlock.getMetadata().size());
        assertEquals("web", toolUseBlock.getMetadata().get("source"));
    }

    @Test
    void testJsonDeserializationWithoutMetadata() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "tool-789",
                    "name": "validator",
                    "input": {"value": 100}
                }
                """;

        ToolUseBlock toolUseBlock = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals("tool-789", toolUseBlock.getId());
        assertEquals("validator", toolUseBlock.getName());
        assertEquals(1, toolUseBlock.getInput().size());
        assertEquals(100, toolUseBlock.getInput().get("value"));
        assertTrue(toolUseBlock.getMetadata().isEmpty());
    }

    @Test
    void testJsonDeserializationWithEmptyInput() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "tool-999",
                    "name": "no-input-tool",
                    "input": {}
                }
                """;

        ToolUseBlock toolUseBlock = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals("tool-999", toolUseBlock.getId());
        assertEquals("no-input-tool", toolUseBlock.getName());
        assertTrue(toolUseBlock.getInput().isEmpty());
    }

    @Test
    void testJsonDeserializationWithNullContent() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "tool-111",
                    "name": "test-tool",
                    "input": {"param": "value"},
                    "content": null
                }
                """;

        ToolUseBlock toolUseBlock = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals("tool-111", toolUseBlock.getId());
        assertEquals("test-tool", toolUseBlock.getName());
        assertEquals("value", toolUseBlock.getInput().get("param"));
        assertEquals(null, toolUseBlock.getContent());
    }

    @Test
    void testRoundTripSerialization() throws JsonProcessingException {
        ToolUseBlock original =
                ToolUseBlock.builder()
                        .id("tool-222")
                        .name("data_processor")
                        .input(Map.of("data", "sample", "format", "json"))
                        .content("Streaming data")
                        .metadata(Map.of("timestamp", "2024-01-01", "version", "1.0"))
                        .build();

        String json = objectMapper.writeValueAsString(original);
        ToolUseBlock deserialized = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getName(), deserialized.getName());
        assertEquals(original.getInput(), deserialized.getInput());
        assertEquals(original.getContent(), deserialized.getContent());
        assertEquals(original.getMetadata(), deserialized.getMetadata());
    }

    @Test
    void testInputMapIsUnmodifiable() {
        Map<String, Object> inputMap = Map.of("key1", "value1", "key2", "value2");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("tool-333").name("test").input(inputMap).build();

        // Verify input is unmodifiable
        try {
            toolUseBlock.getInput().put("key3", "value3");
            assertFalse(true, "Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(true);
        }
    }

    @Test
    void testMetadataMapIsUnmodifiable() {
        Map<String, Object> metadataMap = Map.of("meta1", "data1", "meta2", "data2");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("tool-444")
                        .name("test")
                        .input(Map.of())
                        .metadata(metadataMap)
                        .build();

        // Verify metadata is unmodifiable
        try {
            toolUseBlock.getMetadata().put("meta3", "data3");
            assertFalse(true, "Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(true);
        }
    }

    @Test
    void testConstructorWithThreeParameters() {
        ToolUseBlock toolUseBlock =
                new ToolUseBlock("tool-555", "simple-tool", Map.of("param", "value"));

        assertEquals("tool-555", toolUseBlock.getId());
        assertEquals("simple-tool", toolUseBlock.getName());
        assertEquals("value", toolUseBlock.getInput().get("param"));
        assertEquals(null, toolUseBlock.getContent());
        assertTrue(toolUseBlock.getMetadata().isEmpty());
    }

    @Test
    void testConstructorWithFourParameters() {
        ToolUseBlock toolUseBlock =
                new ToolUseBlock(
                        "tool-666",
                        "metadata-tool",
                        Map.of("key", "value"),
                        Map.of("metaKey", "metaValue"));

        assertEquals("tool-666", toolUseBlock.getId());
        assertEquals("metadata-tool", toolUseBlock.getName());
        assertEquals("value", toolUseBlock.getInput().get("key"));
        assertEquals("metaValue", toolUseBlock.getMetadata().get("metaKey"));
        assertEquals(null, toolUseBlock.getContent());
    }

    @Test
    void testConstructorWithAllFiveParameters() {
        ToolUseBlock toolUseBlock =
                new ToolUseBlock(
                        "tool-777",
                        "full-tool",
                        Map.of("inputKey", "inputValue"),
                        "content value",
                        Map.of("metaKey", "metaValue"));

        assertEquals("tool-777", toolUseBlock.getId());
        assertEquals("full-tool", toolUseBlock.getName());
        assertEquals("inputValue", toolUseBlock.getInput().get("inputKey"));
        assertEquals("content value", toolUseBlock.getContent());
        assertEquals("metaValue", toolUseBlock.getMetadata().get("metaKey"));
    }

    @Test
    void testBuilderPattern() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("tool-888")
                        .name("builder-test")
                        .input(Map.of("param1", "value1"))
                        .content("builder content")
                        .metadata(Map.of("meta1", "data1"))
                        .build();

        assertEquals("tool-888", toolUseBlock.getId());
        assertEquals("builder-test", toolUseBlock.getName());
        assertEquals("value1", toolUseBlock.getInput().get("param1"));
        assertEquals("builder content", toolUseBlock.getContent());
        assertEquals("data1", toolUseBlock.getMetadata().get("meta1"));
    }

    @Test
    void testEmptyMapsForNullInputAndMetadata() {
        ToolUseBlock toolUseBlock = new ToolUseBlock("tool-999", "null-test", "", null, null);

        assertNotNull(toolUseBlock.getInput());
        assertTrue(toolUseBlock.getInput().isEmpty());
        assertNotNull(toolUseBlock.getMetadata());
        assertTrue(toolUseBlock.getMetadata().isEmpty());
        assertEquals(null, toolUseBlock.getContent());
    }

    // ── title field tests ────────────────────────────────────────────────────

    @Test
    void testBuilderWithTitleSetsTitleCorrectly() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("t1")
                        .name("my_tool")
                        .title("My Tool")
                        .input(Map.of("k", "v"))
                        .build();
        assertEquals("My Tool", block.getTitle());
    }

    @Test
    void testBuilderWithoutTitleHasNullTitle() {
        ToolUseBlock block =
                ToolUseBlock.builder().id("t2").name("my_tool").input(Map.of()).build();
        assertNull(block.getTitle());
    }

    @Test
    void testConstructorWithTitleSetsTitle() {
        ToolUseBlock block = new ToolUseBlock("t3", "my_tool", "My Tool", Map.of("a", 1));
        assertEquals("My Tool", block.getTitle());
        assertEquals("my_tool", block.getName());
    }

    @Test
    void testConstructorWithTitleAndMetadataSetsTitle() {
        ToolUseBlock block =
                new ToolUseBlock("t4", "my_tool", "My Tool", Map.of(), Map.of("m", "v"));
        assertEquals("My Tool", block.getTitle());
        assertEquals("v", block.getMetadata().get("m"));
    }

    @Test
    void testConstructorWithTitleContentMetadataSetsTitle() {
        ToolUseBlock block =
                new ToolUseBlock("t5", "my_tool", "My Tool", Map.of(), "raw", Map.of());
        assertEquals("My Tool", block.getTitle());
        assertEquals("raw", block.getContent());
    }

    @Test
    void testWithTitleReturnsCopyWithNewTitle() {
        ToolUseBlock original =
                ToolUseBlock.builder().id("t6").name("my_tool").input(Map.of("x", 1)).build();
        ToolUseBlock updated = original.withTitle("Updated Title");

        assertNull(original.getTitle());
        assertEquals("Updated Title", updated.getTitle());
        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getName(), updated.getName());
        assertEquals(original.getInput(), updated.getInput());
    }

    @Test
    void testWithTitlePreservesAllOtherFields() {
        ToolUseBlock original =
                ToolUseBlock.builder()
                        .id("t7")
                        .name("tool")
                        .title("Old Title")
                        .input(Map.of("p", "v"))
                        .content("raw")
                        .metadata(Map.of("meta", "data"))
                        .build();
        ToolUseBlock updated = original.withTitle("New Title");

        assertEquals("New Title", updated.getTitle());
        assertEquals("t7", updated.getId());
        assertEquals("tool", updated.getName());
        assertEquals("v", updated.getInput().get("p"));
        assertEquals("raw", updated.getContent());
        assertEquals("data", updated.getMetadata().get("meta"));
    }

    @Test
    void testWithTitleAcceptsNull() {
        ToolUseBlock original =
                ToolUseBlock.builder()
                        .id("t8")
                        .name("tool")
                        .title("Some Title")
                        .input(Map.of())
                        .build();
        ToolUseBlock updated = original.withTitle(null);
        assertNull(updated.getTitle());
    }

    @Test
    void testWithStatePreservesTitle() {
        ToolUseBlock original =
                ToolUseBlock.builder().id("t9").name("tool").title("Title").input(Map.of()).build();
        ToolUseBlock updated = original.withState(ToolCallState.FINISHED);
        assertEquals("Title", updated.getTitle());
        assertEquals(ToolCallState.FINISHED, updated.getState());
    }

    @Test
    void testJsonSerializationIncludesTitle() throws JsonProcessingException {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("t10")
                        .name("my_tool")
                        .title("My Tool")
                        .input(Map.of())
                        .build();
        String json = objectMapper.writeValueAsString(block);
        assertTrue(json.contains("\"title\":\"My Tool\""), "JSON should contain title field");
    }

    @Test
    void testJsonDeserializationReadsTitle() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "t11",
                    "name": "my_tool",
                    "title": "My Tool",
                    "input": {}
                }
                """;
        ToolUseBlock block = objectMapper.readValue(json, ToolUseBlock.class);
        assertEquals("My Tool", block.getTitle());
    }

    @Test
    void testJsonDeserializationMissingTitleIsNull() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_use",
                    "id": "t12",
                    "name": "my_tool",
                    "input": {}
                }
                """;
        ToolUseBlock block = objectMapper.readValue(json, ToolUseBlock.class);
        assertNull(block.getTitle());
    }

    @Test
    void testRoundTripSerializationWithTitle() throws JsonProcessingException {
        ToolUseBlock original =
                ToolUseBlock.builder()
                        .id("t13")
                        .name("round_trip_tool")
                        .title("Round Trip Tool")
                        .input(Map.of("key", "value"))
                        .build();
        String json = objectMapper.writeValueAsString(original);
        ToolUseBlock deserialized = objectMapper.readValue(json, ToolUseBlock.class);

        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getName(), deserialized.getName());
        assertEquals(original.getTitle(), deserialized.getTitle());
        assertEquals(original.getInput(), deserialized.getInput());
    }
}
