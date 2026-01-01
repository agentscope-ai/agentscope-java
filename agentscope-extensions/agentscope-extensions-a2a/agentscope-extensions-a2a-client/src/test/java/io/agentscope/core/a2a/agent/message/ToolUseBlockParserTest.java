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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.a2a.spec.DataPart;
import io.a2a.spec.Part;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolUseBlockParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing ToolUseBlock to DataPart</li>
 *   <li>Metadata correctness</li>
 * </ul>
 */
@DisplayName("ToolUseBlockParser Tests")
class ToolUseBlockParserTest {

    private ToolUseBlockParser parser;

    @BeforeEach
    void setUp() {
        parser = new ToolUseBlockParser();
    }

    @Test
    @DisplayName("Should parse ToolUseBlock to DataPart")
    void testParseToolUseBlock() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .name("calculator")
                        .id("123")
                        .input(Map.of("expression", "1+1"))
                        .build();

        Part<?> result = parser.parse(block);

        assertNotNull(result);
        assertEquals(DataPart.class, result.getClass());
        DataPart dataPart = (DataPart) result;
        assertEquals(Map.of("expression", "1+1"), dataPart.getData());
    }

    @Test
    @DisplayName("Should have correct metadata")
    void testMetadata() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .name("calculator")
                        .id("123")
                        .input(Map.of("expression", "1+1"))
                        .build();

        Part<?> result = parser.parse(block);

        DataPart dataPart = (DataPart) result;
        Map<String, Object> metadata = dataPart.getMetadata();
        assertNotNull(metadata);
        assertEquals("tool_use", metadata.get("_agentscope_block_type"));
        assertEquals("calculator", metadata.get("_agentscope_tool_name"));
        assertEquals("123", metadata.get("_agentscope_tool_call_id"));
    }

    @Test
    @DisplayName("Should include additional metadata")
    void testAdditionalMetadata() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .name("calculator")
                        .id("123")
                        .input(Map.of("expression", "1+1"))
                        .metadata(Map.of("custom_key", "custom_value"))
                        .build();

        Part<?> result = parser.parse(block);

        DataPart dataPart = (DataPart) result;
        Map<String, Object> metadata = dataPart.getMetadata();
        assertTrue(metadata.containsKey("custom_key"));
        assertEquals("custom_value", metadata.get("custom_key"));
    }
}
