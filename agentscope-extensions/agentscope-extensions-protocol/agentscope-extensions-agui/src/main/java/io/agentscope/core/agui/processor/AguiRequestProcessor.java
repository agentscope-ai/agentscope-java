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
import io.agentscope.core.agui.event.AguiFramedEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.core.session.SessionLog;
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
    private final AguiRunBroadcaster broadcaster;

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
        this.broadcaster =
                builder.broadcaster != null ? builder.broadcaster : new AguiRunBroadcaster();
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
            interruptAgent(agent, runtimeContext, threadId);
        }
    }

    private static void interruptAgent(
            Agent agent, RuntimeContext runtimeContext, String threadId) {
        ReActAgent reActAgent = AguiUtil.asReActAgent(agent);
        if (reActAgent != null) {
            RuntimeContext interruptContext =
                    RuntimeContext.builder(runtimeContext).sessionId(threadId).build();
            reActAgent.interrupt(interruptContext);
        } else {
            agent.interrupt();
        }
    }

    /**
     * Process an ordinary AG-UI run request.
     *
     * <p>This method always follows the normal run path. SessionLog recovery is an explicit
     * AgentScope extension available through {@link #attach(AguiRuntimeContextRequest)}; it is not
     * inferred from empty messages on the standard run endpoint.
     *
     * <p>The {@link AguiRuntimeContextResolver} (if configured on this processor) is invoked with
     * the given request to obtain a caller-provided {@link RuntimeContext}. That context is copied
     * and enriched by {@link AguiAgentAdapter}, so callers can provide custom attributes without
     * replacing the standard AG-UI metadata.
     *
     * <p>The user id comes from the resolver (deployer decision) when it provides one, otherwise
     * from {@link AguiRuntimeContextRequest#getUserId()} (caller assertion); see {@link
     * #resolveRuntimeContext(AguiRuntimeContextRequest)}.
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

        RuntimeContext runtimeContext = resolveRuntimeContext(request);

        // Resolve agent ID
        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);
        String broadcastKey = broadcastKey(agentId, runtimeContext, threadId);

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
                                broadcaster.open(broadcastKey, runId);
                                // Publish complete source events, not individual frames: a tail
                                // snapshot must never land in the middle of one event.
                                List<AguiFramedEvent> pendingFrames = new ArrayList<>();
                                Runnable flushFrames =
                                        () -> {
                                            if (pendingFrames.isEmpty()) {
                                                return;
                                            }
                                            List<AguiFramedEvent> batch =
                                                    pendingFrames.stream()
                                                            .filter(
                                                                    frame ->
                                                                            !(frame.event()
                                                                                    instanceof
                                                                                    AguiEvent
                                                                                            .RunStarted))
                                                            .toList();
                                            pendingFrames.clear();
                                            // RUN_STARTED is replayed from the session log.
                                            if (!batch.isEmpty()) {
                                                broadcaster.publish(broadcastKey, runId, batch);
                                            }
                                        };
                                return Objects.requireNonNull(
                                                adapter.runFramed(
                                                        effectiveInput, effectiveRuntimeContext),
                                                "adapter event stream is null")
                                        .doOnNext(
                                                frame -> {
                                                    AguiEvent event = frame.event();
                                                    if (event instanceof AguiEvent.RunError) {
                                                        runErrorSeen.set(true);
                                                    }
                                                    resumeCoordinator.trackPendingInterrupts(
                                                            threadId,
                                                            runId,
                                                            event,
                                                            runErrorSeen.get());
                                                    if (!pendingFrames.isEmpty()
                                                            && pendingFrames.get(0).sourceSeq()
                                                                    != frame.sourceSeq()) {
                                                        flushFrames.run();
                                                    }
                                                    pendingFrames.add(frame);
                                                    if (event instanceof AguiEvent.RunStarted) {
                                                        flushFrames.run();
                                                    }
                                                    // Live frames carry no session cursor
                                                    // (sourceSeq = -1), so waiting for it to
                                                    // change would hold them until the run ends
                                                    // and a live tail would never see them.
                                                    if (frame.sourceSeq() < 0) {
                                                        flushFrames.run();
                                                    }
                                                })
                                        .doOnComplete(flushFrames::run)
                                        .doOnError(error -> flushFrames.run())
                                        .map(AguiFramedEvent::event)
                                        // Finish the run before the terminal signal reaches
                                        // the caller. doFinally runs after onComplete/onError
                                        // is propagated, so a caller that collected this run's
                                        // events and immediately started the next run on the
                                        // same thread (the common resume flow) could observe
                                        // the stale active-run marker and be rejected with
                                        // AGUI_INTERRUPT_CONTRACT_ERROR. doOnComplete/doOnError
                                        // run before propagation, giving a happens-before
                                        // guarantee; finishRun is idempotent, and doFinally
                                        // still covers the cancellation path.
                                        .doOnComplete(
                                                () -> resumeCoordinator.finishRun(threadId, runId))
                                        .doOnError(
                                                error ->
                                                        resumeCoordinator.finishRun(
                                                                threadId, runId))
                                        .doFinally(
                                                signalType -> {
                                                    resumeCoordinator.finishRun(threadId, runId);
                                                    broadcaster.close(broadcastKey, runId);
                                                });
                            } catch (Throwable error) {
                                resumeCoordinator.finishRun(threadId, runId);
                                broadcaster.close(broadcastKey, runId);
                                return Flux.fromIterable(processorErrorEvents(input, error));
                            }
                        });
        return new ProcessResult(agent, events, runtimeContext);
    }

    /**
     * Identifies the live channel of a run.
     *
     * <p>A thread id alone is not unique across users and agents, and a shared processor would
     * otherwise hand one session's live tail to another.
     */
    private static String broadcastKey(String agentId, RuntimeContext context, String threadId) {
        String userId = context != null ? context.getUserId() : null;
        return (userId == null ? "" : userId)
                + '\u0000'
                + (agentId == null ? "" : agentId)
                + '\u0000'
                + (threadId == null ? "" : threadId);
    }

    /**
     * Builds the {@link RuntimeContext} for a request, merging the two user-id sources.
     *
     * <p>Priority: {@link AguiRuntimeContextResolver} (deployer decision) &gt; {@code
     * AguiRuntimeContextRequest.getUserId()} (caller assertion) &gt; none. The resolver is
     * assembled by the deployer and receives this very request, so it can validate or override
     * the caller-asserted id; an assertion must never silently override a server-verified
     * identity. When both provide an id and they differ, a WARN is logged to expose the drift.
     *
     * @param request The AG-UI request context
     * @return The runtime context for this request
     */
    private RuntimeContext resolveRuntimeContext(AguiRuntimeContextRequest<?> request) {
        RuntimeContext resolved =
                runtimeContextResolver != null ? runtimeContextResolver.resolve(request) : null;
        RuntimeContext.Builder contextBuilder =
                RuntimeContext.builder(resolved).sessionId(request.getInput().getThreadId());
        String requestUserId = request.getUserId();
        String resolvedUserId = resolved != null ? resolved.getUserId() : null;
        boolean hasRequestUserId = requestUserId != null && !requestUserId.isBlank();
        boolean hasResolvedUserId = resolvedUserId != null && !resolvedUserId.isBlank();
        if (hasResolvedUserId && hasRequestUserId && !resolvedUserId.equals(requestUserId)) {
            logger.warn(
                    "AG-UI user identity conflict: resolver={} vs request={}; resolver wins",
                    resolvedUserId,
                    requestUserId);
        }
        if (!hasResolvedUserId && hasRequestUserId) {
            // Adopt the caller-asserted id only when the resolver made no decision; setting it
            // unconditionally would overwrite the resolver value copied by Builder#from.
            contextBuilder.userId(requestUserId);
        }
        return contextBuilder.build();
    }

    /**
     * Reconnects to an existing AgentScope run after a recovery cursor.
     *
     * <p>This is an AgentScope extension, not an AG-UI standard endpoint. It is the reconnect
     * path of a run: the application supplies the conversation history and a cursor that is a
     * presentation boundary, and this method delivers everything after that cursor. It never
     * starts, resumes or re-executes an Agent run, never calls a model, and never writes to the
     * session log, so calling it repeatedly with the same cursor is idempotent.
     *
     * <p>When the run is still active in this process, the frames published by the original
     * request are used as a low-latency tail: the log is replayed up to the tail's position and
     * the tail continues without a gap or a duplicate. Otherwise the committed log is replayed and
     * then followed by polling, which is slower but always correct.
     *
     * <p>{@code after} must be the sequence of an event whose effects are fully present in the
     * application history; a cursor from the middle of a message replays partial frames.
     *
     * @param request the AG-UI request context
     * @param after the cursor returned by the application history query
     * @param executionRunId the SessionLog execution run id, or {@code null} to follow active runs
     * @return framed AG-UI reconnect events
     */
    public Flux<AguiFramedEvent> attach(
            AguiRuntimeContextRequest<?> request, long after, String executionRunId) {
        RunAgentInput input = request.getInput();
        RuntimeContext runtimeContext;
        try {
            runtimeContext = resolveRuntimeContext(request);
        } catch (RuntimeException error) {
            // A failing resolver is reported like any other attach error instead of escaping as a
            // transport exception.
            return Flux.fromIterable(framed(processorErrorEvents(input, error), -1));
        }
        String agentId =
                resolveAgentId(input, request.getHeaderAgentId(), request.getPathAgentId());
        Agent agent;
        try {
            agent =
                    agentResolver.resolveAgent(
                            agentId, input.getThreadId(), runtimeContext.getUserId());
        } catch (RuntimeException error) {
            // AgentNotFoundException and other resolver failures are reported like any other
            // attach error instead of escaping as a transport exception.
            return Flux.fromIterable(framed(processorErrorEvents(input, error), -1));
        }
        SessionLog sessionLog;
        try {
            sessionLog = AguiUtil.sessionLog(agent, runtimeContext);
        } catch (RuntimeException error) {
            return Flux.fromIterable(framed(processorErrorEvents(input, error), -1));
        }
        if (sessionLog == null) {
            return Flux.fromIterable(
                    framed(
                            processorErrorEvents(
                                    input,
                                    new IllegalStateException(
                                            "Session log attach requires a HarnessAgent with"
                                                    + " SessionHistoryMode.EVENT_LOG")),
                            -1));
        }
        // Register the live tail only when the returned stream is subscribed: an attach that is
        // never subscribed must not occupy a channel queue.
        String broadcastKey = broadcastKey(agentId, runtimeContext, input.getThreadId());
        return Flux.defer(
                () -> {
                    AguiRunBroadcaster.LiveRun live = broadcaster.live(broadcastKey);
                    return AguiSessionLogAttach.attach(
                            input, sessionLog, after, live, executionRunId, config);
                });
    }

    /**
     * Attaches using the cursor and execution run id carried by the request.
     *
     * <p>The cursor comes from {@code Last-Event-ID} or {@code forwardedProps.agentscope
     * .afterSequence}; the run id comes from {@code forwardedProps.agentscope.runId} or {@code
     * forwardedProps.agentscope.executionRunId}.
     *
     * @param request the AG-UI request context
     * @return framed AG-UI replay events
     */
    public Flux<AguiFramedEvent> attach(AguiRuntimeContextRequest<?> request) {
        RunAgentInput input = request.getInput();
        Long after;
        try {
            after = AguiSessionCursor.resolve(request, input);
        } catch (IllegalArgumentException error) {
            return Flux.fromIterable(framed(processorErrorEvents(input, error), -1));
        }
        if (after == null) {
            return Flux.fromIterable(
                    framed(
                            processorErrorEvents(
                                    input,
                                    new IllegalArgumentException(
                                            "An attach cursor is required: Last-Event-ID or"
                                                    + " forwardedProps.agentscope.afterSequence")),
                            -1));
        }
        return attach(request, after, AguiSessionCursor.resolveRunId(input));
    }

    private static List<AguiFramedEvent> framed(List<AguiEvent> events, long seq) {
        List<AguiFramedEvent> framed = new ArrayList<>(events.size());
        for (AguiEvent event : events) {
            framed.add(new AguiFramedEvent(seq, event));
        }
        return framed;
    }

    private List<AguiEvent> processorErrorEvents(RunAgentInput input, Throwable error) {
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
        return List.copyOf(events);
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
        private AguiRunBroadcaster broadcaster;

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
         * Set the live frame broadcaster used to hand a reconnecting client over to a running
         * thread. Optional; when null, a process-local broadcaster is created.
         *
         * @param broadcaster The broadcaster shared by all runs of this processor
         * @return This builder
         */
        public Builder broadcaster(AguiRunBroadcaster broadcaster) {
            this.broadcaster = broadcaster;
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
