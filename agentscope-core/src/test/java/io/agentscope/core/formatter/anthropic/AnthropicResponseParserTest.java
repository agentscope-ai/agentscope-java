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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for AnthropicResponseParser. */
class AnthropicResponseParserTest extends AnthropicFormatterTestBase {

    /**
     * Use reflection to call private parseStreamEvent method for unit testing individual event
     * types.
     */
    private ChatResponse invokeParseStreamEvent(RawMessageStreamEvent event, Instant startTime)
            throws Exception {
        Method method =
                AnthropicResponseParser.class.getDeclaredMethod(
                        "parseStreamEvent", RawMessageStreamEvent.class, Instant.class);
        method.setAccessible(true);
        return (ChatResponse) method.invoke(null, event, startTime);
    }

    @Test
    void testParseMessageWithTextBlock() {
        // Create mock Message with text content
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);

        when(message.id()).thenReturn("msg_123");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);

        when(contentBlock.text()).thenReturn(Optional.of(textBlock));
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.empty());
        when(textBlock.text()).thenReturn("Hello, world!");

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_123", response.getId());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof io.agentscope.core.message.TextBlock);

        io.agentscope.core.message.TextBlock parsedText =
                (io.agentscope.core.message.TextBlock) response.getContent().get(0);
        assertEquals("Hello, world!", parsedText.getText());

        ChatUsage responseUsage = response.getUsage();
        assertNotNull(responseUsage);
        assertEquals(100, responseUsage.getInputTokens());
        assertEquals(50, responseUsage.getOutputTokens());
    }

    @Test
    void testParseMessageWithToolUseBlock() {
        // Create mock Message with tool use content
        // Note: We use null input to avoid Kotlin reflection issues with JsonValue mocking
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        ToolUseBlock toolUseBlock = mock(ToolUseBlock.class);

        when(message.id()).thenReturn("msg_456");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(200L);
        when(usage.outputTokens()).thenReturn(100L);

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.of(toolUseBlock));
        when(contentBlock.thinking()).thenReturn(Optional.empty());

        when(toolUseBlock.id()).thenReturn("tool_call_123");
        when(toolUseBlock.name()).thenReturn("search");
        when(toolUseBlock._input()).thenReturn(null); // Avoid Kotlin reflection issues

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_456", response.getId());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof io.agentscope.core.message.ToolUseBlock);

        io.agentscope.core.message.ToolUseBlock parsedToolUse =
                (io.agentscope.core.message.ToolUseBlock) response.getContent().get(0);
        assertEquals("tool_call_123", parsedToolUse.getId());
        assertEquals("search", parsedToolUse.getName());
        assertNotNull(parsedToolUse.getInput());
        // Null input should result in empty map
        assertTrue(parsedToolUse.getInput().isEmpty());
    }

    @Test
    void testParseMessageWithThinkingBlock() {
        // Create mock Message with thinking content
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        ThinkingBlock thinkingBlock = mock(ThinkingBlock.class);

        when(message.id()).thenReturn("msg_789");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(150L);
        when(usage.outputTokens()).thenReturn(75L);

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.of(thinkingBlock));
        when(thinkingBlock.thinking()).thenReturn("Let me think about this...");

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_789", response.getId());
        assertEquals(1, response.getContent().size());
        assertTrue(
                response.getContent().get(0) instanceof io.agentscope.core.message.ThinkingBlock);

        io.agentscope.core.message.ThinkingBlock parsedThinking =
                (io.agentscope.core.message.ThinkingBlock) response.getContent().get(0);
        assertEquals("Let me think about this...", parsedThinking.getThinking());
    }

    @Test
    void testParseMessageWithMixedContent() {
        // Create mock Message with multiple content blocks
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        ContentBlock textContentBlock = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);

        ContentBlock toolContentBlock = mock(ContentBlock.class);
        ToolUseBlock toolUseBlock = mock(ToolUseBlock.class);

        when(message.id()).thenReturn("msg_mixed");
        when(message.content()).thenReturn(List.of(textContentBlock, toolContentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(300L);
        when(usage.outputTokens()).thenReturn(150L);

        // Text block
        when(textContentBlock.text()).thenReturn(Optional.of(textBlock));
        when(textContentBlock.toolUse()).thenReturn(Optional.empty());
        when(textContentBlock.thinking()).thenReturn(Optional.empty());
        when(textBlock.text()).thenReturn("Let me search for that.");

        // Tool use block - use null input to avoid Kotlin reflection issues
        when(toolContentBlock.text()).thenReturn(Optional.empty());
        when(toolContentBlock.toolUse()).thenReturn(Optional.of(toolUseBlock));
        when(toolContentBlock.thinking()).thenReturn(Optional.empty());
        when(toolUseBlock.id()).thenReturn("tool_xyz");
        when(toolUseBlock.name()).thenReturn("web_search");
        when(toolUseBlock._input()).thenReturn(null); // Avoid Kotlin reflection issues

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_mixed", response.getId());
        assertEquals(2, response.getContent().size());

        assertTrue(response.getContent().get(0) instanceof io.agentscope.core.message.TextBlock);
        assertTrue(response.getContent().get(1) instanceof io.agentscope.core.message.ToolUseBlock);
    }

    @Test
    void testParseMessageWithEmptyContent() {
        // Create mock Message with no content
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        when(message.id()).thenReturn("msg_empty");
        when(message.content()).thenReturn(List.of());
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(50L);
        when(usage.outputTokens()).thenReturn(0L);

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_empty", response.getId());
        assertTrue(response.getContent().isEmpty());
    }

    @Test
    void testParseMessageWithNullToolInput() {
        // Create mock Message with null tool input
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        ToolUseBlock toolUseBlock = mock(ToolUseBlock.class);

        when(message.id()).thenReturn("msg_null_input");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.of(toolUseBlock));
        when(contentBlock.thinking()).thenReturn(Optional.empty());

        when(toolUseBlock.id()).thenReturn("tool_null");
        when(toolUseBlock.name()).thenReturn("test_tool");
        when(toolUseBlock._input()).thenReturn(null);

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals(1, response.getContent().size());

        io.agentscope.core.message.ToolUseBlock parsedToolUse =
                (io.agentscope.core.message.ToolUseBlock) response.getContent().get(0);
        assertEquals("tool_null", parsedToolUse.getId());
        assertEquals("test_tool", parsedToolUse.getName());
        // Null input should result in empty map
        assertNotNull(parsedToolUse.getInput());
        assertTrue(parsedToolUse.getInput().isEmpty());
    }

    @Test
    void testParseStreamEventsMessageStart() {
        // Create mock MessageStart event
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);
        RawMessageStartEvent messageStartEvent = mock(RawMessageStartEvent.class);
        Message message = mock(Message.class);

        when(event.isMessageStart()).thenReturn(true);
        when(event.asMessageStart()).thenReturn(messageStartEvent);
        when(messageStartEvent.message()).thenReturn(message);
        when(message.id()).thenReturn("msg_stream_123");

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // MessageStart events should be filtered out (empty content)
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventMessageStart() throws Exception {
        // Test MessageStart event - should set message ID but have empty content
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);
        RawMessageStartEvent messageStart = mock(RawMessageStartEvent.class);
        Message message = mock(Message.class);

        when(event.isMessageStart()).thenReturn(true);
        when(event.asMessageStart()).thenReturn(messageStart);
        when(messageStart.message()).thenReturn(message);
        when(message.id()).thenReturn("msg_stream_123");

        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        Instant startTime = Instant.now();
        ChatResponse response = invokeParseStreamEvent(event, startTime);

        assertNotNull(response);
        assertEquals("msg_stream_123", response.getId());
        assertTrue(response.getContent().isEmpty()); // MessageStart has no content
    }

    @Test
    void testParseStreamEventUnknownType() throws Exception {
        // Test unknown event type - should return empty response
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);

        when(event.isMessageStart()).thenReturn(false);
        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        Instant startTime = Instant.now();
        ChatResponse response = invokeParseStreamEvent(event, startTime);

        assertNotNull(response);
        assertNotNull(response.getId()); // Builder auto-generates UUID when id is null
        assertFalse(response.getId().isEmpty());
        assertTrue(response.getContent().isEmpty());
        assertNull(response.getUsage());
    }

    @Test
    void testParseStreamEventsFiltersEmptyContent() {
        // Test that parseStreamEvents filters out responses with empty content
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);

        when(event.isMessageStart()).thenReturn(false);
        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // Empty content responses should be filtered out
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsHandlesExceptions() {
        // Test that exceptions in parsing are caught and logged
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);

        // Make the event throw an exception
        when(event.isMessageStart()).thenThrow(new RuntimeException("Test exception"));

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // Exception should be caught and result in empty flux
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsErrorHandling() {
        // Create a Flux that emits an error
        Flux<RawMessageStreamEvent> errorFlux = Flux.error(new RuntimeException("Stream error"));

        Instant startTime = Instant.now();

        // parseStreamEvents should propagate errors
        StepVerifier.create(AnthropicResponseParser.parseStreamEvents(errorFlux, startTime))
                .expectError(RuntimeException.class)
                .verify();
    }
}
