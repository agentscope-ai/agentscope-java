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
package io.agentscope.core.a2a.agent.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.a2a.spec.DataPart;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DataPartParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing DataPart without metadata to TextBlock</li>
 *   <li>Parsing DataPart with tool_use metadata to ToolUseBlock</li>
 *   <li>Parsing DataPart with tool_result metadata to ToolResultBlock</li>
 *   <li>Handling unsupported block types</li>
 *   <li>Parsing ToolResultBlock with List&lt;ContentBlock&gt; output</li>
 *   <li>Parsing ToolResultBlock with non-String and non-List output</li>
 * </ul>
 */
@DisplayName("DataPartParser Tests")
class DataPartParserTest {

    private DataPartParser parser;

    @BeforeEach
    void setUp() {
        parser = new DataPartParser();
    }

    @Test
    @DisplayName("Should parse DataPart without metadata to TextBlock")
    void testParseDataPartWithoutMetadata() {
        DataPart part = new DataPart(Map.of());

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(TextBlock.class, result.getClass());
    }

    @Test
    @DisplayName("Should parse DataPart with tool_use metadata to ToolUseBlock")
    void testParseDataPartWithToolUseMetadata() {
        Map<String, Object> metadata =
                Map.of(
                        "_agentscope_block_type", "tool_use",
                        "_agentscope_tool_name", "calculator",
                        "_agentscope_tool_call_id", "123");
        DataPart part = new DataPart(Map.of("expression", "1+1"), metadata);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(ToolUseBlock.class, result.getClass());
        ToolUseBlock toolUseBlock = (ToolUseBlock) result;
        assertEquals("calculator", toolUseBlock.getName());
        assertEquals("123", toolUseBlock.getId());
        assertEquals(Map.of("expression", "1+1"), toolUseBlock.getInput());
    }

    @Test
    @DisplayName("Should parse DataPart with tool_result metadata to ToolResultBlock")
    void testParseDataPartWithToolResultMetadata() {
        Map<String, Object> metadata =
                Map.of(
                        "_agentscope_block_type", "tool_result",
                        "_agentscope_tool_name", "calculator",
                        "_agentscope_tool_call_id", "123");
        Map<String, Object> data = Map.of("_agentscope_tool_output", "2");
        DataPart part = new DataPart(data, metadata);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(ToolResultBlock.class, result.getClass());
        ToolResultBlock toolResultBlock = (ToolResultBlock) result;
        assertEquals("calculator", toolResultBlock.getName());
        assertEquals("123", toolResultBlock.getId());
    }

    @Test
    @DisplayName("Should return null for unsupported block types")
    void testParseDataPartWithUnsupportedType() {
        Map<String, Object> metadata = Map.of("_agentscope_block_type", "unsupported_type");
        DataPart part = new DataPart(Map.of(), metadata);

        ContentBlock result = parser.parse(part);

        assertNull(result);
    }

    @Test
    @DisplayName("Should parse ToolResultBlock with List<ContentBlock> output")
    void testParseToolResultBlockWithListOutput() {
        // Prepare test data
        TextBlock textBlock = TextBlock.builder().text("test output").build();
        List<ContentBlock> outputList = List.of(textBlock);

        Map<String, Object> metadata =
                Map.of(
                        "_agentscope_block_type", "tool_result",
                        "_agentscope_tool_name", "calculator",
                        "_agentscope_tool_call_id", "123");
        Map<String, Object> data = Map.of("_agentscope_tool_output", outputList);
        DataPart part = new DataPart(data, metadata);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(ToolResultBlock.class, result.getClass());
        ToolResultBlock toolResultBlock = (ToolResultBlock) result;
        assertEquals("calculator", toolResultBlock.getName());
        assertEquals("123", toolResultBlock.getId());
        assertEquals(outputList, toolResultBlock.getOutput());
    }

    @Test
    @DisplayName("Should parse ToolResultBlock with non-String and non-List output")
    void testParseToolResultBlockWithNonStringNonListOutput() {
        // Prepare test data - using Integer as an example of non-String and non-List type
        Map<String, Object> metadata =
                Map.of(
                        "_agentscope_block_type", "tool_result",
                        "_agentscope_tool_name", "calculator",
                        "_agentscope_tool_call_id", "123");
        Map<String, Object> data = Map.of("_agentscope_tool_output", 12345);
        DataPart part = new DataPart(data, metadata);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(ToolResultBlock.class, result.getClass());
        ToolResultBlock toolResultBlock = (ToolResultBlock) result;
        assertEquals("calculator", toolResultBlock.getName());
        assertEquals("123", toolResultBlock.getId());
        // Should default to empty list when output is neither String nor List<ContentBlock>
        assertTrue(toolResultBlock.getOutput().isEmpty());
    }
}
