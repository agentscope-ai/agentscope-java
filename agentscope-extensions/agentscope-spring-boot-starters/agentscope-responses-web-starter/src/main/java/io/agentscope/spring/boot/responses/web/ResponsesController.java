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
package io.agentscope.spring.boot.responses.web;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.converter.ResponsesConversionResult;
import io.agentscope.core.responses.converter.ResponsesGenerationOptionsConverter;
import io.agentscope.core.responses.converter.ResponsesInputConverter;
import io.agentscope.core.responses.converter.ResponsesToolConverter;
import io.agentscope.core.responses.converter.ResponsesValidationException;
import io.agentscope.core.responses.hook.ResponsesRequestHook;
import io.agentscope.core.responses.model.ResponsesDeletionStatus;
import io.agentscope.core.responses.model.ResponsesList;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.model.ResponsesTokenCount;
import io.agentscope.spring.boot.responses.service.ResponsesStateService;
import io.agentscope.spring.boot.responses.service.ResponsesStateService.PreparedRequest;
import io.agentscope.spring.boot.responses.service.ResponsesStreamingService;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HTTP controller exposing a Responses API compatible with OpenAI's standard.
 *
 * <p>This controller exposes both the create endpoint and stateful resources backed by {@link
 * ResponsesStateService}. The default state service is in-memory; applications can replace it with a
 * durable bean when they need persistence across process restarts or multiple application instances.
 *
 * <p><b>How It Works:</b>
 *
 * <ol>
 *   <li>Client sends {@code input}, optional {@code instructions}, optional tools, and optional
 *       output formatting
 *   <li>Controller converts the HTTP DTO into AgentScope messages and request-scoped options
 *   <li>Server creates a fresh {@link ReActAgent}, registers schema-only tools when requested, and
 *       attaches per-request options
 *   <li>Agent runs in normal, structured-output, or streaming mode
 *   <li>Response builder returns a Responses-shaped JSON object or Responses-style SSE events
 * </ol>
 *
 * <p><b>Tool Calls:</b>
 *
 * <p>Function tools are registered as schema-only tools. When the model chooses a tool, the
 * response includes a {@code function_call} output item. The client executes the tool externally
 * and sends a {@code function_call_output} item in the next request.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Non-streaming JSON response by default
 *   <li>SSE streaming when {@code stream=true} or the streaming route is selected
 *   <li>Stored responses, previous-response context, background responses, and conversations
 *   <li>Text, image, audio, video, file-reference, function-call, and opaque item input handling
 *   <li>Schema-only function tool calls and client-provided tool outputs
 *   <li>JSON Schema structured output in both non-streaming and streaming modes
 * </ul>
 */
@RestController
@RequestMapping
public class ResponsesController {

    private static final Logger log = LoggerFactory.getLogger(ResponsesController.class);

    private final ObjectProvider<ReActAgent> agentProvider;
    private final ResponsesInputConverter inputConverter;
    private final ResponsesToolConverter toolConverter;
    private final ResponsesGenerationOptionsConverter generationOptionsConverter;
    private final ResponsesResponseBuilder responseBuilder;
    private final ResponsesStreamingService streamingService;
    private final ResponsesStateService stateService;

    /**
     * Constructs a new {@code ResponsesController}.
     *
     * @param agentProvider Provider for creating prototype-scoped agent instances
     * @param inputConverter Converter for Responses request DTOs to AgentScope messages
     * @param toolConverter Converter for Responses function tools to ToolSchema
     * @param generationOptionsConverter Converter for request generation parameters
     * @param responseBuilder Builder for Responses API response objects
     * @param streamingService Service for converting stream events to Spring SSE events
     * @param stateService Service for stored responses, previous response context, and
     *     conversations
     */
    public ResponsesController(
            ObjectProvider<ReActAgent> agentProvider,
            ResponsesInputConverter inputConverter,
            ResponsesToolConverter toolConverter,
            ResponsesGenerationOptionsConverter generationOptionsConverter,
            ResponsesResponseBuilder responseBuilder,
            ResponsesStreamingService streamingService,
            ResponsesStateService stateService) {
        this.agentProvider = agentProvider;
        this.inputConverter = inputConverter;
        this.toolConverter = toolConverter;
        this.generationOptionsConverter = generationOptionsConverter;
        this.responseBuilder = responseBuilder;
        this.streamingService = streamingService;
        this.stateService = stateService;
    }

