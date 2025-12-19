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
package io.agentscope.spring.boot.agui.mvc.controller;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.RawEvent;
import io.agentscope.core.agui.event.RunFinishedEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.mvc.config.ThreadSessionManager;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * MVC controller for AG-UI protocol requests.
 *
 * <p>This controller processes AG-UI run requests and returns Server-Sent Events (SSE)
 * streams with AG-UI protocol events using Spring MVC's {@link SseEmitter}.
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
 * AguiMvcController controller = AguiMvcController.builder()
 *     .agentRegistry(registry)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .agentIdHeader("X-Agent-Id")
 *     .build();
 * }</pre>
 */
public class AguiMvcController {

    private static final Logger logger = LoggerFactory.getLogger(AguiMvcController.class);

    private static final String DEFAULT_AGENT_ID_HEADER = "X-Agent-Id";

    private final AguiAgentRegistry registry;
    private final ThreadSessionManager sessionManager;
    private final AguiAdapterConfig config;
    private final AguiEventEncoder encoder;
    private final boolean serverSideMemory;
    private final String agentIdHeader;
    private final long sseTimeout;
    private final ExecutorService executorService;

    private AguiMvcController(Builder builder) {
        this.registry = builder.registry;
        this.sessionManager = builder.sessionManager;
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
        this.encoder = new AguiEventEncoder();
        this.serverSideMemory = builder.serverSideMemory;
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
        this.sseTimeout = builder.sseTimeout > 0 ? builder.sseTimeout : 600000L;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Handle an AG-UI run request.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handle(RunAgentInput input, String headerAgentId) {
        return handleInternal(input, headerAgentId, null);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handleWithAgentId(
            RunAgentInput input, String headerAgentId, String pathAgentId) {
        return handleInternal(input, headerAgentId, pathAgentId);
    }

    private SseEmitter handleInternal(
            RunAgentInput input, String headerAgentId, String pathAgentId) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        executorService.submit(
                () -> {
                    Disposable subscription = null;
                    try {
                        // Resolve agent from registry or session
                        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);
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
                                                                                    "Agent not"
                                                                                        + " found: "
                                                                                            + agentId)));

                            // Check if agent has existing memory
                            if (sessionManager.hasMemory(threadId)) {
                                logger.debug(
                                        "Using server-side memory for thread {}, ignoring frontend"
                                                + " history",
                                        threadId);
                                effectiveInput = extractLatestUserMessage(input);
                            } else {
                                logger.debug(
                                        "No server-side memory for thread {}, using frontend"
                                                + " messages",
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
                        final Agent agentForInterrupt = agent;

                        // Set up completion callback to interrupt agent on client disconnect
                        emitter.onCompletion(
                                () -> {
                                    logger.debug(
                                            "SSE connection completed for run {}",
                                            input.getRunId());
                                });
                        emitter.onTimeout(
                                () -> {
                                    logger.info(
                                            "SSE connection timed out for run {}, interrupting"
                                                    + " agent",
                                            input.getRunId());
                                    agentForInterrupt.interrupt();
                                });
                        emitter.onError(
                                (ex) -> {
                                    logger.info(
                                            "SSE connection error for run {}: {}, interrupting"
                                                    + " agent",
                                            input.getRunId(),
                                            ex.getMessage());
                                    agentForInterrupt.interrupt();
                                });

                        // Subscribe to the event stream and send events via SseEmitter
                        subscription =
                                adapter.run(effectiveInput)
                                        .subscribe(
                                                event -> sendEvent(emitter, event),
                                                error -> {
                                                    logger.error(
                                                            "Error during AG-UI run: {}",
                                                            error.getMessage());
                                                    sendErrorAndComplete(
                                                            emitter,
                                                            threadId,
                                                            runId,
                                                            error.getMessage());
                                                },
                                                () -> {
                                                    try {
                                                        emitter.complete();
                                                    } catch (Exception e) {
                                                        logger.debug(
                                                                "Error completing emitter: {}",
                                                                e.getMessage());
                                                    }
                                                });

                    } catch (AgentNotFoundException e) {
                        logger.error("Agent not found: {}", e.getMessage());
                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    } catch (Exception e) {
                        logger.error("Error processing AG-UI request: {}", e.getMessage());
                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    }
                });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, AguiEvent event) {
        try {
            String jsonData = encoder.encodeToJson(event);
            emitter.send(SseEmitter.event().data(jsonData, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            logger.debug("Failed to send SSE event: {}", e.getMessage());
        }
    }

    private void sendErrorAndComplete(
            SseEmitter emitter, String threadId, String runId, String errorMessage) {
        try {
            String errorJson =
                    encoder.encodeToJson(
                            new RawEvent(threadId, runId, Map.of("error", errorMessage)));
            String finishJson = encoder.encodeToJson(new RunFinishedEvent(threadId, runId));
            emitter.send(SseEmitter.event().data(errorJson, MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().data(finishJson, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            logger.debug("Failed to send error event: {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                logger.debug("Failed to complete emitter with error: {}", ex.getMessage());
            }
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
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable (may be null)
     * @return The resolved agent ID
     */
    private String resolveAgentId(RunAgentInput input, String headerAgentId, String pathAgentId) {
        // 1. URL path variable has highest priority
        if (pathAgentId != null && !pathAgentId.isEmpty()) {
            logger.debug("Using agent ID from path variable: {}", pathAgentId);
            return pathAgentId;
        }

        // 2. Check HTTP header
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
     * Get the agent ID header name.
     *
     * @return The header name
     */
    public String getAgentIdHeader() {
        return agentIdHeader;
    }

    /**
     * Creates a new builder for AguiMvcController.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for AguiMvcController.
     */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;
        private long sseTimeout = 600000L;

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
         * Set the SSE timeout in milliseconds.
         *
         * @param sseTimeout The timeout value
         * @return This builder
         */
        public Builder sseTimeout(long sseTimeout) {
            this.sseTimeout = sseTimeout;
            return this;
        }

        /**
         * Build the controller.
         *
         * @return The built controller
         * @throws IllegalStateException if registry is not set
         */
        public AguiMvcController build() {
            if (registry == null) {
                throw new IllegalStateException("Agent registry must be set");
            }
            return new AguiMvcController(this);
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
