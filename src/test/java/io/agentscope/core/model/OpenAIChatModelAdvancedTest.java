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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.core.publisher.Flux;

/**
 * Advanced unit tests for OpenAIChatModel using Mockito mockStatic.
 *
 * <p>These tests use Mockito's mockStatic functionality to mock the OpenAI SDK client creation and
 * API calls.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("OpenAIChatModel Advanced Tests with MockStatic")
class OpenAIChatModelAdvancedTest {

    private MockedStatic<OpenAIOkHttpClient> mockedClientBuilder;
    private MockedStatic<ChatModel> mockedChatModel;
    private OpenAIOkHttpClient.Builder mockClientBuilder;
    private OpenAIClient mockClient;
    private ChatService mockChatService;
    private ChatCompletionService mockChatCompletionService;

    @BeforeEach
    void setUp() {
        // Mock OpenAIOkHttpClient.Builder
        mockedClientBuilder = mockStatic(OpenAIOkHttpClient.class);
        mockClientBuilder = mock(OpenAIOkHttpClient.Builder.class);
        mockClient = mock(OpenAIClient.class);
        mockChatService = mock(ChatService.class);
        mockChatCompletionService = mock(ChatCompletionService.class);

        // Setup mock chain
        mockedClientBuilder.when(OpenAIOkHttpClient::builder).thenReturn(mockClientBuilder);
        when(mockClientBuilder.apiKey(anyString())).thenReturn(mockClientBuilder);
        when(mockClientBuilder.baseUrl(anyString())).thenReturn(mockClientBuilder);
        when(mockClientBuilder.build()).thenReturn(mockClient);

        // Mock chat service
        when(mockClient.chat()).thenReturn(mockChatService);
        when(mockChatService.completions()).thenReturn(mockChatCompletionService);

        // Mock ChatModel
        mockedChatModel = mockStatic(ChatModel.class);
        ChatModel mockChatModelInstance = mock(ChatModel.class);
        mockedChatModel.when(() -> ChatModel.of(anyString())).thenReturn(mockChatModelInstance);
    }

    @AfterEach
    void tearDown() {
        if (mockedClientBuilder != null) {
            mockedClientBuilder.close();
        }
        if (mockedChatModel != null) {
            mockedChatModel.close();
        }
    }

    @Test
    @DisplayName("Should successfully create model with all parameters")
    void testModelCreationWithAllParameters() {
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(100).topP(0.9).build();

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .apiKey("test-key")
                        .modelName("gpt-4")
                        .baseUrl("https://api.openai.com")
                        .stream(true)
                        .defaultOptions(options)
                        .build();

        assertNotNull(model, "Model should be created");
        verify(mockClientBuilder).apiKey("test-key");
        verify(mockClientBuilder).baseUrl("https://api.openai.com");
        verify(mockClientBuilder).build();
    }

    @Test
    @DisplayName("Should create model without baseUrl")
    void testModelCreationWithoutBaseUrl() {
        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").build();

        assertNotNull(model, "Model should be created");
        verify(mockClientBuilder).apiKey("test-key");
        verify(mockClientBuilder, never()).baseUrl(anyString());
    }

    @Test
    @DisplayName("Should create model without apiKey")
    void testModelCreationWithoutApiKey() {
        OpenAIChatModel model = OpenAIChatModel.builder().modelName("gpt-4").build();

        assertNotNull(model, "Model should be created");
        verify(mockClientBuilder, never()).apiKey(anyString());
    }

    @Test
    @DisplayName("Should handle non-streaming completion successfully")
    void testNonStreamingCompletion() {
        // Create mock completion
        ChatCompletion mockCompletion = createMockChatCompletion("Hello, world!", null, 100, 50);

        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockCompletion);

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .build();

        FormattedMessageList messages =
                new FormattedMessageList(List.of(Map.of("role", "user", "content", "Hello")));

        Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);

        // Use collectList().block() to synchronously collect results
        List<ChatResponse> responses = responseFlux.collectList().block();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        ChatResponse response = responses.get(0);
        assertNotNull(response);
        assertFalse(response.getContent().isEmpty());
        assertInstanceOf(TextBlock.class, response.getContent().get(0));
        assertEquals("Hello, world!", ((TextBlock) response.getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should handle streaming completion successfully")
    void testStreamingCompletion() {
        // Create mock streaming response
        ChatCompletionChunk chunk1 = createMockChatCompletionChunk("Hello", null, null);
        ChatCompletionChunk chunk2 = createMockChatCompletionChunk(" world", null, null);
        ChatCompletionChunk chunk3 = createMockChatCompletionChunk("!", 100, 50);

        StreamResponse<ChatCompletionChunk> mockStreamResponse =
                createMockStreamResponse(Stream.of(chunk1, chunk2, chunk3));

        when(mockChatCompletionService.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockStreamResponse);

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(true)
                        .build();

        FormattedMessageList messages =
                new FormattedMessageList(List.of(Map.of("role", "user", "content", "Hello")));

        Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);

        // Use collectList().block() to synchronously collect all chunks
        List<ChatResponse> responses = responseFlux.collectList().block();

        assertNotNull(responses);
        assertTrue(responses.size() >= 2, "Should have at least 2 chunks");

        // Verify first chunks have content
        assertTrue(responses.get(0).getContent().size() > 0);
        assertTrue(responses.get(1).getContent().size() > 0);

        // Verify last chunk has usage info
        ChatResponse lastResponse = responses.get(responses.size() - 1);
        assertNotNull(lastResponse.getUsage());
        assertEquals(100, lastResponse.getUsage().getInputTokens());
        assertEquals(50, lastResponse.getUsage().getOutputTokens());
    }

    @Test
    @DisplayName("Should handle tool calls in non-streaming mode")
    void testNonStreamingWithToolCalls() {
        // Create mock tool call
        ChatCompletionMessageToolCall toolCall = mock(ChatCompletionMessageToolCall.class);
        ChatCompletionMessageFunctionToolCall functionToolCall =
                mock(ChatCompletionMessageFunctionToolCall.class);
        ChatCompletionMessageFunctionToolCall.Function function =
                mock(ChatCompletionMessageFunctionToolCall.Function.class);

        when(toolCall.function()).thenReturn(Optional.of(functionToolCall));
        when(functionToolCall.id()).thenReturn("call_123");
        when(functionToolCall.function()).thenReturn(function);
        when(function.name()).thenReturn("get_weather");
        when(function.arguments()).thenReturn("{\"location\":\"Boston\"}");

        List<ChatCompletionMessageToolCall> toolCalls = List.of(toolCall);

        ChatCompletion mockCompletion = createMockChatCompletion(null, toolCalls, 100, 50);

        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockCompletion);

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .build();

        FormattedMessageList messages =
                new FormattedMessageList(
                        List.of(Map.of("role", "user", "content", "What's the weather?")));

        List<ToolSchema> tools =
                List.of(
                        ToolSchema.builder()
                                .name("get_weather")
                                .description("Get weather")
                                .parameters(Map.of("type", "object"))
                                .build());

        Flux<ChatResponse> responseFlux = model.streamFlux(messages, tools, null);

        List<ChatResponse> responses = responseFlux.collectList().block();

        assertNotNull(responses);
        assertEquals(1, responses.size());
        ChatResponse response = responses.get(0);
        assertNotNull(response);
        assertFalse(response.getContent().isEmpty());
        assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        ToolUseBlock toolBlock = (ToolUseBlock) response.getContent().get(0);
        assertEquals("call_123", toolBlock.getId());
        assertEquals("get_weather", toolBlock.getName());
    }

    @Test
    @DisplayName("Should handle errors gracefully")
    void testErrorHandling() {
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenThrow(new RuntimeException("API Error"));

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .build();

        FormattedMessageList messages =
                new FormattedMessageList(List.of(Map.of("role", "user", "content", "Hello")));

        Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);

        // Verify that an error is thrown when blocking on the Flux
        assertThrows(
                RuntimeException.class,
                () -> responseFlux.collectList().block(),
                "Should propagate API error");
    }

    @Test
    @DisplayName("Should apply generation options correctly")
    void testGenerationOptions() {
        ChatCompletion mockCompletion = createMockChatCompletion("Response", null, 100, 50);
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(mockCompletion);

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.5)
                        .maxTokens(200)
                        .topP(0.8)
                        .frequencyPenalty(0.3)
                        .presencePenalty(0.2)
                        .build();

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .defaultOptions(options)
                        .build();

        FormattedMessageList messages =
                new FormattedMessageList(List.of(Map.of("role", "user", "content", "Test")));

        Flux<ChatResponse> responseFlux = model.streamFlux(messages, null, null);

        List<ChatResponse> responses = responseFlux.collectList().block();
        assertNotNull(responses);
        assertEquals(1, responses.size());

        verify(mockChatCompletionService).create(any(ChatCompletionCreateParams.class));
    }

    // Helper methods to create mock objects

    private ChatCompletion createMockChatCompletion(
            String content,
            List<ChatCompletionMessageToolCall> toolCalls,
            int promptTokens,
            int completionTokens) {

        ChatCompletion completion = mock(ChatCompletion.class);
        ChatCompletion.Choice choice = mock(ChatCompletion.Choice.class);
        ChatCompletionMessage message = mock(ChatCompletionMessage.class);
        CompletionUsage usage = mock(CompletionUsage.class);

        when(completion.id()).thenReturn("comp_123");
        when(completion.choices()).thenReturn(List.of(choice));
        when(choice.message()).thenReturn(message);

        if (content != null) {
            when(message.content()).thenReturn(Optional.of(content));
        } else {
            when(message.content()).thenReturn(Optional.empty());
        }

        if (toolCalls != null) {
            when(message.toolCalls()).thenReturn(Optional.of(toolCalls));
        } else {
            when(message.toolCalls()).thenReturn(Optional.empty());
        }

        when(usage.promptTokens()).thenReturn((long) promptTokens);
        when(usage.completionTokens()).thenReturn((long) completionTokens);
        when(completion.usage()).thenReturn(Optional.of(usage));

        return completion;
    }

    private ChatCompletionChunk createMockChatCompletionChunk(
            String content, Integer promptTokens, Integer completionTokens) {

        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);

        when(chunk.id()).thenReturn("chunk_123");
        when(chunk.choices()).thenReturn(List.of(choice));
        when(choice.delta()).thenReturn(delta);

        if (content != null) {
            when(delta.content()).thenReturn(Optional.of(content));
        } else {
            when(delta.content()).thenReturn(Optional.empty());
        }

        when(delta.toolCalls()).thenReturn(Optional.empty());

        if (promptTokens != null && completionTokens != null) {
            CompletionUsage usage = mock(CompletionUsage.class);
            when(usage.promptTokens()).thenReturn((long) promptTokens);
            when(usage.completionTokens()).thenReturn((long) completionTokens);
            when(chunk.usage()).thenReturn(Optional.of(usage));
        } else {
            when(chunk.usage()).thenReturn(Optional.empty());
        }

        return chunk;
    }

    @SuppressWarnings("unchecked")
    private StreamResponse<ChatCompletionChunk> createMockStreamResponse(
            Stream<ChatCompletionChunk> chunks) {
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(chunks);
        return streamResponse;
    }
}