    /**
     * Responses creation endpoint.
     *
     * <p>Processes the request input and returns a Responses API object. If the request has {@code
     * stream=true}, this method automatically switches to streaming mode even without an {@code
     * Accept: text/event-stream} header for better client compatibility.
     *
     * @param request The Responses API request
     * @return A {@link Mono} containing a {@link ResponsesResponse}, or a {@link Flux} of {@link
     *     ServerSentEvent} if streaming is requested
     */
    @PostMapping(
            value = "${agentscope.responses.base-path:/v1/responses}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createResponse(@Valid @RequestBody ResponsesRequest request) {
        String responseId = responseId();
        try {
            PreparedRequest prepared = stateService.prepare(request);
            ResponsesConversionResult conversion = inputConverter.convert(prepared.request());
            if (Boolean.TRUE.equals(prepared.request().getStream())) {
                Flux<ServerSentEvent<String>> stream =
                        createResponseStream(prepared.request(), conversion, responseId, prepared);
                return ResponseEntity.ok()
                        .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(stream);
            }
            if (Boolean.TRUE.equals(prepared.request().getBackground())) {
                return createBackgroundResponse(
                        prepared.request(), conversion, responseId, prepared);
            }
            return createNonStreamingResponse(prepared.request(), conversion, responseId)
                    .map(response -> stateService.save(response, prepared));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(responseBuilder.buildInvalidRequestError(e));
        }
    }

    /**
     * Streaming Responses endpoint.
     *
     * <p>Processes the request input and streams Responses API Server-Sent Events. This route also
     * supports {@code text.format.type=json_schema}; the structured result is emitted as standard
     * Responses output text events.
     *
     * @param request The Responses API request
     * @return A {@link ResponseEntity} containing a {@link Flux} of {@link ServerSentEvent} objects
     */
    @PostMapping(
            value = "${agentscope.responses.base-path:/v1/responses}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object createResponseStream(@Valid @RequestBody ResponsesRequest request) {
        String responseId = responseId();
        try {
            PreparedRequest prepared = stateService.prepare(request);
            ResponsesConversionResult conversion = inputConverter.convert(prepared.request());
            return ResponseEntity.ok()
                    .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(
                            createResponseStream(
                                    prepared.request(), conversion, responseId, prepared));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(responseBuilder.buildInvalidRequestError(e));
        }
    }

