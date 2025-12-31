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
package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import java.io.IOException;
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

    private final AguiRequestProcessor processor;
    private final AguiEventEncoder encoder;
    private final String agentIdHeader;
    private final long sseTimeout;
    private final ExecutorService executorService;

    private AguiMvcController(Builder builder) {
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
                        .build();
        this.encoder = new AguiEventEncoder();
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
                        // Process request - returns both agent and event stream
                        AguiRequestProcessor.ProcessResult result =
                                processor.process(input, headerAgentId, pathAgentId);

                        // Set up callbacks for client disconnect handling
                        // using the same agent instance from the result
                        emitter.onCompletion(
                                () -> logger.debug("SSE connection completed for run {}", runId));
                        emitter.onTimeout(
                                () -> {
                                    logger.info(
                                            "SSE connection timed out for run {}, interrupting"
                                                    + " agent",
                                            runId);
                                    result.agent().interrupt();
                                });
                        emitter.onError(
                                (ex) -> {
                                    logger.info(
                                            "SSE connection error for run {}: {}, interrupting"
                                                    + " agent",
                                            runId,
                                            ex.getMessage());
                                    result.agent().interrupt();
                                });

                        // Subscribe to event stream from the same result
                        subscription =
                                result.events()
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

                    } catch (AguiException.AgentNotFoundException e) {
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
                            new AguiEvent.Raw(threadId, runId, Map.of("error", errorMessage)));
            String finishJson = encoder.encodeToJson(new AguiEvent.RunFinished(threadId, runId));
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

    /** Builder for AguiMvcController. */
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
}
