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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolResultBlock Tests")
class ToolResultBlockTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── factory methods ──────────────────────────────────────────────────────

    @Test
    @DisplayName("text() factory should have null title")
    void testTextFactoryNullTitle() {
        ToolResultBlock block = ToolResultBlock.text("hello");
        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("error() factory should have null title")
    void testErrorFactoryNullTitle() {
        ToolResultBlock block = ToolResultBlock.error("bad");
        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("of(ContentBlock) factory should have null title")
    void testOfContentBlockNullTitle() {
        ToolResultBlock block = ToolResultBlock.of(TextBlock.builder().text("result").build());
        assertNull(block.getTitle());
    }

    @Test
    @DisplayName("of(id, name, title, ContentBlock) factory preserves title")
    void testOfIdNameTitleContentBlock() {
        ToolResultBlock block =
                ToolResultBlock.of(
                        "id1", "tool_name", "Tool Name", TextBlock.builder().text("r").build());
        assertEquals("id1", block.getId());
        assertEquals("tool_name", block.getName());
        assertEquals("Tool Name", block.getTitle());
        assertEquals(1, block.getOutput().size());
    }

    @Test
    @DisplayName("of(id, name, title, List) factory preserves title")
    void testOfIdNameTitleList() {
        List<ContentBlock> output =
                List.of(
                        TextBlock.builder().text("a").build(),
                        TextBlock.builder().text("b").build());
        ToolResultBlock block = ToolResultBlock.of("id2", "tool_name", "Tool Name", output);
        assertEquals("Tool Name", block.getTitle());
        assertEquals(2, block.getOutput().size());
    }

    @Test
    @DisplayName("of(id, name, title, ContentBlock, metadata) preserves title")
    void testOfIdNameTitleContentBlockMetadata() {
        ToolResultBlock block =
                ToolResultBlock.of(
                        "id3",
                        "tool_name",
                        "Tool Name",
                        TextBlock.builder().text("x").build(),
                        Map.of("key", "val"));
        assertEquals("Tool Name", block.getTitle());
        assertEquals("val", block.getMetadata().get("key"));
    }

    @Test
    @DisplayName("of(id, name, title, List, metadata) preserves title")
    void testOfIdNameTitleListMetadata() {
        ToolResultBlock block =
                ToolResultBlock.of(
                        "id4",
                        "tool_name",
                        "Tool Name",
                        List.of(TextBlock.builder().text("x").build()),
                        Map.of("k", "v"));
        assertEquals("Tool Name", block.getTitle());
    }

    // ── withIdAndNameAndTitle ─────────────────────────────────────────────────

    @Test
    @DisplayName("withIdAndNameAndTitle sets all three fields")
    void testWithIdAndNameAndTitle() {
        ToolResultBlock original = ToolResultBlock.text("result");
        ToolResultBlock updated = original.withIdAndNameAndTitle("id99", "my_tool", "My Tool");

        assertEquals("id99", updated.getId());
        assertEquals("my_tool", updated.getName());
        assertEquals("My Tool", updated.getTitle());
        assertEquals(original.getOutput(), updated.getOutput());
        assertEquals(original.getMetadata(), updated.getMetadata());
        assertEquals(original.getState(), updated.getState());
    }

    @Test
    @DisplayName("withIdAndName preserves existing title from original block")
    void testWithIdAndNamePreservesTitle() {
        ToolResultBlock original =
                ToolResultBlock.of(
                        "old_id", "old_name", "Old Title", TextBlock.builder().text("r").build());
        ToolResultBlock updated = original.withIdAndName("new_id", "new_name");

        assertEquals("new_id", updated.getId());
        assertEquals("new_name", updated.getName());
        assertEquals("Old Title", updated.getTitle());
    }

    // ── withState ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("withState preserves title")
    void testWithStatePreservesTitle() {
        ToolResultBlock original =
                ToolResultBlock.of(
                        "id1", "tool", "Tool Title", TextBlock.builder().text("r").build());
        ToolResultBlock updated = original.withState(ToolResultState.SUCCESS);

        assertEquals("Tool Title", updated.getTitle());
        assertEquals(ToolResultState.SUCCESS, updated.getState());
    }

    // ── builder ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Builder with title sets title correctly")
    void testBuilderWithTitle() {
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("bid")
                        .name("b_tool")
                        .title("Builder Tool")
                        .output(TextBlock.builder().text("output").build())
                        .build();

        assertEquals("bid", block.getId());
        assertEquals("b_tool", block.getName());
        assertEquals("Builder Tool", block.getTitle());
    }

    @Test
    @DisplayName("Builder without title has null title")
    void testBuilderWithoutTitle() {
        ToolResultBlock block =
                ToolResultBlock.builder()
                        .id("bid2")
                        .name("b_tool2")
                        .output(TextBlock.builder().text("output").build())
                        .build();

        assertNull(block.getTitle());
    }

    // ── constructor ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Constructor with id, name, title, List sets all fields")
    void testConstructorIdNameTitleList() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "cid",
                        "c_tool",
                        "C Tool",
                        List.of(TextBlock.builder().text("c").build()),
                        null);
        assertEquals("C Tool", block.getTitle());
        assertEquals("cid", block.getId());
    }

    @Test
    @DisplayName("Constructor with id, name (no title) has null title")
    void testConstructorIdNameNoTitle() {
        ToolResultBlock block =
                new ToolResultBlock(
                        "cid2", "c_tool2", List.of(TextBlock.builder().text("c").build()), null);
        assertNull(block.getTitle());
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Title is serialized to JSON")
    void testJsonSerializationIncludesTitle() throws JsonProcessingException {
        ToolResultBlock block =
                ToolResultBlock.of(
                        "id", "tool", "Tool Title", TextBlock.builder().text("r").build());
        String json = objectMapper.writeValueAsString(block);
        assertTrue(json.contains("\"title\":\"Tool Title\""), "JSON should contain title field");
    }

    @Test
    @DisplayName("Title is deserialized from JSON")
    void testJsonDeserializationReadsTitle() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_result",
                    "id": "id1",
                    "name": "my_tool",
                    "title": "My Tool",
                    "output": [{"type": "text", "text": "hello"}]
                }
                """;
        ToolResultBlock block = objectMapper.readValue(json, ToolResultBlock.class);
        assertEquals("My Tool", block.getTitle());
        assertEquals("id1", block.getId());
    }

    @Test
    @DisplayName("Missing title in JSON deserializes as null")
    void testJsonDeserializationMissingTitle() throws JsonProcessingException {
        String json =
                """
                {
                    "type": "tool_result",
                    "id": "id2",
                    "name": "my_tool",
                    "output": [{"type": "text", "text": "hello"}]
                }
                """;
        ToolResultBlock block = objectMapper.readValue(json, ToolResultBlock.class);
        assertNull(block.getTitle());
    }

    // ── suspended ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("suspended() carries title from ToolUseBlock")
    void testSuspendedCarriesTitle() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("uid")
                        .name("my_tool")
                        .title("My Tool")
                        .input(Map.of())
                        .build();
        ToolResultBlock block =
                ToolResultBlock.suspended(
                        toolUse, new io.agentscope.core.tool.ToolSuspendException("test"));
        assertEquals("My Tool", block.getTitle());
    }
}
