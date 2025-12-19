/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.agui.webflux.handler;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.RawEvent;
import io.agentscope.core.agui.event.RunFinishedEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.webflux.config.ThreadSessionManager;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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

    private final AguiAgentRegistry registry;
    private final ThreadSessionManager sessionManager;
    private final AguiAdapterConfig config;
    private final AguiEventEncoder encoder;
    private final boolean serverSideMemory;
    private final String agentIdHeader;

    private AguiWebFluxHandler(Builder builder) {
        this.registry = builder.registry;
        this.sessionManager = builder.sessionManager;
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
        this.encoder = new AguiEventEncoder();
        this.serverSideMemory = builder.serverSideMemory;
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
    }

    /**
     * Handle an AG-UI run request.
     *
     * <p>This method parses the request body as {@link RunAgentInput}, resolves the
     * agent from the registry, and returns an SSE stream of AG-UI events.
     *
     * <p>Agent ID is resolved from (in priority order):
     * <ol>
     *   <li>HTTP header (configurable, default: X-Agent-Id)</li>
     *   <li>forwardedProps.agentId in request body</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param request The server request
     * @return A Mono containing the server response with SSE stream
     */
    public Mono<ServerResponse> handle(ServerRequest request) {
        return request.bodyToMono(RunAgentInput.class)
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
        return request.bodyToMono(RunAgentInput.class)
                .flatMap(input -> processInput(input, request, pathAgentId))
                .onErrorResume(this::handleParseError);
    }

    private Mono<ServerResponse> processInput(
            RunAgentInput input, ServerRequest request, String pathAgentId) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        try {
            // Resolve agent from registry or session
            String agentId = resolveAgentId(input, request, pathAgentId);
            Agent agent;
            RunAgentInput effectiveInput = input;

            if (serverSideMemory && sessionManager != null) {
                // Server-side memory mode: use session manager
                agent =
                        sessionManager.getOrCreateAgent(
                                threadId,
                                agentId,
                                () ->
                                        registry.getAgent(agentId)
                                                .orElseThrow(
                                                        () ->
                                                                new AgentNotFoundException(
                                                                        "Agent not found: "
                                                                                + agentId)));

                // Check if agent has existing memory
                if (sessionManager.hasMemory(threadId)) {
                    // Agent has memory, only use the latest user message from frontend
                    logger.debug(
                            "Using server-side memory for thread {}, ignoring frontend history",
                            threadId);
                    effectiveInput = extractLatestUserMessage(input);
                } else {
                    // No memory yet, use frontend messages to initialize
                    logger.debug(
                            "No server-side memory for thread {}, using frontend messages",
                            threadId);
                }
            } else {
                // Standard mode: create new agent for each request
                agent =
                        registry.getAgent(agentId)
                                .orElseThrow(
                                        () ->
                                                new AgentNotFoundException(
                                                        "Agent not found: " + agentId));
            }

            // Create adapter and run
            AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);
            // Use encodeToJson() - WebFlux will add SSE "data:" prefix automatically
            Flux<String> sseStream =
                    adapter.run(effectiveInput)
                            .map(encoder::encodeToJson)
                            // When client closes connection (cancels stream), interrupt the agent
                            .doOnCancel(
                                    () -> {
                                        logger.info(
                                                "SSE stream cancelled for run {}, interrupting"
                                                        + " agent",
                                                runId);
                                        agent.interrupt();
                                    });

            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseStream, String.class);

        } catch (AgentNotFoundException e) {
            logger.error("Agent not found: {}", e.getMessage());
            return createErrorResponse(threadId, runId, e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing AG-UI request: {}", e.getMessage());
            return createErrorResponse(threadId, runId, e.getMessage());
        }
    }

    /**
     * Extract only the latest user message from the input.
     * Used when server-side memory is enabled and the agent already has history.
     */
    private RunAgentInput extractLatestUserMessage(RunAgentInput input) {
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return input;
        }

        // Find the last user message
        AguiMessage lastUserMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AguiMessage msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                lastUserMessage = msg;
                break;
            }
        }

        if (lastUserMessage == null) {
            return input;
        }

        // Create new input with only the last user message
        return RunAgentInput.builder()
                .threadId(input.getThreadId())
                .runId(input.getRunId())
                .messages(List.of(lastUserMessage))
                .tools(input.getTools())
                .context(input.getContext())
                .forwardedProps(input.getForwardedProps())
                .build();
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
                        String.class);
    }

    private Mono<ServerResponse> createErrorResponse(
            String threadId, String runId, String errorMessage) {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(createErrorEventStream(threadId, runId, errorMessage), String.class);
    }

    /**
     * Create an SSE stream containing error and finish events.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param errorMessage The error message
     * @return A Flux of encoded SSE events
     */
    private Flux<String> createErrorEventStream(
            String threadId, String runId, String errorMessage) {
        String errorEvent =
                encoder.encodeToJson(new RawEvent(threadId, runId, Map.of("error", errorMessage)));
        String finishEvent = encoder.encodeToJson(new RunFinishedEvent(threadId, runId));
        return Flux.just(errorEvent, finishEvent);
    }

    /**
     * Resolve the agent ID from multiple sources.
     *
     * <p>The agent ID is resolved in the following priority order:
     * <ol>
     *   <li>URL path variable (if provided)</li>
     *   <li>HTTP header (configurable, default: X-Agent-Id)</li>
     *   <li>forwardedProps.agentId in request body</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param input The request input
     * @param request The server request (for header access)
     * @param pathAgentId The agent ID from URL path variable (may be null)
     * @return The resolved agent ID
     */
    private String resolveAgentId(RunAgentInput input, ServerRequest request, String pathAgentId) {
        // 1. URL path variable has highest priority
        if (pathAgentId != null && !pathAgentId.isEmpty()) {
            logger.debug("Using agent ID from path variable: {}", pathAgentId);
            return pathAgentId;
        }

        // 2. Check HTTP header
        String headerAgentId = request.headers().firstHeader(agentIdHeader);
        if (headerAgentId != null && !headerAgentId.isEmpty()) {
            logger.debug("Using agent ID from header {}: {}", agentIdHeader, headerAgentId);
            return headerAgentId;
        }

        // 3. Check forwardedProps for agentId
        Object agentIdProp = input.getForwardedProp("agentId");
        if (agentIdProp != null) {
            String propsAgentId = agentIdProp.toString();
            logger.debug("Using agent ID from forwardedProps: {}", propsAgentId);
            return propsAgentId;
        }

        // 4. Use config default
        if (config.getDefaultAgentId() != null) {
            logger.debug("Using default agent ID from config: {}", config.getDefaultAgentId());
            return config.getDefaultAgentId();
        }

        // 5. Fall back to "default"
        logger.debug("Using fallback agent ID: default");
        return "default";
    }

    /**
     * Creates a new builder for AguiWebFluxHandler.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for AguiWebFluxHandler.
     */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;

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

    /**
     * Exception thrown when an agent is not found in the registry.
     */
    public static class AgentNotFoundException extends RuntimeException {

        public AgentNotFoundException(String message) {
            super(message);
        }
    }
}
