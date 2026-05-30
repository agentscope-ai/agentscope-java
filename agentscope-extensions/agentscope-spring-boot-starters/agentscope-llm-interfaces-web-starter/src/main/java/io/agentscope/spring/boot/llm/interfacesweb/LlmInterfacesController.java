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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessageConverter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessagesRequest;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicResponseBuilder;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicStreamEvent;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicToolConverter;
import io.agentscope.core.llm.interfacesweb.common.ProtocolJsonUtils;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesMessageConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesRequest;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesResponseBuilder;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesStreamEvent;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesToolConverter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Aggregated OpenAI Chat, OpenAI Responses, and Anthropic Messages endpoints. */
@RestController
@RequestMapping
public class LlmInterfacesController {

    private final ObjectProvider<ReActAgent> agentProvider;
    private final ObjectProvider<Model> modelProvider;
    private final LlmInterfacesProperties properties;
    private final ChatMessageConverter chatMessageConverter;
    private final OpenAIToolConverter openAIToolConverter;
    private final ChatCompletionsResponseBuilder chatResponseBuilder;
    private final ChatCompletionsStreamingAdapter chatStreamingAdapter;
    private final ResponsesMessageConverter responsesMessageConverter;
    private final ResponsesToolConverter responsesToolConverter;
    private final ResponsesResponseBuilder responsesResponseBuilder;
    private final ResponsesStreamingAdapter responsesStreamingAdapter;
    private final AnthropicMessageConverter anthropicMessageConverter;
    private final AnthropicToolConverter anthropicToolConverter;
    private final AnthropicResponseBuilder anthropicResponseBuilder;
    private final AnthropicStreamingAdapter anthropicStreamingAdapter;

    public LlmInterfacesController(
            ObjectProvider<ReActAgent> agentProvider,
            ObjectProvider<Model> modelProvider,
            LlmInterfacesProperties properties,
            ChatMessageConverter chatMessageConverter,
            OpenAIToolConverter openAIToolConverter,
            ChatCompletionsResponseBuilder chatResponseBuilder,
            ChatCompletionsStreamingAdapter chatStreamingAdapter,
            ResponsesMessageConverter responsesMessageConverter,
            ResponsesToolConverter responsesToolConverter,
            ResponsesResponseBuilder responsesResponseBuilder,
            ResponsesStreamingAdapter responsesStreamingAdapter,
            AnthropicMessageConverter anthropicMessageConverter,
            AnthropicToolConverter anthropicToolConverter,
            AnthropicResponseBuilder anthropicResponseBuilder,
            AnthropicStreamingAdapter anthropicStreamingAdapter) {
        this.agentProvider = agentProvider;
        this.modelProvider = modelProvider;
        this.properties = properties;
        this.chatMessageConverter = chatMessageConverter;
        this.openAIToolConverter = openAIToolConverter;
        this.chatResponseBuilder = chatResponseBuilder;
        this.chatStreamingAdapter = chatStreamingAdapter;
        this.responsesMessageConverter = responsesMessageConverter;
        this.responsesToolConverter = responsesToolConverter;
        this.responsesResponseBuilder = responsesResponseBuilder;
        this.responsesStreamingAdapter = responsesStreamingAdapter;
        this.anthropicMessageConverter = anthropicMessageConverter;
        this.anthropicToolConverter = anthropicToolConverter;
        this.anthropicResponseBuilder = anthropicResponseBuilder;
        this.anthropicStreamingAdapter = anthropicStreamingAdapter;
    }

