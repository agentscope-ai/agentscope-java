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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.a2a.spec.TextPart;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TextPartParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing TextPart to TextBlock</li>
 *   <li>Parsing TextPart with thinking metadata to ThinkingBlock</li>
 * </ul>
 */
@DisplayName("TextPartParser Tests")
class TextPartParserTest {

    private TextPartParser parser;

    @BeforeEach
    void setUp() {
        parser = new TextPartParser();
    }

    @Test
    @DisplayName("Should parse TextPart to TextBlock")
    void testParseTextPart() {
        TextPart part = new TextPart("Hello world");

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(TextBlock.class, result.getClass());
        TextBlock textBlock = (TextBlock) result;
        assertEquals("Hello world", textBlock.getText());
    }

    @Test
    @DisplayName("Should parse TextPart with text metadata to TextBlock")
    void testParseTextPartWithTextMetadata() {
        Map<String, Object> metadata = Map.of("_agentscope_block_type", "text");
        TextPart part = new TextPart("Text process", metadata);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertInstanceOf(TextBlock.class, result);
        TextBlock thinkingBlock = (TextBlock) result;
        assertEquals("Text process", thinkingBlock.getText());
    }

    @Test
    @DisplayName("Should parse TextPart with thinking metadata to ThinkingBlock")
    void testParseTextPartWithThinkingMetadata() {
        Map<String, Object> metadata = Map.of("_agentscope_block_type", "thinking");
        TextPart part = new TextPart("Thinking process", metadata);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertInstanceOf(ThinkingBlock.class, result);
        ThinkingBlock thinkingBlock = (ThinkingBlock) result;
        assertEquals("Thinking process", thinkingBlock.getThinking());
    }
}
