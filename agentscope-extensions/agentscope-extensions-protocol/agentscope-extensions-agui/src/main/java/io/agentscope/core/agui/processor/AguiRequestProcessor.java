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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiUtil;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * AguiRuntimeContextRequest<?> request = AguiRuntimeContextRequest.builder()
 *         .input(input)
 *         .build();
 * ProcessResult result = processor.process(request);
 * Flux<AguiEvent> events = result.events();
 * }</pre>
 */
public class AguiRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AguiRequestProcessor.class);

    private final AgentResolver agentResolver;
    private final AguiAdapterConfig config;
    private final AguiAgentAdapterFactory adapterFactory;
    private final AguiResumeCoordinator resumeCoordinator;
    private final AguiRuntimeContextResolver runtimeContextResolver;

    private AguiRequestProcessor(Builder builder) {
        this.agentResolver =
                Objects.requireNonNull(builder.agentResolver, "agentResolver cannot be null");
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
        this.adapterFactory =
                builder.adapterFactory != null
                        ? builder.adapterFactory
                        : AguiAgentAdapterFactory.defaultFactory();
        this.resumeCoordinator = new AguiResumeCoordinator();
        this.runtimeContextResolver = builder.runtimeContextResolver;
    }

    /**
     * Result of processing an AG-UI request.
     *
     * <p>Contains the resolved agent (for interrupt handling) and the event stream.
     *
     * @param agent The resolved agent instance
     * @param events The event stream
     * @param runtimeContext The resolved caller-provided runtime context, may be null
     */
    public record ProcessResult(
            Agent agent, Flux<AguiEvent> events, RuntimeContext runtimeContext) {

        /**
         * Interrupt this request's active session.
         *
         * <p>AG-UI uses {@code threadId} as the session id. For a multi-session
         * {@link ReActAgent}, preserve the caller's user id and target that session instead of
         * invoking the deprecated no-argument interrupt method, which always targets the default
         * session.
         *
         * @param threadId The AG-UI thread id for this request
         */
        public void interrupt(String threadId) {
            ReActAgent reActAgent = AguiUtil.asReActAgent(agent);
            if (reActAgent != null) {
                RuntimeContext interruptContext =
                        RuntimeContext.builder(runtimeContext).sessionId(threadId).build();
                reActAgent.interrupt(interruptContext);
            } else {
                agent.interrupt();
            }
        }
    }

    /**
     * Process an AG-UI request and return the result containing agent and event stream.
     *
     * <p>The {@link AguiRuntimeContextResolver} (if configured on this processor) is invoked with
     * the given request to obtain a caller-provided {@link RuntimeContext}. That context is copied
     * and enriched by {@link AguiAgentAdapter}, so callers can provide custom attributes without
     * replacing the standard AG-UI metadata.
     *
     * @param request The AG-UI request context carrying input, agent IDs, transport details and the
     *     native request
     * @return A ProcessResult containing the agent and event stream
     */
    public ProcessResult process(AguiRuntimeContextRequest<?> request) {
        RunAgentInput input = request.getInput();
        String headerAgentId = request.getHeaderAgentId();
        String pathAgentId = request.getPathAgentId();
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        RuntimeContext resolved =
                runtimeContextResolver != null ? runtimeContextResolver.resolve(request) : null;
        RuntimeContext runtimeContext =
                RuntimeContext.builder(resolved).sessionId(threadId).build();

        // Resolve agent ID
        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);

        // Resolve agent
        Agent agent = agentResolver.resolveAgent(agentId, threadId, runtimeContext.getUserId());

        Flux<AguiEvent> events =
                Flux.defer(
                        () -> {
                            AguiResumeCoordinator.ResumeContractResult beginResult =
                                    resumeCoordinator.beginRun(input);
                            if (beginResult.isError()) {
                                return Flux.fromIterable(
                                        resumeCoordinator.contractErrorEvents(
                                                input,
                                                beginResult.message(),
                                                config.isEmitRunFinishedAfterError()));
                            }

                            try {
                                // Determine effective input based on server-side memory
                                RunAgentInput effectiveInput = input;
                                if (agentResolver.hasMemory(runtimeContext)) {
                                    logger.debug(
                                            "Using server-side memory for thread {} user {},"
                                                    + " extracting follow-up messages",
                                            threadId,
                                            runtimeContext.getUserId());
                                    effectiveInput = extractLatestUserMessage(input);
                                }

                                RuntimeContext effectiveRuntimeContext =
                                        resumeCoordinator.addResumeInterrupts(
                                                input, runtimeContext);

                                // Create adapter and run
                                AguiAgentAdapter adapter = adapterFactory.create(agent, config);
                                AtomicBoolean runErrorSeen = new AtomicBoolean(false);
                                return Objects.requireNonNull(
                                                adapter.run(
                                                        effectiveInput, effectiveRuntimeContext),
                                                "adapter event stream is null")
                                        .doOnNext(
                                                event -> {
                                                    if (event instanceof AguiEvent.RunError) {
                                                        runErrorSeen.set(true);
                                                    }
                                                    resumeCoordinator.trackPendingInterrupts(
                                                            threadId,
                                                            runId,
                                                            event,
                                                            runErrorSeen.get());
                                                })
                                        .doFinally(
                                                signalType ->
                                                        resumeCoordinator.finishRun(
                                                                threadId, runId));
                            } catch (Throwable error) {
                                resumeCoordinator.finishRun(threadId, runId);
                                return processorErrorEvents(input, error);
                            }
                        });
        return new ProcessResult(agent, events, runtimeContext);
    }

    private Flux<AguiEvent> processorErrorEvents(RunAgentInput input, Throwable error) {
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(input.getThreadId(), input.getRunId(), null, input));
        events.add(
                new AguiEvent.RunError(
                        input.getThreadId(),
                        input.getRunId(),
                        errorMessage,
                        mapErrorCode(error),
                        System.currentTimeMillis(),
                        null));
        if (config.isEmitRunFinishedAfterError()) {
            events.add(new AguiEvent.RunFinished(input.getThreadId(), input.getRunId()));
        }
        return Flux.fromIterable(events);
    }

    private static String mapErrorCode(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT_ERROR";
        }
        if (error instanceof java.lang.InterruptedException) {
            return "INTERRUPTED_ERROR";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return "INVALID_INPUT_ERROR";
        }
        return "INTERNAL_ERROR";
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
     * Extract messages that arrived after the last assistant turn.
     *
     * <p>When server-side memory is enabled the agent already holds prior turns. CopilotKit (and
     * similar clients) still send the full transcript, including HITL tool results that follow the
     * last assistant message. Only those trailing messages should be appended.
     *
     * <p>If the transcript has no assistant message yet, the original input is returned unchanged.
     * If the transcript ends with an assistant turn (regenerate/continue flows) and there
     * are no trailing follow-up messages, the last user message before that turn is returned so
     * the agent has a prompt to regenerate from instead of receiving an empty input.
     *
     * @param input The original input
     * @return A new input containing only the follow-up messages, or the original input
     */
    public RunAgentInput extractLatestUserMessage(RunAgentInput input) {
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return input;
        }

        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equalsIgnoreCase(messages.get(i).getRole())) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) {
            return input;
        }

        List<AguiMessage> after =
                lastAssistantIdx < messages.size() - 1
                        ? List.copyOf(messages.subList(lastAssistantIdx + 1, messages.size()))
                        : List.of();

        if (after.isEmpty()) {
            for (int i = lastAssistantIdx - 1; i >= 0; i--) {
                if ("user".equalsIgnoreCase(messages.get(i).getRole())) {
                    after = List.of(messages.get(i));
                    break;
                }
            }
        }

        return RunAgentInput.builder()
                .threadId(input.getThreadId())
                .runId(input.getRunId())
                .messages(after)
                .tools(input.getTools())
                .context(input.getContext())
                .state(input.getState())
                .forwardedProps(input.getForwardedProps())
                .resume(input.getResume())
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
        private AguiAgentAdapterFactory adapterFactory;
        private AguiRuntimeContextResolver runtimeContextResolver;

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
         * Set the runtime context resolver invoked for each request to produce a caller-provided
         * {@link RuntimeContext}. Optional; when null, no caller context is attached.
         *
         * @param runtimeContextResolver The resolver used for each request
         * @return This builder
         */
        public Builder runtimeContextResolver(AguiRuntimeContextResolver runtimeContextResolver) {
            this.runtimeContextResolver = runtimeContextResolver;
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
