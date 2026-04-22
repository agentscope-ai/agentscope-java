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

import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.agentscope.core.message.ThinkingBlock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ThinkingBlockParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing ThinkingBlock to TextPart</li>
 *   <li>Metadata correctness</li>
 * </ul>
 */
@DisplayName("ThinkingBlockParser Tests")
class ThinkingBlockParserTest {

    private ThinkingBlockParser parser;

    @BeforeEach
    void setUp() {
        parser = new ThinkingBlockParser();
    }

    @Test
    @DisplayName("Should parse ThinkingBlock to TextPart")
    void testParseThinkingBlock() {
        ThinkingBlock block = ThinkingBlock.builder().thinking("Thinking process").build();

        Part<?> result = parser.parse(block);

        assertNotNull(result);
        assertEquals(TextPart.class, result.getClass());
        TextPart textPart = (TextPart) result;
        assertEquals("Thinking process", textPart.getText());
    }

    @Test
    @DisplayName("Should have correct metadata")
    void testMetadata() {
        ThinkingBlock block = ThinkingBlock.builder().thinking("Thinking process").build();

        Part<?> result = parser.parse(block);

        TextPart textPart = (TextPart) result;
        Map<String, Object> metadata = textPart.getMetadata();
        assertNotNull(metadata);
        assertEquals("thinking", metadata.get("_agentscope_block_type"));
    }
}
