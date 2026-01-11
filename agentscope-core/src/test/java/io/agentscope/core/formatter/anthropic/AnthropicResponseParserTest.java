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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicResponse;
import io.agentscope.core.formatter.anthropic.dto.AnthropicStreamEvent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicUsage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for AnthropicResponseParser. */
class AnthropicResponseParserTest extends AnthropicFormatterTestBase {

    @Test
    void testParseMessageWithTextBlock() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_123");
        response.setContent(List.of(AnthropicContent.text("Hello, world!")));

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(100);
        usage.setOutputTokens(50);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("msg_123", chatResponse.getId());
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(
                chatResponse.getContent().get(0) instanceof io.agentscope.core.message.TextBlock);
        assertEquals(
                "Hello, world!",
                ((io.agentscope.core.message.TextBlock) chatResponse.getContent().get(0))
                        .getText());

        ChatUsage responseUsage = chatResponse.getUsage();
        assertNotNull(responseUsage);
        assertEquals(100, responseUsage.getInputTokens());
        assertEquals(50, responseUsage.getOutputTokens());
    }

    @Test
    void testParseMessageWithToolUseBlock() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_456");

        AnthropicContent toolContent = new AnthropicContent();
        toolContent.setType("tool_use");
        toolContent.setId("tool_call_123");
        toolContent.setName("search");
        toolContent.setInput(Map.of("query", "test"));
        response.setContent(List.of(toolContent));

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(200);
        usage.setOutputTokens(100);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("msg_456", chatResponse.getId());
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(
                chatResponse.getContent().get(0)
                        instanceof io.agentscope.core.message.ToolUseBlock);

        io.agentscope.core.message.ToolUseBlock toolUse =
                (io.agentscope.core.message.ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("tool_call_123", toolUse.getId());
        assertEquals("search", toolUse.getName());
        assertEquals("test", toolUse.getInput().get("query"));
    }

    @Test
    void testParseMessageWithThinkingBlock() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_789");
        response.setContent(List.of(AnthropicContent.thinking("Let me think...")));

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(150);
        usage.setOutputTokens(75);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(
                chatResponse.getContent().get(0)
                        instanceof io.agentscope.core.message.ThinkingBlock);

        io.agentscope.core.message.ThinkingBlock thinking =
                (io.agentscope.core.message.ThinkingBlock) chatResponse.getContent().get(0);
        assertEquals("Let me think...", thinking.getThinking());
    }

    @Test
    void testParseMessageWithMixedContent() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_mixed");

        AnthropicContent toolContent = new AnthropicContent();
        toolContent.setType("tool_use");
        toolContent.setId("tool_xyz");
        toolContent.setName("web_search");
        toolContent.setInput(Map.of());

        response.setContent(List.of(AnthropicContent.text("Let me search for that."), toolContent));

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(300);
        usage.setOutputTokens(150);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());
        assertTrue(
                chatResponse.getContent().get(0) instanceof io.agentscope.core.message.TextBlock);
        assertTrue(
                chatResponse.getContent().get(1)
                        instanceof io.agentscope.core.message.ToolUseBlock);
    }

    @Test
    void testParseStreamEventsMessageStart() {
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType("message_start");

        // message_start event has a specialized 'message' field which is
        // AnthropicResponse
        AnthropicResponse message = new AnthropicResponse();
        message.setId("msg_stream_123");

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(10);
        usage.setOutputTokens(0);
        message.setUsage(usage); // Set usage on the message structure

        event.setMessage(message);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // Current implementation filters out empty content responses, so message_start
        // is verified to complete empty
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsTextDelta() {
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType("content_block_delta");
        event.setIndex(0);

        AnthropicStreamEvent.Delta delta = new AnthropicStreamEvent.Delta();
        delta.setType("text_delta");
        delta.setText("Hello stream");
        event.setDelta(delta);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        StepVerifier.create(responseFlux)
                .assertNext(
                        res -> {
                            assertEquals(1, res.getContent().size());
                            assertTrue(
                                    res.getContent().get(0)
                                            instanceof io.agentscope.core.message.TextBlock);
                            assertEquals(
                                    "Hello stream",
                                    ((io.agentscope.core.message.TextBlock) res.getContent().get(0))
                                            .getText());
                        })
                .verifyComplete();
    }

    @Test
    void testParseStreamEventsUnknownType() {
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType("unknown_event");

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsErrorHandling() {
        // Create a Flux that emits an error
        Flux<AnthropicStreamEvent> errorFlux = Flux.error(new RuntimeException("Stream error"));

        Instant startTime = Instant.now();

        // parseStreamEvents should propagate errors
        StepVerifier.create(AnthropicResponseParser.parseStreamEvents(errorFlux, startTime))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void testParseMessageWithNullContent() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_null_content");
        response.setContent(null);

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(50);
        usage.setOutputTokens(25);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertTrue(chatResponse.getContent().isEmpty());
        assertNotNull(chatResponse.getUsage());
    }

    @Test
    void testParseMessageWithEmptyContent() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_empty_content");
        response.setContent(List.of());

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(30);
        usage.setOutputTokens(15);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertTrue(chatResponse.getContent().isEmpty());
        assertNotNull(chatResponse.getUsage());
    }

    @Test
    void testParseMessageWithNullUsage() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_no_usage");
        response.setContent(List.of(AnthropicContent.text("Hello")));
        response.setUsage(null);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        // Usage should be null when not provided
        assertNull(chatResponse.getUsage());
    }

    @Test
    void testParseMessageWithNullTokensInUsage() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_null_tokens");
        response.setContent(List.of(AnthropicContent.text("Response")));

        AnthropicUsage usage = new AnthropicUsage();
        usage.setInputTokens(null);
        usage.setOutputTokens(null);
        response.setUsage(usage);

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getUsage());
        assertEquals(0, chatResponse.getUsage().getInputTokens());
        assertEquals(0, chatResponse.getUsage().getOutputTokens());
    }

    @Test
    void testParseMessageWithNullTextBlock() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_null_text");

        AnthropicContent content = new AnthropicContent();
        content.setType("text");
        content.setText(null);
        response.setContent(List.of(content));

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        // Null text blocks should be skipped
        assertTrue(chatResponse.getContent().isEmpty());
    }

    @Test
    void testParseMessageWithUnknownContentType() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_unknown_type");

        AnthropicContent content = new AnthropicContent();
        content.setType("unknown_type");
        response.setContent(List.of(content));

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        // Unknown content types should be skipped
        assertTrue(chatResponse.getContent().isEmpty());
    }

    @Test
    void testParseStreamEventsContentBlockStart() {
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType("content_block_start");
        event.setIndex(0);

        AnthropicContent toolContent = new AnthropicContent();
        toolContent.setType("tool_use");
        toolContent.setId("tool_123");
        toolContent.setName("calculator");
        event.setContentBlock(toolContent);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        StepVerifier.create(responseFlux)
                .assertNext(
                        res -> {
                            assertEquals(1, res.getContent().size());
                            assertTrue(
                                    res.getContent().get(0)
                                            instanceof io.agentscope.core.message.ToolUseBlock);
                            io.agentscope.core.message.ToolUseBlock toolUse =
                                    (io.agentscope.core.message.ToolUseBlock)
                                            res.getContent().get(0);
                            assertEquals("tool_123", toolUse.getId());
                            assertEquals("calculator", toolUse.getName());
                        })
                .verifyComplete();
    }

    @Test
    void testParseStreamEventsContentBlockDeltaWithPartialJson() {
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType("content_block_delta");
        event.setIndex(0);

        AnthropicStreamEvent.Delta delta = new AnthropicStreamEvent.Delta();
        delta.setType("input_json_delta");
        delta.setPartialJson("{\"query\":");
        event.setDelta(delta);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        StepVerifier.create(responseFlux)
                .assertNext(
                        res -> {
                            assertEquals(1, res.getContent().size());
                            assertTrue(
                                    res.getContent().get(0)
                                            instanceof io.agentscope.core.message.ToolUseBlock);
                            io.agentscope.core.message.ToolUseBlock toolUse =
                                    (io.agentscope.core.message.ToolUseBlock)
                                            res.getContent().get(0);
                            assertEquals("__fragment__", toolUse.getName());
                            assertEquals("{\"query\":", toolUse.getContent());
                        })
                .verifyComplete();
    }

    @Test
    void testParseStreamEventsMessageDelta() {
        // message_delta events with only usage (no content) are filtered out
        // The usage info would typically be attached to the last content block in a real stream
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType("message_delta");

        AnthropicStreamEvent.Delta delta = new AnthropicStreamEvent.Delta();
        event.setDelta(delta);

        io.agentscope.core.formatter.anthropic.dto.AnthropicUsage usage =
                new io.agentscope.core.formatter.anthropic.dto.AnthropicUsage();
        usage.setOutputTokens(150);
        event.setUsage(usage);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // message_delta with only usage (no content) completes without emitting
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsWithNullType() {
        AnthropicStreamEvent event = new AnthropicStreamEvent();
        event.setType(null);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // Should handle null type gracefully and complete
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseMessageWithMultipleToolUseBlocks() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_multiple_tools");

        AnthropicContent tool1 = new AnthropicContent();
        tool1.setType("tool_use");
        tool1.setId("tool_1");
        tool1.setName("search");
        tool1.setInput(Map.of("query", "test"));

        AnthropicContent tool2 = new AnthropicContent();
        tool2.setType("tool_use");
        tool2.setId("tool_2");
        tool2.setName("calculate");
        tool2.setInput(Map.of("expression", "2+2"));

        response.setContent(List.of(tool1, tool2));

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        io.agentscope.core.message.ToolUseBlock firstTool =
                (io.agentscope.core.message.ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("tool_1", firstTool.getId());
        assertEquals("search", firstTool.getName());

        io.agentscope.core.message.ToolUseBlock secondTool =
                (io.agentscope.core.message.ToolUseBlock) chatResponse.getContent().get(1);
        assertEquals("tool_2", secondTool.getId());
        assertEquals("calculate", secondTool.getName());
    }

    @Test
    void testParseMessageWithToolUseNullInput() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_null_input");

        AnthropicContent toolContent = new AnthropicContent();
        toolContent.setType("tool_use");
        toolContent.setId("tool_1");
        toolContent.setName("search");
        toolContent.setInput(null);
        response.setContent(List.of(toolContent));

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        io.agentscope.core.message.ToolUseBlock toolUse =
                (io.agentscope.core.message.ToolUseBlock) chatResponse.getContent().get(0);
        assertNotNull(toolUse.getInput());
        assertTrue(toolUse.getInput().isEmpty());
    }

    @Test
    void testParseMessageWithThinkingNullText() {
        AnthropicResponse response = new AnthropicResponse();
        response.setId("msg_null_thinking");

        AnthropicContent thinkingContent = new AnthropicContent();
        thinkingContent.setType("thinking");
        thinkingContent.setThinking(null);
        response.setContent(List.of(thinkingContent));

        Instant startTime = Instant.now();
        ChatResponse chatResponse = AnthropicResponseParser.parseMessage(response, startTime);

        assertNotNull(chatResponse);
        // Null thinking should be skipped
        assertTrue(chatResponse.getContent().isEmpty());
    }
}
