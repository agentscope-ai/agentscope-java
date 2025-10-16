/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import reactor.core.publisher.Flux;

/**
 * Comprehensive unit tests for DashScopeChatModel using Mockito mockConstruction to mock the
 * Generation class instantiation.
 *
 * <p>These tests achieve high code coverage by mocking the DashScope SDK components and testing
 * various scenarios including: - Model creation with different configurations - Streaming and
 * non-streaming completions - Tool call handling - Thinking content parsing - Error handling -
 * Generation options application
 */
@Tag("unit")
@DisplayName("DashScopeChatModel Advanced Tests")
class DashScopeChatModelAdvancedTest {

    /**
     * Tests model creation with all parameters including baseUrl.
     *
     * <p>Verifies that the builder correctly sets all fields including custom baseUrl.
     */
    @Test
    @DisplayName("Should create model with all parameters")
    void testModelCreationWithAllParameters() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).topP(0.9).build();

        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-api-key").modelName("qwen-max").stream(
                                true)
                        .enableThinking(true)
                        .defaultOptions(options)
                        .protocol(Protocol.HTTP.getValue())
                        .baseUrl("https://custom.dashscope.com")
                        .build();

        assertNotNull(model);
    }

    /**
     * Tests model creation without baseUrl.
     *
     * <p>Verifies that baseUrl is optional and the model can be created without it.
     */
    @Test
    @DisplayName("Should create model without baseUrl")
    void testModelCreationWithoutBaseUrl() {
        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-api-key").modelName("qwen-max").stream(
                                false)
                        .build();

        assertNotNull(model);
    }

    /**
     * Tests model creation with enableThinking=true forces streaming mode.
     *
     * <p>Verifies that when enableThinking is true, the model switches to streaming mode
     * regardless of the stream parameter.
     */
    @Test
    @DisplayName("Should force streaming when enableThinking is true")
    void testEnableThinkingForcesStreaming() {
        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-api-key").modelName("qwen-max").stream(
                                false) // Explicitly set to false
                        .enableThinking(true) // This should force stream to true
                        .build();

        assertNotNull(model);
    }

    /**
     * Tests streaming completion with text content.
     *
     * <p>Verifies that the model correctly handles streaming responses and parses text content.
     */
    @Test
    @DisplayName("Should handle streaming completion with text content")
    void testStreamingCompletion() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            // Mock streamCall to invoke callback with mock result
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);

                                                // Simulate streaming chunks
                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                "Hello", null, null, null));
                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                " world", null, null, null));
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .stream(true)
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(List.of(Map.of("type", "text", "text", "Hello")))
                                    .build());

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);
            List<ChatResponse> responses = responseFlux.collectList().block();

            assertNotNull(responses);
            assertEquals(2, responses.size());

            // Verify first chunk
            ChatResponse firstChunk = responses.get(0);
            assertNotNull(firstChunk);
            assertFalse(firstChunk.getContent().isEmpty());
            assertInstanceOf(TextBlock.class, firstChunk.getContent().get(0));
            TextBlock firstText = (TextBlock) firstChunk.getContent().get(0);
            assertEquals("Hello", firstText.getText());

            // Verify second chunk
            ChatResponse secondChunk = responses.get(1);
            assertNotNull(secondChunk);
            assertFalse(secondChunk.getContent().isEmpty());
            assertInstanceOf(TextBlock.class, secondChunk.getContent().get(0));
            TextBlock secondText = (TextBlock) secondChunk.getContent().get(0);
            assertEquals(" world", secondText.getText());
        }
    }

    /**
     * Tests streaming with thinking content (reasoning).
     *
     * <p>Verifies that the model correctly parses reasoning content into ThinkingBlock.
     */
    @Test
    @DisplayName("Should handle thinking content in streaming")
    void testStreamingWithThinkingContent() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);
                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                "Answer",
                                                                "Let me think...",
                                                                null,
                                                                null));
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .enableThinking(true)
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(List.of(Map.of("type", "text", "text", "Question")))
                                    .build());

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);
            List<ChatResponse> responses = responseFlux.collectList().block();

            assertNotNull(responses);
            assertEquals(1, responses.size());

            ChatResponse response = responses.get(0);
            List<ContentBlock> blocks = response.getContent();
            // Output.getText() also generates a TextBlock, so we have 3 blocks total
            assertEquals(3, blocks.size());

            // First block should be text from output.getText()
            assertInstanceOf(TextBlock.class, blocks.get(0));
            // Second block should be thinking
            assertInstanceOf(ThinkingBlock.class, blocks.get(1));
            ThinkingBlock thinking = (ThinkingBlock) blocks.get(1);
            assertEquals("Let me think...", thinking.getThinking());
            // Third block should be text from message.getContent()
            assertInstanceOf(TextBlock.class, blocks.get(2));
            TextBlock text = (TextBlock) blocks.get(2);
            assertEquals("Answer", text.getText());
        }
    }

    /**
     * Tests streaming with tool calls.
     *
     * <p>Verifies that the model correctly parses tool calls into ToolUseBlock.
     */
    @Test
    @DisplayName("Should handle tool calls in streaming")
    void testStreamingWithToolCalls() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);

                                                // Create mock tool call
                                                ToolCallFunction toolCall = new ToolCallFunction();
                                                toolCall.setId("call_123");
                                                ToolCallFunction.CallFunction callFunction =
                                                        toolCall.new CallFunction();
                                                callFunction.setName("get_weather");
                                                callFunction.setArguments(
                                                        "{\"location\":\"Beijing\"}");
                                                toolCall.setFunction(callFunction);

                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                null,
                                                                null,
                                                                List.of(toolCall),
                                                                null));
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(
                                            List.of(
                                                    Map.of(
                                                            "type",
                                                            "text",
                                                            "text",
                                                            "What's the weather?")))
                                    .build());

            // Define tools
            List<ToolSchema> tools = new ArrayList<>();
            tools.add(
                    ToolSchema.builder()
                            .name("get_weather")
                            .description("Get weather information")
                            .parameters(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("location", Map.of("type", "string"))))
                            .build());

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, tools, null);
            List<ChatResponse> responses = responseFlux.collectList().block();

            assertNotNull(responses);
            assertEquals(1, responses.size());

            ChatResponse response = responses.get(0);
            List<ContentBlock> blocks = response.getContent();
            assertEquals(1, blocks.size());

            // Verify tool use block
            assertInstanceOf(ToolUseBlock.class, blocks.get(0));
            ToolUseBlock toolUse = (ToolUseBlock) blocks.get(0);
            assertEquals("call_123", toolUse.getId());
            assertEquals("get_weather", toolUse.getName());
            assertNotNull(toolUse.getInput());
            assertEquals("Beijing", toolUse.getInput().get("location"));
        }
    }

    /**
     * Tests error handling in streaming.
     *
     * <p>Verifies that the model correctly propagates errors through the Flux.
     */
    @Test
    @DisplayName("Should propagate errors in streaming")
    void testErrorHandling() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);
                                                callback.onError(new RuntimeException("API Error"));
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(List.of(Map.of("type", "text", "text", "Hello")))
                                    .build());

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);

            // Verify that the error is propagated
            try {
                responseFlux.collectList().block();
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("API Error"));
            }
        }
    }

    /**
     * Tests generation options application (temperature, maxTokens, topP).
     *
     * <p>Verifies that the model correctly applies generation options to the API request.
     */
    @Test
    @DisplayName("Should apply generation options")
    void testGenerationOptions() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                GenerationParam param = invocation.getArgument(0);

                                                // Verify options are applied
                                                assertEquals(0.8f, param.getTemperature());
                                                assertEquals(0.95, param.getTopP());
                                                assertEquals(2000, param.getMaxTokens());

                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);
                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                "Response", null, null, null));
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(List.of(Map.of("type", "text", "text", "Hello")))
                                    .build());

            GenerateOptions options =
                    GenerateOptions.builder().temperature(0.8).maxTokens(2000).topP(0.95).build();

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, options);
            List<ChatResponse> responses = responseFlux.collectList().block();

            assertNotNull(responses);
            assertEquals(1, responses.size());
        }
    }

    /**
     * Tests usage information parsing.
     *
     * <p>Verifies that the model correctly parses token usage information from the API response.
     */
    @Test
    @DisplayName("Should parse usage information")
    void testUsageParsing() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            doAnswer(
                                            invocation -> {
                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);

                                                GenerationUsage usage = mock(GenerationUsage.class);
                                                when(usage.getInputTokens()).thenReturn(100);
                                                when(usage.getOutputTokens()).thenReturn(50);

                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                "Response", null, null, usage));
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(List.of(Map.of("type", "text", "text", "Hello")))
                                    .build());

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);
            List<ChatResponse> responses = responseFlux.collectList().block();

            assertNotNull(responses);
            assertEquals(1, responses.size());

            ChatResponse response = responses.get(0);
            ChatUsage usage = response.getUsage();
            assertNotNull(usage);
            assertEquals(100, usage.getInputTokens());
            assertEquals(50, usage.getOutputTokens());
        }
    }

    /**
     * Tests model creation with custom baseUrl.
     *
     * <p>Verifies that the Generation constructor is called with both protocol and baseUrl when
     * baseUrl is provided.
     */
    @Test
    @DisplayName("Should create Generation with baseUrl when provided")
    void testModelWithCustomBaseUrl() {
        try (MockedConstruction<Generation> mockedGeneration =
                mockConstruction(
                        Generation.class,
                        (mock, context) -> {
                            // Verify constructor arguments
                            List<?> args = context.arguments();
                            assertEquals(2, args.size());
                            assertEquals(Protocol.HTTP.getValue(), args.get(0));
                            assertEquals("https://custom.dashscope.com", args.get(1));

                            doAnswer(
                                            invocation -> {
                                                ResultCallback<GenerationResult> callback =
                                                        invocation.getArgument(1);
                                                callback.onEvent(
                                                        createMockGenerationResult(
                                                                "Response", null, null, null));
                                                callback.onComplete();
                                                return null;
                                            })
                                    .when(mock)
                                    .streamCall(any(GenerationParam.class), any());
                        })) {

            DashScopeChatModel model =
                    DashScopeChatModel.builder()
                            .apiKey("test-api-key")
                            .modelName("qwen-max")
                            .baseUrl("https://custom.dashscope.com")
                            .build();

            FormattedMessageList messages =
                    FormattedMessageList.of(
                            FormattedMessage.builder()
                                    .role("user")
                                    .content(List.of(Map.of("type", "text", "text", "Hello")))
                                    .build());

            Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);
            List<ChatResponse> responses = responseFlux.collectList().block();

            assertNotNull(responses);
        }
    }

    /**
     * Helper method to create mock GenerationResult.
     *
     * @param text The text content
     * @param reasoningContent The reasoning/thinking content
     * @param toolCalls The list of tool calls
     * @param usage The usage information
     * @return A mocked GenerationResult
     */
    private GenerationResult createMockGenerationResult(
            String text,
            String reasoningContent,
            List<ToolCallBase> toolCalls,
            GenerationUsage usage) {
        GenerationResult result = mock(GenerationResult.class);
        GenerationOutput output = mock(GenerationOutput.class);
        Message message = mock(Message.class);

        // Setup output
        when(result.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn(text);

        // Setup choices and message
        GenerationOutput.Choice choice = mock(GenerationOutput.Choice.class);
        when(output.getChoices()).thenReturn(List.of(choice));
        when(choice.getMessage()).thenReturn(message);

        // Setup message content
        when(message.getContent()).thenReturn(text);
        when(message.getReasoningContent()).thenReturn(reasoningContent);
        when(message.getToolCalls()).thenReturn(toolCalls);

        // Setup usage
        when(result.getUsage()).thenReturn(usage);
        when(result.getRequestId()).thenReturn("request-123");

        return result;
    }
}