    @PostMapping(
            value = "${agentscope.llm-interfaces.base-path:/v1}/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletions(@RequestBody ChatCompletionsRequest request) {
        if (!properties.getChat().isEnabled()) {
            return disabled("chat");
        }
        String requestId = "chatcmpl-" + UUID.randomUUID();
        if (request.getModel() == null || request.getModel().isBlank()) {
            request.setModel(activeModelName());
        }

        try {
            ReActAgent agent = newAgent();
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                agent.getToolkit()
                        .registerSchemas(
                                openAIToolConverter.convertToToolSchemas(request.getTools()));
            }
            List<Msg> messages = chatMessageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                return Mono.error(new IllegalArgumentException("At least one message is required"));
            }
            if (Boolean.TRUE.equals(request.getStream())) {
                Flux<ServerSentEvent<String>> stream =
                        chatStreamingAdapter.stream(agent, messages, requestId, request.getModel())
                                .map(this::chatChunkToSse)
                                .concatWith(
                                        Flux.just(
                                                ServerSentEvent.<String>builder()
                                                        .data("[DONE]")
                                                        .build()));
                return sse(stream);
            }
            return agent.call(messages)
                    .map(reply -> chatResponseBuilder.buildResponse(request, reply, requestId))
                    .onErrorResume(
                            error ->
                                    Mono.just(
                                            chatResponseBuilder.buildErrorResponse(
                                                    request, error, requestId)));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @PostMapping(
            value = "${agentscope.llm-interfaces.base-path:/v1}/responses",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object responses(@RequestBody ResponsesRequest request) {
        if (!properties.getResponses().isEnabled()) {
            return disabled("responses");
        }
        String responseId = "resp_" + UUID.randomUUID();
        if (request.getModel() == null || request.getModel().isBlank()) {
            request.setModel(activeModelName());
        }

        try {
            ReActAgent agent = newAgent();
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                agent.getToolkit()
                        .registerSchemas(responsesToolConverter.convert(request.getTools()));
            }
            List<Msg> messages = responsesMessageConverter.convert(request);
            if (Boolean.TRUE.equals(request.getStream())) {
                Flux<ServerSentEvent<String>> stream =
                        responsesStreamingAdapter.stream(agent, messages, request, responseId)
                                .map(this::responsesEventToSse)
                                .onErrorResume(
                                        error ->
                                                Flux.just(
                                                        responsesEventToSse(
                                                                responsesStreamingAdapter
                                                                        .errorEvent(
                                                                                error,
                                                                                responseId))));
                return sse(stream);
            }
            return agent.call(messages)
                    .map(
                            reply ->
                                    responsesResponseBuilder.buildResponse(
                                            request, reply, responseId))
                    .onErrorResume(
                            error ->
                                    Mono.just(
                                            responsesResponseBuilder.buildErrorResponse(
                                                    request, error, responseId)));
        } catch (Exception e) {
            return Mono.just(responsesResponseBuilder.buildErrorResponse(request, e, responseId));
        }
    }

    @PostMapping(
            value = "${agentscope.llm-interfaces.base-path:/v1}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object anthropicMessages(@RequestBody AnthropicMessagesRequest request) {
        if (!properties.getAnthropic().isEnabled()) {
            return disabled("anthropic");
        }
        String messageId = "msg_" + UUID.randomUUID();
        if (request.getModel() == null || request.getModel().isBlank()) {
            request.setModel(activeModelName());
        }

        try {
            ReActAgent agent = newAgent();
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                agent.getToolkit()
                        .registerSchemas(anthropicToolConverter.convert(request.getTools()));
            }
            List<Msg> messages = anthropicMessageConverter.convert(request);
            if (Boolean.TRUE.equals(request.getStream())) {
                Flux<ServerSentEvent<String>> stream =
                        anthropicStreamingAdapter.stream(agent, messages, request, messageId)
                                .map(this::anthropicEventToSse)
                                .onErrorResume(
                                        error ->
                                                Flux.just(
                                                        anthropicEventToSse(
                                                                anthropicStreamingAdapter
                                                                        .errorEvent(error))));
                return sse(stream);
            }
            return agent.call(messages)
                    .<Object>map(
                            reply ->
                                    anthropicResponseBuilder.buildResponse(
                                            request, reply, messageId))
                    .onErrorResume(error -> Mono.just(anthropicResponseBuilder.buildError(error)));
        } catch (Exception e) {
            return Mono.just(anthropicResponseBuilder.buildError(e));
        }
    }

    @GetMapping(
            value = "${agentscope.llm-interfaces.base-path:/v1}/models",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> models() {
        String modelName = activeModelName();
        return Map.of(
                "object",
                "list",
                "data",
                List.of(Map.of("id", modelName, "object", "model", "owned_by", "agentscope")));
    }

    private ReActAgent newAgent() {
        ReActAgent agent = agentProvider.getObject();
        if (agent == null) {
            throw new IllegalStateException("Failed to create ReActAgent");
        }
        return agent;
    }

    private String activeModelName() {
        Model model = modelProvider.getIfAvailable();
        return model != null ? model.getModelName() : "agentscope-agent";
    }

    private ResponseEntity<Map<String, Object>> disabled(String endpoint) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        Map.of(
                                "error",
                                Map.of(
                                        "type",
                                        "not_found_error",
                                        "message",
                                        endpoint + " endpoint is disabled")));
    }

    private ResponseEntity<Flux<ServerSentEvent<String>>> sse(
            Flux<ServerSentEvent<String>> stream) {
        return ResponseEntity.ok()
                .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(stream);
    }

    private ServerSentEvent<String> chatChunkToSse(ChatCompletionsChunk chunk) {
        return ServerSentEvent.<String>builder().data(json(chunk)).build();
    }

    private ServerSentEvent<String> responsesEventToSse(ResponsesStreamEvent event) {
        return ServerSentEvent.<String>builder().event(event.getType()).data(json(event)).build();
    }

    private ServerSentEvent<String> anthropicEventToSse(AnthropicStreamEvent event) {
        return ServerSentEvent.<String>builder().event(event.getType()).data(json(event)).build();
    }

    private String json(Object value) {
        try {
            return ProtocolJsonUtils.OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Serialization error\"}";
        }
    }
}