    /**
     * Retrieve a stored response by ID.
     *
     * @param responseId Response ID
     * @return Stored response, or a structured not-found error
     */
    @GetMapping(value = "${agentscope.responses.base-path:/v1/responses}/{responseId}")
    public Object retrieveResponse(@PathVariable String responseId) {
        try {
            return ResponseEntity.ok(stateService.retrieveResponse(responseId));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(404).body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Delete a stored response by ID.
     *
     * @param responseId Response ID
     * @return Deletion status, or a structured not-found error
     */
    @DeleteMapping(value = "${agentscope.responses.base-path:/v1/responses}/{responseId}")
    public Object deleteResponse(@PathVariable String responseId) {
        try {
            ResponsesDeletionStatus deleted = stateService.deleteResponse(responseId);
            return ResponseEntity.ok(deleted);
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(404).body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Cancel a stored or running background response.
     *
     * @param responseId Response ID
     * @return Canceled response, or a structured not-found error
     */
    @PostMapping(value = "${agentscope.responses.base-path:/v1/responses}/{responseId}/cancel")
    public Object cancelResponse(@PathVariable String responseId) {
        try {
            return ResponseEntity.ok(stateService.cancelResponse(responseId));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(404).body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * List the input items originally stored with a response.
     *
     * @param responseId Response ID
     * @param after Cursor item ID
     * @param limit Maximum item count
     * @param order Sort order, either {@code asc} or {@code desc}
     * @return OpenAI-style list object
     */
    @GetMapping(value = "${agentscope.responses.base-path:/v1/responses}/{responseId}/input_items")
    public Object listResponseInputItems(
            @PathVariable String responseId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String order) {
        try {
            ResponsesList<Object> items =
                    stateService.responseInputItems(responseId, after, limit, order);
            return ResponseEntity.ok(items);
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Count input tokens approximately.
     *
     * @param request Request containing input to count
     * @return Approximate token count
     */
    @PostMapping(
            value = "${agentscope.responses.base-path:/v1/responses}/input_tokens",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponsesTokenCount countResponseInputTokens(
            @Valid @RequestBody ResponsesRequest request) {
        return stateService.countInputTokens(request);
    }

    /**
     * Alias for clients that use the newer {@code /input_tokens/count} path.
     *
     * @param request Request containing input to count
     * @return Approximate token count
     */
    @PostMapping(
            value = "${agentscope.responses.base-path:/v1/responses}/input_tokens/count",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponsesTokenCount countResponseInputTokensAlias(
            @Valid @RequestBody ResponsesRequest request) {
        return stateService.countInputTokens(request);
    }

    /**
     * Compact input by running a normal non-streaming response over the supplied context.
     *
     * @param request Request containing context to compact
     * @return Completed compacted response, or a structured error
     */
    @PostMapping(
            value = "${agentscope.responses.base-path:/v1/responses}/compact",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object compactResponseInput(@Valid @RequestBody ResponsesRequest request) {
        String responseId = responseId();
        try {
            PreparedRequest prepared = stateService.prepare(request);
            prepared.request().setStream(false);
            ResponsesConversionResult conversion = inputConverter.convert(prepared.request());
            return createNonStreamingResponse(prepared.request(), conversion, responseId)
                    .map(response -> stateService.save(response, prepared));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(responseBuilder.buildInvalidRequestError(e));
        }
    }

    /**
     * Run the agent in non-streaming mode and wrap the final message in Responses format.
     *
     * @param request Prepared Responses request
     * @param conversion Converted messages and structured-output metadata
     * @param responseId Response ID
     * @return Mono containing completed or failed response
     */
    private Mono<ResponsesResponse> createNonStreamingResponse(
            ResponsesRequest request, ResponsesConversionResult conversion, String responseId) {
        try {
            ReActAgent agent = prepareAgent(request, conversion);
            long startedAt = System.currentTimeMillis();
            Mono<Msg> call =
                    conversion.structuredOutputSchema() != null
                            ? agent.call(conversion.messages(), conversion.structuredOutputSchema())
                            : agent.call(conversion.messages());
            return call.map(
                            reply -> {
                                log.debug(
                                        "Responses request completed: responseId={}, duration={}ms",
                                        responseId,
                                        System.currentTimeMillis() - startedAt);
                                if (conversion.structuredOutputSchema() != null) {
                                    return responseBuilder.buildStructuredResponse(
                                            request, reply, responseId);
                                }
                                return responseBuilder.buildResponse(request, reply, responseId);
                            })
                    .onErrorResume(
                            error ->
                                    Mono.just(
                                            responseBuilder.buildFailedResponse(
                                                    request, error, responseId)));
        } catch (Exception e) {
            if (e instanceof ResponsesValidationException validationException) {
                throw validationException;
            }
            return Mono.just(responseBuilder.buildFailedResponse(request, e, responseId));
        }
    }

    /**
     * Run the agent in streaming mode and persist the terminal response event.
     *
     * @param request Prepared Responses request
     * @param conversion Converted messages and structured-output metadata
     * @param responseId Response ID
     * @param prepared Prepared state metadata for storage
     * @return Flux of Spring SSE events
     */
    private Flux<ServerSentEvent<String>> createResponseStream(
            ResponsesRequest request,
            ResponsesConversionResult conversion,
            String responseId,
            PreparedRequest prepared) {
        try {
            ReActAgent agent = prepareAgent(request, conversion);
            return streamingService
                    .streamAsSse(
                            agent,
                            conversion.messages(),
                            conversion.structuredOutputSchema(),
                            request,
                            responseId,
                            event -> {
                                // The stream itself carries the final response object, so persist
                                // only when the adapter emits a terminal event.
                                if (event.getResponse() != null
                                        && ("response.completed".equals(event.getType())
                                                || "response.failed".equals(event.getType()))) {
                                    stateService.save(event.getResponse(), prepared);
                                }
                            })
                    .onErrorResume(
                            error ->
                                    Flux.just(
                                            streamingService.createErrorSseEvent(
                                                    error, request, responseId)));
        } catch (Exception e) {
            if (e instanceof ResponsesValidationException validationException) {
                throw validationException;
            }
            return Flux.just(streamingService.createErrorSseEvent(e, request, responseId));
        }
    }

    /**
     * Queue a background response and execute the real model call asynchronously.
     *
     * @param request Prepared Responses request
     * @param conversion Converted messages and structured-output metadata
     * @param responseId Response ID
     * @param prepared Prepared state metadata for storage
     * @return Mono containing the queued response placeholder
     */
    private Mono<ResponsesResponse> createBackgroundResponse(
            ResponsesRequest request,
            ResponsesConversionResult conversion,
            String responseId,
            PreparedRequest prepared) {
        ResponsesResponse queued = responseBuilder.baseResponse(request, responseId, "queued");
        queued.setBackground(true);
        queued.setStore(true);
        queued.setOutput(List.of());
        queued.setOutputText("");
        stateService.saveBackground(queued, prepared, null);

        // Store the subscription separately so /cancel can dispose it while the model call is still
        // running.
        Disposable task =
                createNonStreamingResponse(request, conversion, responseId)
                        .map(response -> stateService.save(response, prepared))
                        .subscribe();
        stateService.attachBackgroundTask(responseId, task);
        return Mono.just(queued);
    }

    /**
     * Create and configure a fresh agent for one request.
     *
     * @param request Prepared Responses request
     * @param conversion Converted messages and request-scoped system fragments
     * @return Configured agent
     */
    private ReActAgent prepareAgent(
            ResponsesRequest request, ResponsesConversionResult conversion) {
        ReActAgent agent = agentProvider.getObject();
        if (agent == null) {
            throw new IllegalStateException("Failed to create ReActAgent: provider returned null");
        }
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            agent.getToolkit()
                    .registerSchemas(toolConverter.convertToToolSchemas(request.getTools()));
        }
        GenerateOptions requestOptions = generationOptionsConverter.convert(request);
        ResponsesRequestHook hook =
                new ResponsesRequestHook(conversion.systemFragments(), requestOptions);
        List<Hook> hooks = agent.getHooks();
        // The starter uses a prototype agent, but hooks are still sorted to respect application
        // hook priority when users add their own request customizations.
        hooks.add(hook);
        hooks.sort(Comparator.comparingInt(Hook::priority));
        return agent;
    }

    private String responseId() {
        return "resp_" + UUID.randomUUID();
    }

    private org.springframework.http.HttpStatus responseStatus(ResponsesValidationException e) {
        return "not_found".equals(e.getCode())
                ? org.springframework.http.HttpStatus.NOT_FOUND
                : org.springframework.http.HttpStatus.BAD_REQUEST;
    }
}
