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
package io.agentscope.spring.boot.agui.webflux;

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebFlux handler for AG-UI protocol requests.
 *
 * <p>This handler processes AG-UI run requests and returns Server-Sent Events (SSE)
 * streams with AG-UI protocol events.
 *
 * <p><b>Agent ID Resolution Priority:</b>
 * <ol>
 *   <li>URL path variable: {@code /agui/run/{agentId}}</li>
 *   <li>HTTP header: configurable via {@code agentIdHeader} (default: X-Agent-Id)</li>
 *   <li>forwardedProps.agentId in request body</li>
 *   <li>config.defaultAgentId</li>
 *   <li>"default"</li>
 * </ol>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AguiWebFluxHandler handler = AguiWebFluxHandler.builder()
 *     .agentRegistry(registry)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .agentIdHeader("X-Agent-Id")
 *     .build();
 *
 * RouterFunction<ServerResponse> routes = RouterFunctions.route()
 *     .POST("/agui/run", handler::handle)
 *     .POST("/agui/run/{agentId}", handler::handleWithAgentId)
 *     .build();
 * }</pre>
 */
public class AguiWebFluxHandler {

    private static final Logger logger = LoggerFactory.getLogger(AguiWebFluxHandler.class);

    private static final String DEFAULT_AGENT_ID_HEADER = "X-Agent-Id";
    private static final String AGENT_ID_PATH_VARIABLE = "agentId";

    private final AguiRequestProcessor processor;
    private final AguiEventEncoder encoder;
    private final AguiRequestBodyParser requestBodyParser;
    private final String agentIdHeader;
    private final boolean interruptOnDisconnect;

    private AguiWebFluxHandler(Builder builder) {
        this.processor =
                AguiRequestProcessor.builder()
                        .agentResolver(
                                DefaultAgentResolver.builder()
                                        .registry(builder.registry)
                                        .sessionManager(builder.sessionManager)
                                        .serverSideMemory(builder.serverSideMemory)
                                        .build())
                        .config(
                                builder.config != null
                                        ? builder.config
                                        : AguiAdapterConfig.defaultConfig())
                        .adapterFactory(builder.adapterFactory)
                        .runtimeContextResolver(builder.runtimeContextResolver)
                        .build();
        this.encoder = new AguiEventEncoder();
        this.requestBodyParser =
                builder.requestBodyParser != null
                        ? builder.requestBodyParser
                        : new AguiRequestBodyParser();
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
        this.interruptOnDisconnect = builder.interruptOnDisconnect;
    }

    /**
     * Handle an AG-UI run request.
     *
     * <p>This method parses the request body as {@link RunAgentInput}, resolves the
     * agent from the registry, and returns an SSE stream of AG-UI events.
     *
     * @param request The server request
     * @return A Mono containing the server response with SSE stream
     */
    public Mono<ServerResponse> handle(ServerRequest request) {
        return request.bodyToMono(String.class)
                .map(requestBodyParser::parse)
                .flatMap(input -> processInput(input, request, null))
                .onErrorResume(this::handleParseError);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * <p>This method handles requests to {@code /agui/run/{agentId}}.
     * The path variable takes highest priority for agent resolution.
     *
     * @param request The server request containing the agentId path variable
     * @return A Mono containing the server response with SSE stream
     */
    public Mono<ServerResponse> handleWithAgentId(ServerRequest request) {
        String pathAgentId = request.pathVariable(AGENT_ID_PATH_VARIABLE);
        return request.bodyToMono(String.class)
                .map(requestBodyParser::parse)
                .flatMap(input -> processInput(input, request, pathAgentId))
                .onErrorResume(this::handleParseError);
    }

    private Mono<ServerResponse> processInput(
            RunAgentInput input, ServerRequest request, String pathAgentId) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        try {
            // Get header agent ID
            String headerAgentId = request.headers().firstHeader(agentIdHeader);

            // Process request - returns both agent and event stream
            AguiRequestProcessor.ProcessResult result =
                    processor.process(
                            runtimeContextRequest(input, headerAgentId, pathAgentId, request));

            // Create SSE stream using ServerSentEvent for proper streaming behavior
            Flux<AguiEvent> events =
                    interruptOnDisconnect
                            ? result.events()
                            : result.events().publish().autoConnect(1);
            Flux<ServerSentEvent<String>> sseStream =
                    events.map(
                                    event ->
                                            ServerSentEvent.<String>builder()
                                                    .data(encoder.encodeToJson(event).trim())
                                                    .build())
                            // When the client closes the connection, optionally interrupt the agent
                            .doOnCancel(
                                    () -> {
                                        if (interruptOnDisconnect) {
                                            logger.info(
                                                    "SSE stream cancelled for run {}, interrupting"
                                                            + " agent",
                                                    runId);
                                            result.interrupt(threadId);
                                        } else {
                                            logger.info(
                                                    "SSE stream cancelled for run {}, agent"
                                                            + " continues running",
                                                    runId);
                                        }
                                    });

            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseStream, ServerSentEvent.class);

        } catch (AguiException.AgentNotFoundException e) {
            logger.error("Agent not found: {}", e.getMessage());
            return createErrorResponse(threadId, runId, e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing AG-UI request: {}", e.getMessage());
            return createErrorResponse(threadId, runId, e.getMessage());
        }
    }

