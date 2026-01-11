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
package io.agentscope.core.formatter.anthropic.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnthropicContent}.
 */
class AnthropicContentTest {

    @Test
    void testTextFactoryMethod() {
        AnthropicContent content = AnthropicContent.text("Hello, world!");

        assertEquals("text", content.getType());
        assertEquals("Hello, world!", content.getText());
        assertNull(content.getSource());
        assertNull(content.getId());
        assertNull(content.getName());
        assertNull(content.getInput());
        assertNull(content.getToolUseId());
        assertNull(content.getContent());
        assertNull(content.getIsError());
        assertNull(content.getThinking());
    }

    @Test
    void testImageFactoryMethod() {
        AnthropicContent content = AnthropicContent.image("image/png", "base64data");

        assertEquals("image", content.getType());
        assertNull(content.getText());
        assertNotNull(content.getSource());
        assertEquals("base64", content.getSource().getType());
        assertEquals("image/png", content.getSource().getMediaType());
        assertEquals("base64data", content.getSource().getData());
        assertNull(content.getId());
        assertNull(content.getName());
        assertNull(content.getInput());
        assertNull(content.getToolUseId());
        assertNull(content.getContent());
        assertNull(content.getIsError());
        assertNull(content.getThinking());
    }

    @Test
    void testToolUseFactoryMethod() {
        Map<String, Object> input = Map.of("query", "test", "limit", 10);
        AnthropicContent content = AnthropicContent.toolUse("tool_123", "search", input);

        assertEquals("tool_use", content.getType());
        assertNull(content.getText());
        assertNull(content.getSource());
        assertEquals("tool_123", content.getId());
        assertEquals("search", content.getName());
        assertEquals(input, content.getInput());
        assertNull(content.getToolUseId());
        assertNull(content.getContent());
        assertNull(content.getIsError());
        assertNull(content.getThinking());
    }

    @Test
    void testToolResultFactoryMethodWithStringContent() {
        String resultContent = "Search results";
        AnthropicContent content = AnthropicContent.toolResult("tool_123", resultContent, false);

        assertEquals("tool_result", content.getType());
        assertNull(content.getText());
        assertNull(content.getSource());
        assertNull(content.getId());
        assertNull(content.getName());
        assertNull(content.getInput());
        assertEquals("tool_123", content.getToolUseId());
        assertEquals(resultContent, content.getContent());
        assertEquals(false, content.getIsError());
        assertNull(content.getThinking());
    }

    @Test
    void testToolResultFactoryMethodWithArrayContent() {
        AnthropicContent[] contentArray =
                new AnthropicContent[] {
                    AnthropicContent.text("Result line 1"), AnthropicContent.text("Result line 2")
                };
        AnthropicContent content = AnthropicContent.toolResult("tool_123", contentArray, null);

        assertEquals("tool_result", content.getType());
        assertEquals("tool_123", content.getToolUseId());
        assertNotNull(content.getContent());
        assertNull(content.getIsError());
    }

    @Test
    void testToolResultFactoryMethodWithError() {
        String errorContent = "Tool execution failed";
        AnthropicContent content = AnthropicContent.toolResult("tool_123", errorContent, true);

        assertEquals("tool_result", content.getType());
        assertEquals("tool_123", content.getToolUseId());
        assertEquals(errorContent, content.getContent());
        assertEquals(true, content.getIsError());
    }

    @Test
    void testThinkingFactoryMethod() {
        AnthropicContent content = AnthropicContent.thinking("Let me think about this...");

        assertEquals("thinking", content.getType());
        assertNull(content.getText());
        assertNull(content.getSource());
        assertNull(content.getId());
        assertNull(content.getName());
        assertNull(content.getInput());
        assertNull(content.getToolUseId());
        assertNull(content.getContent());
        assertNull(content.getIsError());
        assertEquals("Let me think about this...", content.getThinking());
    }

    @Test
    void testSettersAndGetters() {
        AnthropicContent content = new AnthropicContent();

        content.setType("custom");
        assertEquals("custom", content.getType());

        content.setText("Custom text");
        assertEquals("Custom text", content.getText());

        AnthropicContent.ImageSource source =
                new AnthropicContent.ImageSource("image/jpeg", "data");
        content.setSource(source);
        assertEquals(source, content.getSource());

        content.setId("custom_id");
        assertEquals("custom_id", content.getId());

        content.setName("custom_name");
        assertEquals("custom_name", content.getName());

        Map<String, Object> input = Map.of("key", "value");
        content.setInput(input);
        assertEquals(input, content.getInput());

        content.setToolUseId("tool_use_id");
        assertEquals("tool_use_id", content.getToolUseId());

        Object contentObj = "content object";
        content.setContent(contentObj);
        assertEquals(contentObj, content.getContent());

        content.setIsError(true);
        assertEquals(true, content.getIsError());

        content.setThinking("thinking process");
        assertEquals("thinking process", content.getThinking());
    }

    @Test
    void testImageSourceDefaultConstructor() {
        AnthropicContent.ImageSource source = new AnthropicContent.ImageSource();

        assertEquals("base64", source.getType());
        assertNull(source.getMediaType());
        assertNull(source.getData());
    }

    @Test
    void testImageSourceParameterizedConstructor() {
        AnthropicContent.ImageSource source =
                new AnthropicContent.ImageSource("image/webp", "webpdata");

        assertEquals("base64", source.getType());
        assertEquals("image/webp", source.getMediaType());
        assertEquals("webpdata", source.getData());
    }

    @Test
    void testImageSourceSettersAndGetters() {
        AnthropicContent.ImageSource source = new AnthropicContent.ImageSource();

        source.setType("custom_type");
        assertEquals("custom_type", source.getType());

        source.setMediaType("image/gif");
        assertEquals("image/gif", source.getMediaType());

        source.setData("gifdata");
        assertEquals("gifdata", source.getData());
    }

    @Test
    void testEmptyContentConstruction() {
        AnthropicContent content = new AnthropicContent();

        assertNull(content.getType());
        assertNull(content.getText());
        assertNull(content.getSource());
        assertNull(content.getId());
        assertNull(content.getName());
        assertNull(content.getInput());
        assertNull(content.getToolUseId());
        assertNull(content.getContent());
        assertNull(content.getIsError());
        assertNull(content.getThinking());
    }

    @Test
    void testContentWithMultipleFieldsSet() {
        AnthropicContent content = AnthropicContent.text("Main text");

        // Additional setters to verify all fields can coexist
        content.setId("extra_id");
        content.setName("extra_name");

        assertEquals("text", content.getType());
        assertEquals("Main text", content.getText());
        assertEquals("extra_id", content.getId());
        assertEquals("extra_name", content.getName());
    }
}
