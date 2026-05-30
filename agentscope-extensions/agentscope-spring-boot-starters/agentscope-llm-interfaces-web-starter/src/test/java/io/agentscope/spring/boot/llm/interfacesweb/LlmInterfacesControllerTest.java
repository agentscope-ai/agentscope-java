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
package io.agentscope.spring.boot.llm.interfacesweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.chat.completions.model.OpenAITool;
import io.agentscope.core.chat.completions.model.OpenAIToolFunction;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessageConverter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessagesRequest;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessagesResponse;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicResponseBuilder;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicStreamEvent;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicToolConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesMessageConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesRequest;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesResponse;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesResponseBuilder;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesStreamEvent;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesToolConverter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("LlmInterfacesController Tests")
class LlmInterfacesControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmInterfacesController controller;
    private ReActAgent agent;
    private LlmInterfacesProperties properties;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<ReActAgent> agentProvider = mock(ObjectProvider.class);
        ObjectProvider<Model> modelProvider = mock(ObjectProvider.class);
        Model model = mock(Model.class);
        agent = mock(ReActAgent.class);

        when(agentProvider.getObject()).thenReturn(agent);
        when(modelProvider.getIfAvailable()).thenReturn(model);
        when(model.getModelName()).thenReturn("active-model");

        properties = new LlmInterfacesProperties();
        controller = newController(agentProvider, modelProvider, properties);
    }

    private LlmInterfacesController newController(
            ObjectProvider<ReActAgent> agentProvider,
            ObjectProvider<Model> modelProvider,
            LlmInterfacesProperties properties) {
        ResponsesResponseBuilder responsesResponseBuilder = new ResponsesResponseBuilder();
        AnthropicResponseBuilder anthropicResponseBuilder = new AnthropicResponseBuilder();
        return new LlmInterfacesController(
                agentProvider,
                modelProvider,
                properties,
                new ChatMessageConverter(),
                new OpenAIToolConverter(),
                new ChatCompletionsResponseBuilder(),
                new ChatCompletionsStreamingAdapter(),
                new ResponsesMessageConverter(),
                new ResponsesToolConverter(),
                responsesResponseBuilder,
                new ResponsesStreamingAdapter(responsesResponseBuilder),
                new AnthropicMessageConverter(),
                new AnthropicToolConverter(),
                anthropicResponseBuilder,
                new AnthropicStreamingAdapter(anthropicResponseBuilder));
    }

    @Test
    @DisplayName("Should handle Chat Completions requests")
    void shouldHandleChatCompletionsRequests() {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setMessages(List.of(new ChatMessage("user", "Hello")));
        request.setStream(false);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.call(anyList())).thenReturn(Mono.just(reply));

        Object result = controller.chatCompletions(request);

        @SuppressWarnings("unchecked")
        Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            assertEquals("active-model", response.getModel());
                            assertEquals(
                                    "Hi", response.getChoices().get(0).getMessage().getContent());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should build Chat Completions error responses")
    void shouldBuildChatCompletionsErrorResponses() {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setMessages(List.of(new ChatMessage("user", "Hello")));
        request.setStream(false);
        when(agent.call(anyList())).thenReturn(Mono.error(new RuntimeException("boom")));

        Object result = controller.chatCompletions(request);

        @SuppressWarnings("unchecked")
        Mono<ChatCompletionsResponse> responseMono = (Mono<ChatCompletionsResponse>) result;
        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            assertEquals("active-model", response.getModel());
                            assertEquals("error", response.getChoices().get(0).getFinishReason());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should register Chat Completions tools")
    void shouldRegisterChatCompletionsTools() {
        Toolkit toolkit = new Toolkit();
        when(agent.getToolkit()).thenReturn(toolkit);
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setMessages(List.of(new ChatMessage("user", "Hello")));
        request.setStream(false);
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("lookup");
        function.setDescription("Lookup data");
        function.setParameters(Map.of("type", "object"));
        request.setTools(List.of(new OpenAITool(function)));
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.call(anyList())).thenReturn(Mono.just(reply));

        @SuppressWarnings("unchecked")
        Mono<ChatCompletionsResponse> responseMono =
                (Mono<ChatCompletionsResponse>) controller.chatCompletions(request);

        StepVerifier.create(responseMono).expectNextCount(1).verifyComplete();
        assertNotNull(toolkit.getTool("lookup"));
    }

    @Test
    @DisplayName("Should reject empty Chat Completions messages")
    void shouldRejectEmptyChatMessages() {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setMessages(List.of());

        @SuppressWarnings("unchecked")
        Mono<Object> responseMono = (Mono<Object>) controller.chatCompletions(request);

        StepVerifier.create(responseMono)
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalArgumentException
                                        && error.getMessage()
                                                .contains("At least one message is required"))
                .verify();
    }

    @Test
    @DisplayName("Should surface Chat Completions conversion errors")
    void shouldSurfaceChatConversionErrors() {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setMessages(List.of(new ChatMessage("alien", "Hello")));

        @SuppressWarnings("unchecked")
        Mono<Object> responseMono = (Mono<Object>) controller.chatCompletions(request);

        StepVerifier.create(responseMono)
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalArgumentException
                                        && error.getMessage().contains("Unknown message role"))
                .verify();
    }

    @Test
    @DisplayName("Should emit Chat Completions SSE done sentinel")
    void shouldEmitChatCompletionsDoneSentinel() {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setMessages(List.of(new ChatMessage("user", "Hello")));
        request.setStream(true);
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hi").build())
                        .build();
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, reply, true)));

        Object result = controller.chatCompletions(request);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, result);
        StepVerifier.create(response.getBody())
                .expectNextMatches(event -> event.data().contains("chat.completion.chunk"))
                .expectNextMatches(event -> event.data().contains("\"finish_reason\":\"stop\""))
                .expectNextMatches(event -> "[DONE]".equals(event.data()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle Responses create requests")
    void shouldHandleResponsesRequests() throws Exception {
        ResponsesRequest request = new ResponsesRequest();
        request.setInput(objectMapper.readTree("\"Hello\""));
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.call(anyList())).thenReturn(Mono.just(reply));

        Object result = controller.responses(request);

        @SuppressWarnings("unchecked")
        Mono<ResponsesResponse> responseMono = (Mono<ResponsesResponse>) result;
        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            assertEquals("active-model", response.getModel());
                            assertEquals("completed", response.getStatus());
                            assertEquals("Hi", response.getOutputText());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should register Responses tools")
    void shouldRegisterResponsesTools() throws Exception {
        Toolkit toolkit = new Toolkit();
        when(agent.getToolkit()).thenReturn(toolkit);
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "input": "Hello",
                          "tools": [
                            {
                              "type": "function",
                              "name": "lookup",
                              "description": "Lookup data",
                              "parameters": {"type": "object"}
                            }
                          ]
                        }
                        """,
                        ResponsesRequest.class);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.call(anyList())).thenReturn(Mono.just(reply));

        @SuppressWarnings("unchecked")
        Mono<ResponsesResponse> responseMono =
                (Mono<ResponsesResponse>) controller.responses(request);

        StepVerifier.create(responseMono).expectNextCount(1).verifyComplete();
        assertNotNull(toolkit.getTool("lookup"));
    }

    @Test
    @DisplayName("Should build Responses errors for agent failures")
    void shouldBuildResponsesErrorsForAgentFailures() throws Exception {
        ResponsesRequest request = new ResponsesRequest();
        request.setInput(objectMapper.readTree("\"Hello\""));
        when(agent.call(anyList())).thenReturn(Mono.error(new RuntimeException("boom")));

        @SuppressWarnings("unchecked")
        Mono<ResponsesResponse> responseMono =
                (Mono<ResponsesResponse>) controller.responses(request);

        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            assertEquals("failed", response.getStatus());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> error = (Map<String, Object>) response.getError();
                            assertEquals("server_error", error.get("type"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should serialize Responses SSE failures defensively")
    void shouldSerializeResponsesSseFailuresDefensively() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReActAgent> agentProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Model> modelProvider = mock(ObjectProvider.class);
        ReActAgent localAgent = mock(ReActAgent.class);
        when(agentProvider.getObject()).thenReturn(localAgent);
        when(modelProvider.getIfAvailable()).thenReturn(null);
        ResponsesStreamingAdapter streamingAdapter = mock(ResponsesStreamingAdapter.class);
        ResponsesStreamEvent event = new ResponsesStreamEvent("response.created");
        event.setError(new Object());
        when(streamingAdapter.stream(any(), anyList(), any(), any())).thenReturn(Flux.just(event));
        LlmInterfacesController localController =
                new LlmInterfacesController(
                        agentProvider,
                        modelProvider,
                        new LlmInterfacesProperties(),
                        new ChatMessageConverter(),
                        new OpenAIToolConverter(),
                        new ChatCompletionsResponseBuilder(),
                        new ChatCompletionsStreamingAdapter(),
                        new ResponsesMessageConverter(),
                        new ResponsesToolConverter(),
                        new ResponsesResponseBuilder(),
                        streamingAdapter,
                        new AnthropicMessageConverter(),
                        new AnthropicToolConverter(),
                        new AnthropicResponseBuilder(),
                        new AnthropicStreamingAdapter(new AnthropicResponseBuilder()));
        ResponsesRequest request = new ResponsesRequest();
        request.setInput(objectMapper.readTree("\"Hello\""));
        request.setStream(true);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, localController.responses(request));

        StepVerifier.create(response.getBody())
                .expectNextMatches(sse -> "{\"error\":\"Serialization error\"}".equals(sse.data()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should emit Responses SSE semantic events")
    void shouldEmitResponsesSseEvents() throws Exception {
        ResponsesRequest request = new ResponsesRequest();
        request.setInput(objectMapper.readTree("\"Hello\""));
        request.setStream(true);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, reply, true)));

        Object result = controller.responses(request);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, result);
        StepVerifier.create(response.getBody())
                .expectNextMatches(event -> "response.created".equals(event.event()))
                .expectNextMatches(event -> "response.output_text.delta".equals(event.event()))
                .expectNextMatches(event -> "response.completed".equals(event.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should emit Responses SSE errors")
    void shouldEmitResponsesSseErrors() throws Exception {
        ResponsesRequest request = new ResponsesRequest();
        request.setInput(objectMapper.readTree("\"Hello\""));
        request.setStream(true);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        Object result = controller.responses(request);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, result);
        StepVerifier.create(response.getBody())
                .expectNextMatches(event -> "response.created".equals(event.event()))
                .expectNextMatches(event -> "response.failed".equals(event.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should build Responses error shapes for invalid requests")
    void shouldBuildResponsesErrorShapesForInvalidRequests() {
        ResponsesRequest request = new ResponsesRequest();

        Object result = controller.responses(request);

        @SuppressWarnings("unchecked")
        Mono<ResponsesResponse> responseMono = (Mono<ResponsesResponse>) result;
        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            assertEquals("failed", response.getStatus());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> error = (Map<String, Object>) response.getError();
                            assertEquals("server_error", error.get("type"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle Anthropic Messages requests")
    void shouldHandleAnthropicRequests() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "messages": [{"role": "user", "content": "Hello"}]
                        }
                        """,
                        AnthropicMessagesRequest.class);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.call(anyList())).thenReturn(Mono.just(reply));

        Object result = controller.anthropicMessages(request);

        @SuppressWarnings("unchecked")
        Mono<Object> responseMono = (Mono<Object>) result;
        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            AnthropicMessagesResponse message =
                                    assertInstanceOf(AnthropicMessagesResponse.class, response);
                            assertEquals("active-model", message.getModel());
                            assertEquals("Hi", message.getContent().get(0).get("text"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should register Anthropic tools")
    void shouldRegisterAnthropicTools() throws Exception {
        Toolkit toolkit = new Toolkit();
        when(agent.getToolkit()).thenReturn(toolkit);
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "messages": [{"role": "user", "content": "Hello"}],
                          "tools": [
                            {
                              "name": "lookup",
                              "description": "Lookup data",
                              "input_schema": {"type": "object"}
                            }
                          ]
                        }
                        """,
                        AnthropicMessagesRequest.class);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.call(anyList())).thenReturn(Mono.just(reply));

        @SuppressWarnings("unchecked")
        Mono<Object> responseMono = (Mono<Object>) controller.anthropicMessages(request);

        StepVerifier.create(responseMono).expectNextCount(1).verifyComplete();
        assertNotNull(toolkit.getTool("lookup"));
    }

    @Test
    @DisplayName("Should build Anthropic errors for agent failures")
    void shouldBuildAnthropicErrorsForAgentFailures() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {"messages": [{"role": "user", "content": "Hello"}]}
                        """,
                        AnthropicMessagesRequest.class);
        when(agent.call(anyList())).thenReturn(Mono.error(new RuntimeException("boom")));

        @SuppressWarnings("unchecked")
        Mono<Object> responseMono = (Mono<Object>) controller.anthropicMessages(request);

        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> body = (Map<String, Object>) response;
                            assertEquals("error", body.get("type"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should serialize Anthropic SSE failures defensively")
    void shouldSerializeAnthropicSseFailuresDefensively() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReActAgent> agentProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Model> modelProvider = mock(ObjectProvider.class);
        ReActAgent localAgent = mock(ReActAgent.class);
        when(agentProvider.getObject()).thenReturn(localAgent);
        when(modelProvider.getIfAvailable()).thenReturn(null);
        AnthropicStreamingAdapter streamingAdapter = mock(AnthropicStreamingAdapter.class);
        AnthropicStreamEvent event = new AnthropicStreamEvent("message_start");
        event.setDelta(Map.of("bad", new Object()));
        when(streamingAdapter.stream(any(), anyList(), any(), any())).thenReturn(Flux.just(event));
        AnthropicResponseBuilder responseBuilder = new AnthropicResponseBuilder();
        LlmInterfacesController localController =
                new LlmInterfacesController(
                        agentProvider,
                        modelProvider,
                        new LlmInterfacesProperties(),
                        new ChatMessageConverter(),
                        new OpenAIToolConverter(),
                        new ChatCompletionsResponseBuilder(),
                        new ChatCompletionsStreamingAdapter(),
                        new ResponsesMessageConverter(),
                        new ResponsesToolConverter(),
                        new ResponsesResponseBuilder(),
                        new ResponsesStreamingAdapter(new ResponsesResponseBuilder()),
                        new AnthropicMessageConverter(),
                        new AnthropicToolConverter(),
                        responseBuilder,
                        streamingAdapter);
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {"stream": true, "messages": [{"role": "user", "content": "Hello"}]}
                        """,
                        AnthropicMessagesRequest.class);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, localController.anthropicMessages(request));

        StepVerifier.create(response.getBody())
                .expectNextMatches(sse -> "{\"error\":\"Serialization error\"}".equals(sse.data()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should emit Anthropic SSE semantic events")
    void shouldEmitAnthropicSseEvents() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "stream": true,
                          "messages": [{"role": "user", "content": "Hello"}]
                        }
                        """,
                        AnthropicMessagesRequest.class);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hi").build();
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, reply, true)));

        Object result = controller.anthropicMessages(request);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, result);
        StepVerifier.create(response.getBody())
                .expectNextMatches(event -> "message_start".equals(event.event()))
                .expectNextMatches(event -> "content_block_start".equals(event.event()))
                .expectNextMatches(event -> "content_block_delta".equals(event.event()))
                .expectNextMatches(event -> "content_block_stop".equals(event.event()))
                .expectNextMatches(event -> "message_delta".equals(event.event()))
                .expectNextMatches(event -> "message_stop".equals(event.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should emit Anthropic SSE errors")
    void shouldEmitAnthropicSseErrors() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "stream": true,
                          "messages": [{"role": "user", "content": "Hello"}]
                        }
                        """,
                        AnthropicMessagesRequest.class);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        Object result = controller.anthropicMessages(request);

        ResponseEntity<Flux<ServerSentEvent<String>>> response =
                assertInstanceOf(ResponseEntity.class, result);
        StepVerifier.create(response.getBody())
                .expectNextMatches(event -> "message_start".equals(event.event()))
                .expectNextMatches(event -> "error".equals(event.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should build Anthropic error shapes for invalid requests")
    void shouldBuildAnthropicErrorShapesForInvalidRequests() {
        AnthropicMessagesRequest request = new AnthropicMessagesRequest();

        Object result = controller.anthropicMessages(request);

        @SuppressWarnings("unchecked")
        Mono<Object> responseMono = (Mono<Object>) result;
        StepVerifier.create(responseMono)
                .assertNext(
                        response -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> body = (Map<String, Object>) response;
                            assertEquals("error", body.get("type"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should expose active model through models endpoint")
    void shouldExposeModelsEndpoint() {
        Map<String, Object> response = controller.models();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) response.get("data");
        assertEquals("list", response.get("object"));
        assertEquals("active-model", models.get(0).get("id"));
    }

    @Test
    @DisplayName("Should fall back to default model name when model bean is absent")
    void shouldFallBackToDefaultModelNameWhenModelBeanIsAbsent() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReActAgent> agentProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Model> modelProvider = mock(ObjectProvider.class);
        when(modelProvider.getIfAvailable()).thenReturn(null);
        LlmInterfacesController noModelController =
                newController(agentProvider, modelProvider, new LlmInterfacesProperties());

        Map<String, Object> response = noModelController.models();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) response.get("data");
        assertEquals("agentscope-agent", models.get(0).get("id"));
    }

    @Test
    @DisplayName("Should return disabled endpoint errors")
    void shouldReturnDisabledEndpointErrors() {
        properties.getChat().setEnabled(false);
        properties.getResponses().setEnabled(false);
        properties.getAnthropic().setEnabled(false);

        ResponseEntity<?> chat =
                assertInstanceOf(
                        ResponseEntity.class,
                        controller.chatCompletions(new ChatCompletionsRequest()));
        ResponseEntity<?> responses =
                assertInstanceOf(
                        ResponseEntity.class, controller.responses(new ResponsesRequest()));
        ResponseEntity<?> anthropic =
                assertInstanceOf(
                        ResponseEntity.class,
                        controller.anthropicMessages(new AnthropicMessagesRequest()));

        assertEquals(HttpStatus.NOT_FOUND, chat.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, responses.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, anthropic.getStatusCode());
    }

    @Test
    @DisplayName("Should convert missing agent into protocol errors")
    void shouldConvertMissingAgentIntoProtocolErrors() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReActAgent> agentProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Model> modelProvider = mock(ObjectProvider.class);
        when(agentProvider.getObject()).thenReturn(null);
        when(modelProvider.getIfAvailable()).thenReturn(null);
        LlmInterfacesController noAgentController =
                newController(agentProvider, modelProvider, new LlmInterfacesProperties());
        ResponsesRequest responsesRequest = new ResponsesRequest();
        responsesRequest.setInput(objectMapper.readTree("\"Hello\""));
        AnthropicMessagesRequest anthropicRequest =
                objectMapper.readValue(
                        """
                        {"messages": [{"role": "user", "content": "Hello"}]}
                        """,
                        AnthropicMessagesRequest.class);

        @SuppressWarnings("unchecked")
        Mono<ResponsesResponse> responsesMono =
                (Mono<ResponsesResponse>) noAgentController.responses(responsesRequest);
        @SuppressWarnings("unchecked")
        Mono<Object> anthropicMono =
                (Mono<Object>) noAgentController.anthropicMessages(anthropicRequest);

        StepVerifier.create(responsesMono)
                .expectNextMatches(response -> "failed".equals(response.getStatus()))
                .verifyComplete();
        StepVerifier.create(anthropicMono)
                .expectNextMatches(response -> ((Map<?, ?>) response).get("type").equals("error"))
                .verifyComplete();
    }
}
