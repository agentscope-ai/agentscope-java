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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Core processor for AG-UI requests.
 *
 * <p>This class encapsulates the common logic for processing AG-UI requests,
 * extracting it from MVC and WebFlux handlers to avoid code duplication.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Agent ID resolution from multiple sources</li>
 *   <li>Message extraction for server-side memory scenarios</li>
 *   <li>Agent resolution via {@link AgentResolver}</li>
 *   <li>Event stream generation via {@link AguiAgentAdapter}</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AguiRequestProcessor processor = AguiRequestProcessor.builder()
 *     .agentResolver(resolver)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .build();
 *
 * ProcessResult result = processor.process(input, headerAgentId, pathAgentId);
 * Flux<AguiEvent> events = result.events();
 * Agent agent = result.agent(); // For interrupt handling
 * }</pre>
 */
public class AguiRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AguiRequestProcessor.class);

    private final AgentResolver agentResolver;
    private final AguiAdapterConfig config;

    private AguiRequestProcessor(Builder builder) {
        this.agentResolver =
                Objects.requireNonNull(builder.agentResolver, "agentResolver cannot be null");
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
    }

    /**
     * Result of processing an AG-UI request.
     *
     * <p>Contains the resolved agent (for interrupt handling) and the event stream.
     *
     * @param agent The resolved agent instance
     * @param events The event stream
     */
    public record ProcessResult(Agent agent, Flux<AguiEvent> events) {}

    /**
     * Process an AG-UI request and return the result containing agent and event stream.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable (may be null)
     * @return A ProcessResult containing the agent and event stream
     */
    public ProcessResult process(RunAgentInput input, String headerAgentId, String pathAgentId) {
        String threadId = input.getThreadId();

        // Resolve agent ID
        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);

        // Resolve agent
        Agent agent = agentResolver.resolveAgent(agentId, threadId);

        // Determine effective input based on server-side memory
        RunAgentInput effectiveInput = input;
        if (agentResolver.hasMemory(threadId)) {
            logger.debug(
                    "Using server-side memory for thread {}, extracting latest user message",
                    threadId);
            effectiveInput = extractLatestUserMessage(input);
        }

        // Create adapter and run
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);
        Flux<AguiEvent> events = adapter.run(effectiveInput);

        return new ProcessResult(agent, events);
    }

    /**
     * Resolve the agent ID from multiple sources.
     *
     * <p>The agent ID is resolved in the following priority order:
     * <ol>
     *   <li>URL path variable (if provided)</li>
     *   <li>HTTP header (if provided)</li>
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
    public String resolveAgentId(RunAgentInput input, String headerAgentId, String pathAgentId) {
        // 1. URL path variable has highest priority
        if (pathAgentId != null && !pathAgentId.isEmpty()) {
            logger.debug("Using agent ID from path variable: {}", pathAgentId);
            return pathAgentId;
        }

        // 2. Check HTTP header
        if (headerAgentId != null && !headerAgentId.isEmpty()) {
            logger.debug("Using agent ID from header: {}", headerAgentId);
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
     * Extract only the latest user message from the input.
     *
     * <p>This is used when server-side memory is enabled and the agent already
     * has conversation history. Only the latest user message needs to be passed.
     *
     * @param input The original input
     * @return A new input with only the latest user message
     */
    public RunAgentInput extractLatestUserMessage(RunAgentInput input) {
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
     * Creates a new builder for AguiRequestProcessor.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AguiRequestProcessor. */
    public static class Builder {

        private AgentResolver agentResolver;
        private AguiAdapterConfig config;

        /**
         * Set the agent resolver.
         *
         * @param agentResolver The agent resolver
         * @return This builder
         */
        public Builder agentResolver(AgentResolver agentResolver) {
            this.agentResolver = agentResolver;
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
         * Build the processor.
         *
         * @return The built processor
         * @throws NullPointerException if agentResolver is not set
         */
        public AguiRequestProcessor build() {
            return new AguiRequestProcessor(this);
        }
    }
}
