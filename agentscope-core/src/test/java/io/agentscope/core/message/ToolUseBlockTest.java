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
        ToolUseBlock toolUseBlock = new ToolUseBlock("tool-999", "null-test", null, null, null);

        assertNotNull(toolUseBlock.getInput());
        assertTrue(toolUseBlock.getInput().isEmpty());
        assertNotNull(toolUseBlock.getMetadata());
        assertTrue(toolUseBlock.getMetadata().isEmpty());
        assertEquals(null, toolUseBlock.getContent());
    }
}
