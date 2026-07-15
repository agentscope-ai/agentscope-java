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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for AnthropicResponseParser. */
class AnthropicResponseParserTest extends AnthropicFormatterTestBase {

    private static com.anthropic.models.messages.TextBlock mockTextBlock() {
        return mock(com.anthropic.models.messages.TextBlock.class);
    }

    private static com.anthropic.models.messages.ThinkingBlock mockThinkingBlock() {
        var thinkingBlock = mock(com.anthropic.models.messages.ThinkingBlock.class);
        when(thinkingBlock.signature()).thenReturn("signature-123");
        return thinkingBlock;
    }

    private static com.anthropic.models.messages.ToolUseBlock mockToolUseBlock() {
        return mock(com.anthropic.models.messages.ToolUseBlock.class);
    }

    private static ContentBlock mockContentBlock() {
        ContentBlock contentBlock = mock(ContentBlock.class);
        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.empty());
        when(contentBlock.redactedThinking()).thenReturn(Optional.empty());
        return contentBlock;
    }

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
        ContentBlock contentBlock = mockContentBlock();
        var textBlock = mockTextBlock();

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
        TextBlock parsedText = assertInstanceOf(TextBlock.class, response.getContent().get(0));
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
        ContentBlock contentBlock = mockContentBlock();
        var toolUseBlock = mockToolUseBlock();

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
        ToolUseBlock parsedToolUse =
                assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
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
        ContentBlock contentBlock = mockContentBlock();
        var thinkingBlock = mockThinkingBlock();

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
        ThinkingBlock parsedThinking =
                assertInstanceOf(ThinkingBlock.class, response.getContent().get(0));
        assertEquals("Let me think about this...", parsedThinking.getThinking());
        assertEquals(
                "signature-123",
                AnthropicThinkingMetadata.toContentBlockParams(parsedThinking)
                        .get(0)
                        .asThinking()
                        .signature());
    }

    @Test
    void testParseMessagePreservesThinkingAndRedactedBlocks() throws Exception {
        String json =
                """
                {
                  "id": "msg_reasoning",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "thinking",
                      "thinking": "Reasoning",
                      "signature": "signature-123"
                    },
                    {
                      "type": "redacted_thinking",
                      "data": "encrypted-data"
                    }
                  ],
                  "model": "claude-sonnet-4-5-20250929",
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {"input_tokens": 10, "output_tokens": 20}
                }
                """;
        Message message = ObjectMappers.jsonMapper().readValue(json, Message.class);

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        assertEquals(2, response.getContent().size());
        ThinkingBlock thinking =
                assertInstanceOf(ThinkingBlock.class, response.getContent().get(0));
        ThinkingBlock redacted =
                assertInstanceOf(ThinkingBlock.class, response.getContent().get(1));
        assertTrue(AnthropicThinkingMetadata.toContentBlockParams(thinking).get(0).isThinking());
        assertTrue(
                AnthropicThinkingMetadata.toContentBlockParams(redacted)
                        .get(0)
                        .isRedactedThinking());
    }

    @Test
    void testParseMessageWithMixedContent() {
        // Create mock Message with multiple content blocks
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        ContentBlock textContentBlock = mockContentBlock();
        var textBlock = mockTextBlock();

        ContentBlock toolContentBlock = mockContentBlock();
        var toolUseBlock = mockToolUseBlock();

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

        assertInstanceOf(TextBlock.class, response.getContent().get(0));
        assertInstanceOf(ToolUseBlock.class, response.getContent().get(1));
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
        ContentBlock contentBlock = mockContentBlock();
        var toolUseBlock = mockToolUseBlock();

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

        ToolUseBlock parsedToolUse =
                assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
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
    void testParseStreamEventThinkingDelta() throws Exception {
        RawContentBlockDeltaEvent deltaEvent =
                RawContentBlockDeltaEvent.builder()
                        .index(0)
                        .thinkingDelta("Let me reason through this.")
                        .build();
        RawMessageStreamEvent event = RawMessageStreamEvent.ofContentBlockDelta(deltaEvent);

        Instant startTime = Instant.now();
        ChatResponse response = invokeParseStreamEvent(event, startTime);

        assertNotNull(response);
        assertEquals(1, response.getContent().size());
        ThinkingBlock parsedThinking =
                assertInstanceOf(ThinkingBlock.class, response.getContent().get(0));
        assertEquals("Let me reason through this.", parsedThinking.getThinking());
        assertNull(response.getUsage());
    }

    @Test
    void testParseStreamEventTextDelta() throws Exception {
        RawMessageStreamEvent event =
                RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder().index(0).textDelta("answer").build());

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        TextBlock text = assertInstanceOf(TextBlock.class, response.getContent().get(0));
        assertEquals("answer", text.getText());
    }

    @Test
    void testParseStreamEventInputJsonDelta() throws Exception {
        RawMessageStreamEvent event =
                RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder()
                                .index(0)
                                .inputJsonDelta("{\"city\":")
                                .build());

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        ToolUseBlock fragment = assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        assertEquals("", fragment.getId());
        assertEquals("__fragment__", fragment.getName());
        assertEquals("{\"city\":", fragment.getContent());
    }

    @Test
    void testParseStreamEventToolUseStart() throws Exception {
        RawMessageStreamEvent event =
                RawMessageStreamEvent.ofContentBlockStart(
                        RawContentBlockStartEvent.builder()
                                .index(0)
                                .contentBlock(
                                        com.anthropic.models.messages.ToolUseBlock.builder()
                                                .id("call-123")
                                                .name("weather")
                                                .input(JsonValue.from(Map.of("city", "Hangzhou")))
                                                .build())
                                .build());

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        ToolUseBlock toolUse = assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        assertEquals("call-123", toolUse.getId());
        assertEquals("weather", toolUse.getName());
        assertTrue(toolUse.getInput().isEmpty());
    }

    @Test
    void testParseStreamEventMessageDeltaUsage() throws Exception {
        RawMessageStreamEvent event =
                RawMessageStreamEvent.ofMessageDelta(
                        RawMessageDeltaEvent.builder()
                                .delta(
                                        RawMessageDeltaEvent.Delta.builder()
                                                .stopReason(StopReason.END_TURN)
                                                .stopSequence(Optional.empty())
                                                .build())
                                .usage(
                                        MessageDeltaUsage.builder()
                                                .cacheCreationInputTokens(Optional.empty())
                                                .cacheReadInputTokens(Optional.empty())
                                                .inputTokens(Optional.empty())
                                                .outputTokens(7)
                                                .serverToolUse(Optional.empty())
                                                .build())
                                .build());

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        assertNotNull(response.getUsage());
        assertEquals(7, response.getUsage().getOutputTokens());
    }

    @Test
    void testParseStreamPreservesSignatureAndRedactedThinking() {
        RawMessageStreamEvent firstThinking =
                RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder()
                                .index(0)
                                .thinkingDelta("Let me ")
                                .build());
        RawMessageStreamEvent secondThinking =
                RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder()
                                .index(0)
                                .thinkingDelta("reason")
                                .build());
        RawMessageStreamEvent signature =
                RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder()
                                .index(0)
                                .signatureDelta("signature-123")
                                .build());
        RawMessageStreamEvent redacted =
                RawMessageStreamEvent.ofContentBlockStart(
                        RawContentBlockStartEvent.builder()
                                .index(1)
                                .redactedThinkingContentBlock("encrypted-data")
                                .build());

        Flux<ChatResponse> responses =
                AnthropicResponseParser.parseStreamEvents(
                        Flux.just(firstThinking, secondThinking, signature, redacted),
                        Instant.now());

        StepVerifier.create(responses.collectList())
                .assertNext(
                        chunks -> {
                            ReasoningContext context = new ReasoningContext("Assistant");
                            chunks.forEach(context::processChunk);
                            Msg message = context.buildFinalMessage();
                            ThinkingBlock thinking =
                                    message.getFirstContentBlock(ThinkingBlock.class);

                            assertEquals("Let me reason", thinking.getThinking());
                            var nativeBlocks =
                                    AnthropicThinkingMetadata.toContentBlockParams(thinking);
                            assertEquals(2, nativeBlocks.size());
                            assertTrue(nativeBlocks.get(0).isThinking());
                            assertEquals(
                                    "signature-123", nativeBlocks.get(0).asThinking().signature());
                            assertTrue(nativeBlocks.get(1).isRedactedThinking());
                            assertEquals(
                                    "encrypted-data",
                                    nativeBlocks.get(1).asRedactedThinking().data());
                        })
                .verifyComplete();
    }

    @Test
    void testParseStreamPreservesOmittedThinkingSignature() {
        RawMessageStreamEvent start =
                RawMessageStreamEvent.ofContentBlockStart(
                        RawContentBlockStartEvent.builder()
                                .index(0)
                                .contentBlock(
                                        com.anthropic.models.messages.ThinkingBlock.builder()
                                                .thinking("")
                                                .signature("")
                                                .build())
                                .build());
        RawMessageStreamEvent signature =
                RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder()
                                .index(0)
                                .signatureDelta("signature-omitted")
                                .build());
        RawMessageStreamEvent stop =
                RawMessageStreamEvent.ofContentBlockStop(
                        RawContentBlockStopEvent.builder().index(0).build());

        StepVerifier.create(
                        AnthropicResponseParser.parseStreamEvents(
                                        Flux.just(start, signature, stop), Instant.now())
                                .collectList())
                .assertNext(
                        chunks -> {
                            ReasoningContext context = new ReasoningContext("Assistant");
                            chunks.forEach(context::processChunk);
                            ThinkingBlock thinking =
                                    context.buildFinalMessage()
                                            .getFirstContentBlock(ThinkingBlock.class);
                            List<ContentBlockParam> nativeBlocks =
                                    AnthropicThinkingMetadata.toContentBlockParams(thinking);

                            assertEquals("", thinking.getThinking());
                            assertEquals(1, nativeBlocks.size());
                            assertEquals(
                                    "signature-omitted",
                                    nativeBlocks.get(0).asThinking().signature());
                        })
                .verifyComplete();
    }

    @Test
    void testParseStreamPreservesThinkingFromContentBlockStart() {
        RawMessageStreamEvent start =
                RawMessageStreamEvent.ofContentBlockStart(
                        RawContentBlockStartEvent.builder()
                                .index(2)
                                .contentBlock(
                                        com.anthropic.models.messages.ThinkingBlock.builder()
                                                .thinking("initial reasoning")
                                                .signature("signature-start")
                                                .build())
                                .build());

        StepVerifier.create(
                        AnthropicResponseParser.parseStreamEvents(Flux.just(start), Instant.now()))
                .assertNext(
                        response -> {
                            ThinkingBlock thinking =
                                    assertInstanceOf(
                                            ThinkingBlock.class, response.getContent().get(0));
                            List<ContentBlockParam> nativeBlocks =
                                    AnthropicThinkingMetadata.toContentBlockParams(thinking);

                            assertEquals("initial reasoning", thinking.getThinking());
                            assertEquals(
                                    "signature-start",
                                    nativeBlocks.get(0).asThinking().signature());
                        })
                .verifyComplete();
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
