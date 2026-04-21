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
package io.agentscope.core.studio;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.StreamableAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Central manager for AgentScope Studio integration.
 *
 * <p>This class provides a simplified API for initializing Studio integration and accessing
 * Studio clients. It manages the lifecycle of HTTP and WebSocket connections to Studio.
 *
 * <p>Usage example:
 * <pre>{@code
 * // Initialize Studio integration
 * StudioManager.init()
 *     .studioUrl("http://localhost:8000")
 *     .project("MyProject")
 *     .runName("experiment_001")
 *     .initialize()
 *     .block();
 *
 * // Create agent with Studio hook
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .hook(new StudioMessageHook(StudioManager.getClient()))
 *     .build();
 *
 * // Agent calls automatically send messages to Studio
 * agent.call(msg).block();
 * }</pre>
 */
public class StudioManager {
    private static final Logger logger = LoggerFactory.getLogger(StudioManager.class);

    private static volatile StudioConfig config;
    private static volatile StudioClient client;
    private static volatile StudioWebSocketClient wsClient;

    private StudioManager() {
        // Private constructor to prevent instantiation
    }

    /**
     * Creates a new initialization builder.
     *
     * @return A new builder for configuring Studio integration
     */
    public static Builder init() {
        return new Builder();
    }

    /**
     * Gets the Studio HTTP client.
     *
     * @return The Studio HTTP client, or null if not initialized
     */
    public static StudioClient getClient() {
        return client;
    }

    /**
     * Gets the Studio WebSocket client.
     *
     * @return The Studio WebSocket client, or null if not initialized
     */
    public static StudioWebSocketClient getWebSocketClient() {
        return wsClient;
    }

    /**
     * Gets the Studio configuration.
     *
     * @return The Studio configuration, or null if not initialized
     */
    public static StudioConfig getConfig() {
        return config;
    }

    /**
     * Checks if Studio integration is initialized.
     *
     * @return true if Studio is initialized, false otherwise
     */
    public static boolean isInitialized() {
        return config != null && client != null;
    }

    /**
     * Shuts down Studio integration and releases resources.
     */
    public static void shutdown() {
        if (client != null) {
            client.shutdown();
        }
        if (wsClient != null) {
            wsClient.close();
        }
        config = null;
        client = null;
        wsClient = null;
    }

    /**
     * Stream agent events to Studio for real-time visualization and final message persistence.
     *
     * <p>This method invokes {@link StreamableAgent#stream(Msg, StreamOptions)} on the provided
     * agent to obtain a {@link Flux} of {@link Event} instances, then forwards that stream to
     * Studio via a {@link StudioStreamingBridge}. The bridge:
     * @param agent the {@link StreamableAgent} to run; must not be {@code null}
     * @param input the input {@link Msg} for this invocation (may be {@code null} for agents that
     *     do not require an initial message)
     * @param options optional {@link StreamOptions} controlling the agent's streaming behavior;
     *     when {@code null}, {@link StreamOptions#defaults()} is used
     * @return a {@link Mono} that completes when the event stream has been fully forwarded to
     *     Studio and, if a final message exists, persisted; if Studio is not initialized,
     *     this returns a {@link Mono#error(Throwable)} with {@link IllegalStateException}
     */
    public static Mono<Void> streamToStudio(
            StreamableAgent agent, Msg input, StreamOptions options) {
        if (!isInitialized() || client == null || wsClient == null) {
            return Mono.error(new IllegalStateException("Studio is not initialized"));
        }
        StreamOptions effective = options != null ? options : StreamOptions.defaults();
        Flux<Event> events = agent.stream(input, effective);
        StudioStreamingBridge bridge = new StudioStreamingBridge(client, wsClient);
        return bridge.forwardToStudio(events);
    }

    /**
     * Builder for configuring and initializing Studio integration.
     */
    public static class Builder {
        private final StudioConfig.Builder configBuilder;

        private Builder() {
            this.configBuilder = StudioConfig.builder();
        }

        /**
         * Sets the Studio URL.
         *
         * @param studioUrl The Studio server URL (e.g., "http://localhost:8000")
         * @return This builder
         */
        public Builder studioUrl(String studioUrl) {
            configBuilder.studioUrl(studioUrl);
            return this;
        }

        /**
         * Sets the tracing URL (defaults to {studioUrl}/v1/traces).
         *
         * @param tracingUrl The OpenTelemetry tracing endpoint URL
         * @return This builder
         */
        public Builder tracingUrl(String tracingUrl) {
            configBuilder.tracingUrl(tracingUrl);
            return this;
        }

        /**
         * Sets the project name.
         *
         * @param project The project name
         * @return This builder
         */
        public Builder project(String project) {
            configBuilder.project(project);
            return this;
        }

        /**
         * Sets the run name.
         *
         * @param runName The run name
         * @return This builder
         */
        public Builder runName(String runName) {
            configBuilder.runName(runName);
            return this;
        }

        /**
         * Sets the maximum number of HTTP request retries.
         *
         * @param maxRetries Maximum retry count (default: 3)
         * @return This builder
         */
        public Builder maxRetries(int maxRetries) {
            configBuilder.maxRetries(maxRetries);
            return this;
        }

        /**
         * Sets the number of WebSocket reconnection attempts.
         *
         * @param reconnectAttempts Maximum reconnection attempts (default: 3)
         * @return This builder
         */
        public Builder reconnectAttempts(int reconnectAttempts) {
            configBuilder.reconnectAttempts(reconnectAttempts);
            return this;
        }

        /**
         * Initializes Studio integration.
         *
         * <p>This method:
         * <ol>
         *   <li>Builds the configuration</li>
         *   <li>Creates HTTP and WebSocket clients</li>
         *   <li>Registers the run with Studio</li>
         *   <li>Establishes WebSocket connection</li>
         * </ol>
         *
         * @return A Mono that completes when initialization succeeds
         */
        public Mono<Void> initialize() {
            return Mono.fromRunnable(
                            () -> {
                                // Build configuration
                                config = configBuilder.build();

                                // Create clients
                                client = new StudioClient(config);
                                wsClient = new StudioWebSocketClient(config);
                            })
                    .then(
                            Mono.defer(
                                    () ->
                                            // Register run with Studio
                                            client.registerRun()
                                                    .doOnSuccess(
                                                            v ->
                                                                    logger.info(
                                                                            "Registered run with"
                                                                                    + " Studio: {}",
                                                                            config.getRunId()))))
                    .then(
                            Mono.defer(
                                    () ->
                                            // Connect WebSocket
                                            wsClient.connect()
                                                    .doOnSuccess(
                                                            v ->
                                                                    logger.info(
                                                                            "Studio integration"
                                                                                + " initialized"
                                                                                + " successfully"))))
                    .doOnSuccess(
                            (v) -> {
                                AgentBase.addSystemHook(
                                        new StudioMessageHook(StudioManager.getClient()));
                            })
                    .doOnSuccess(
                            (v) -> {
                                String traceEndpoint =
                                        URI.create(config.getStudioUrl()).getPath() + "/v1/traces";
                                if (config.getTracingUrl() != null) {
                                    traceEndpoint = config.getTracingUrl();
                                }
                                TracerRegistry.register(
                                        TelemetryTracer.builder().endpoint(traceEndpoint).build());
                            })
                    .doOnError(e -> logger.error("Failed to initialize Studio", e))
                    .onErrorResume(
                            e -> {
                                // Clean up on error
                                shutdown();
                                return Mono.error(e);
                            });
        }
    }
}