    private AguiRuntimeContextRequest<ServerRequest> runtimeContextRequest(
            RunAgentInput input, String headerAgentId, String pathAgentId, ServerRequest request) {
        return AguiRuntimeContextRequest.<ServerRequest>builder()
                .input(input)
                .headerAgentId(headerAgentId)
                .pathAgentId(pathAgentId)
                .transport(AguiRuntimeContextRequest.Transport.WEBFLUX)
                .method(request != null ? request.method().name() : null)
                .path(request != null ? request.path() : null)
                .headers(headers(request))
                .queryParams(queryParams(request))
                .nativeRequest(request)
                .build();
    }

    private static Map<String, List<String>> headers(ServerRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.headers()
                .asHttpHeaders()
                .forEach((name, values) -> headers.put(name, List.copyOf(values)));
        return headers;
    }

    private static Map<String, List<String>> queryParams(ServerRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        request.queryParams().forEach((name, values) -> queryParams.put(name, List.copyOf(values)));
        return queryParams;
    }

    private Mono<ServerResponse> handleParseError(Throwable error) {
        logger.error("Error parsing AG-UI request: {}", error.getMessage());
        return ServerResponse.badRequest()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(
                        createErrorEventStream(
                                "unknown",
                                "unknown",
                                "Failed to parse request: " + error.getMessage()),
                        ServerSentEvent.class);
    }

    private Mono<ServerResponse> createErrorResponse(
            String threadId, String runId, String errorMessage) {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(createErrorEventStream(threadId, runId, errorMessage), ServerSentEvent.class);
    }

    /**
     * Create an SSE stream containing error and finish events.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param errorMessage The error message
     * @return A Flux of ServerSentEvents
     */
    private Flux<ServerSentEvent<String>> createErrorEventStream(
            String threadId, String runId, String errorMessage) {
        String errorEvent =
                encoder.encodeToJson(
                                new AguiEvent.Raw(threadId, runId, Map.of("error", errorMessage)))
                        .trim();
        String finishEvent =
                encoder.encodeToJson(new AguiEvent.RunFinished(threadId, runId)).trim();
        return Flux.just(
                ServerSentEvent.<String>builder().data(errorEvent).build(),
                ServerSentEvent.<String>builder().data(finishEvent).build());
    }

    /**
     * Creates a new builder for AguiWebFluxHandler.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AguiWebFluxHandler. */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;
        private boolean interruptOnDisconnect = true;
        private AguiRuntimeContextResolver runtimeContextResolver;
        private AguiAgentAdapterFactory adapterFactory;
        private AguiRequestBodyParser requestBodyParser;

        /**
         * Set the agent registry.
         *
         * @param registry The agent registry
         * @return This builder
         */
        public Builder agentRegistry(AguiAgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Set the thread session manager for server-side memory support.
         *
         * @param sessionManager The session manager
         * @return This builder
         */
        public Builder sessionManager(ThreadSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * Enable or disable server-side memory management.
         *
         * @param enabled Whether to enable server-side memory
         * @return This builder
         */
        public Builder serverSideMemory(boolean enabled) {
            this.serverSideMemory = enabled;
            return this;
        }

        /**
         * Set the adapter configuration.
         *
         * @param config The adapter configuration
         * @return This builder
         */
        public Builder config(AguiAdapterConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Set the HTTP header name to read agent ID from.
         *
         * @param agentIdHeader The header name (default: X-Agent-Id)
         * @return This builder
         */
        public Builder agentIdHeader(String agentIdHeader) {
            this.agentIdHeader = agentIdHeader;
            return this;
        }

        /**
         * Set whether to interrupt the agent when the client disconnects.
         *
         * @param interruptOnDisconnect whether to interrupt the agent
         * @return This builder
         */
        public Builder interruptOnDisconnect(boolean interruptOnDisconnect) {
            this.interruptOnDisconnect = interruptOnDisconnect;
            return this;
        }

        /**
         * Set the runtime context resolver.
         *
         * @param runtimeContextResolver The resolver used for each request
         * @return This builder
         */
        public Builder runtimeContextResolver(AguiRuntimeContextResolver runtimeContextResolver) {
            this.runtimeContextResolver = runtimeContextResolver;
            return this;
        }

        /**
         * Set the adapter factory.
         *
         * @param adapterFactory The factory used to create per-request adapters
         * @return This builder
         */
        public Builder adapterFactory(AguiAgentAdapterFactory adapterFactory) {
            this.adapterFactory = adapterFactory;
            return this;
        }

        /**
         * Set the request body parser.
         *
         * @param requestBodyParser The parser used to decode request bodies
         * @return This builder
         */
        public Builder requestBodyParser(AguiRequestBodyParser requestBodyParser) {
            this.requestBodyParser = requestBodyParser;
            return this;
        }

        /**
         * Build the handler.
         *
         * @return The built handler
         * @throws IllegalStateException if registry is not set
         */
        public AguiWebFluxHandler build() {
            if (registry == null) {
                throw new IllegalStateException("Agent registry must be set");
            }
            return new AguiWebFluxHandler(this);
        }
    }
}
