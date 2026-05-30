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
package io.agentscope.core.llm.interfacesweb.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Anthropic DTO Tests")
class AnthropicDtoTest {

    @Test
    @DisplayName("Should expose Anthropic request accessors")
    void shouldExposeAnthropicRequestAccessors() {
        AnthropicMessage message = new AnthropicMessage();
        AnthropicTool tool = new AnthropicTool();
        AnthropicMessagesRequest request = new AnthropicMessagesRequest();
        Map<String, Object> toolChoice = Map.of("type", "auto");

        request.setModel("claude-test");
        request.setSystem("system prompt");
        request.setMessages(List.of(message));
        request.setTools(List.of(tool));
        request.setStream(true);
        request.setMaxTokens(128);
        request.setToolChoice(toolChoice);

        assertEquals("claude-test", request.getModel());
        assertEquals("system prompt", request.getSystem());
        assertEquals(List.of(message), request.getMessages());
        assertEquals(List.of(tool), request.getTools());
        assertEquals(true, request.getStream());
        assertEquals(128, request.getMaxTokens());
        assertEquals(toolChoice, request.getToolChoice());
    }

    @Test
    @DisplayName("Should expose Anthropic message and tool accessors")
    void shouldExposeAnthropicMessageAndToolAccessors() {
        AnthropicMessage message = new AnthropicMessage();
        AnthropicTool tool = new AnthropicTool();
        Map<String, Object> schema = Map.of("type", "object");

        message.setRole("user");
        message.setContent("hello");
        tool.setName("search");
        tool.setDescription("Search docs");
        tool.setInputSchema(schema);

        assertEquals("user", message.getRole());
        assertEquals("hello", message.getContent());
        assertEquals("search", tool.getName());
        assertEquals("Search docs", tool.getDescription());
        assertEquals(schema, tool.getInputSchema());
    }

    @Test
    @DisplayName("Should expose Anthropic response accessors")
    void shouldExposeAnthropicResponseAccessors() {
        AnthropicUsage usage = new AnthropicUsage(4, 6);
        List<Map<String, Object>> content = List.of(Map.of("type", "text", "text", "hello"));
        AnthropicMessagesResponse response = new AnthropicMessagesResponse();

        response.setId("msg_1");
        response.setType("message");
        response.setRole("assistant");
        response.setModel("claude-test");
        response.setContent(content);
        response.setStopReason("end_turn");
        response.setStopSequence(null);
        response.setUsage(usage);

        assertEquals("msg_1", response.getId());
        assertEquals("message", response.getType());
        assertEquals("assistant", response.getRole());
        assertEquals("claude-test", response.getModel());
        assertEquals(content, response.getContent());
        assertEquals("end_turn", response.getStopReason());
        assertEquals(null, response.getStopSequence());
        assertSame(usage, response.getUsage());
    }

    @Test
    @DisplayName("Should expose Anthropic usage accessors")
    void shouldExposeAnthropicUsageAccessors() {
        AnthropicUsage usage = new AnthropicUsage();

        usage.setInputTokens(7);
        usage.setOutputTokens(9);

        assertEquals(7, usage.getInputTokens());
        assertEquals(9, usage.getOutputTokens());
        assertEquals(2, new AnthropicUsage(1, 2).getOutputTokens());
    }

    @Test
    @DisplayName("Should expose Anthropic stream event accessors")
    void shouldExposeAnthropicStreamEventAccessors() {
        AnthropicStreamEvent defaults = new AnthropicStreamEvent();
        AnthropicStreamEvent event = new AnthropicStreamEvent("content_block_delta");
        AnthropicMessagesResponse message = new AnthropicMessagesResponse();
        AnthropicUsage usage = new AnthropicUsage(1, 2);
        Map<String, Object> contentBlock = Map.of("type", "text");
        Map<String, Object> delta = Map.of("text", "hel");

        assertEquals(null, defaults.getType());
        event.setMessage(message);
        event.setIndex(0);
        event.setContentBlock(contentBlock);
        event.setDelta(delta);
        event.setUsage(usage);
        event.setStopReason("end_turn");

        assertEquals("content_block_delta", event.getType());
        assertSame(message, event.getMessage());
        assertEquals(0, event.getIndex());
        assertEquals(contentBlock, event.getContentBlock());
        assertEquals(delta, event.getDelta());
        assertSame(usage, event.getUsage());
        assertEquals("end_turn", event.getStopReason());

        event.setType("message_stop");
        assertEquals("message_stop", event.getType());
    }
}
