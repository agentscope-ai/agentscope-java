/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Abstract base class for all agents in the AgentScope framework.
 *
 * <p>This class provides common functionality for agents including basic hook integration,
 * MsgHub subscriber management, interrupt handling, tracing, and state management through StateModule.
 * It does NOT manage memory - that is the responsibility of specific agent implementations like
 * ReActAgent.
 *
 * <p>Design Philosophy:
 * <ul>
 *   <li>AgentBase provides infrastructure (hooks, subscriptions, interrupt, state) but not domain
 *       logic</li>
 *   <li>Memory management is delegated to concrete agents that need it (e.g., ReActAgent)</li>
 *   <li>State management implements StateModule interface</li>
 *   <li>Interrupt mechanism uses reactive patterns: subclasses call checkInterruptedAsync()
 *       at appropriate checkpoints, which propagates InterruptedException through Mono chain</li>
 *   <li>Observe pattern: agents can receive messages without generating a reply</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 * Agent instances are NOT designed for concurrent execution. A single agent instance should not
 * be invoked concurrently from multiple threads (e.g., calling {@code call()} or {@code stream()}
 * simultaneously). The hooks list is mutable and modified during streaming operations without
 * synchronization, which is safe only under single-threaded execution per agent instance.
 *
 * <p><b>Interrupt Mechanism:</b>
 * <pre>{@code
 * // External call to interrupt
 * agent.interrupt(userMsg);
 *
 * // Inside agent's Mono chain, at checkpoints:
 * return checkInterruptedAsync()
 *     .then(doWork())
 *     .flatMap(result -> checkInterruptedAsync().thenReturn(result));
 *
 * // AgentBase.call() catches the exception:
 * .onErrorResume(error -> {
 *     if (error instanceof InterruptedException) {
 *         return handleInterrupt(context, msg);
 *     }
 *     ...
 * });
 * }</pre>
 */
public abstract class AgentBase implements StateModule, Agent {

    private final String agentId;
    private final String name;
    private final String description;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean checkRunning;
    private final List<Hook> hooks;
    private static final List<Hook> systemHooks = new CopyOnWriteArrayList<>();
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    // Interrupt state management (available to all agents)
    private final AtomicBoolean interruptFlag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userInterruptMessage = new AtomicReference<>(null);

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     */
    public AgentBase(String name) {
        this(name, null, true, List.of());
    }

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     * @param description Agent description
     */
    public AgentBase(String name, String description) {
        this(name, description, true, List.of());
    }

    /**
     * Constructor for AgentBase with hooks.
     *
     * @param name Agent name
     * @param description Agent description
     * @param checkRunning Whether to check running state
     * @param hooks List of hooks for monitoring/intercepting execution
     */
    public AgentBase(String name, String description, boolean checkRunning, List<Hook> hooks) {
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.checkRunning = checkRunning;
        this.hooks = new CopyOnWriteArrayList<>(hooks != null ? hooks : List.of());
        this.hooks.addAll(systemHooks);
    }

    @Override
    public final String getAgentId() {
        return agentId;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description != null ? description : Agent.super.getDescription();
    }

    /**
     * Process a list of input messages and generate a response with hook execution.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param msgs Input messages
     * @return Response message
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs) {
        if (!running.compareAndSet(false, true) && checkRunning) {
            return Mono.error(
                    new IllegalStateException(
                            "Agent is still running, please wait for it to finish"));
        }
        resetInterruptFlag();

        return TracerRegistry.get()
                .callAgent(
                        this,
                        msgs,
                        () ->
                                notifyPreCall(msgs)
                                        .flatMap(this::doCall)
                                        .flatMap(this::notifyPostCall)
                                        .onErrorResume(
                                                createErrorHandler(msgs.toArray(new Msg[0]))))
                .doFinally(signalType -> running.set(false));
    }

    /**
     * Process multiple input messages and generate structured output with hook execution.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param msgs Input messages
     * @param structuredOutputClass Class defining the structure of the output
     * @return Response message with structured data in metadata
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        if (!running.compareAndSet(false, true) && checkRunning) {
            return Mono.error(
                    new IllegalStateException(
                            "Agent is still running, please wait for it to finish"));
        }
        resetInterruptFlag();

        return TracerRegistry.get()
                .callAgent(
                        this,
                        msgs,
                        () ->
                                notifyPreCall(msgs)
                                        .flatMap(m -> doCall(m, structuredOutputClass))
                                        .flatMap(this::notifyPostCall)
                                        .onErrorResume(
                                                createErrorHandler(msgs.toArray(new Msg[0]))))
                .doFinally(signalType -> running.set(false));
    }

    /**
     * Process multiple input messages and generate structured output with hook execution.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param msgs Input messages
     * @param schema com.fasterxml.jackson.databind.JsonNode instance defining the structure of the output
     * @return Response message with structured data in metadata
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        if (!running.compareAndSet(false, true) && checkRunning) {
            return Mono.error(
                    new IllegalStateException(
                            "Agent is still running, please wait for it to finish"));
        }
        resetInterruptFlag();

        return TracerRegistry.get()
                .callAgent(
                        this,
                        msgs,
                        () ->
                                notifyPreCall(msgs)
                                        .flatMap(m -> doCall(m, schema))
                                        .flatMap(this::notifyPostCall)
                                        .onErrorResume(
                                                createErrorHandler(msgs.toArray(new Msg[0]))))
                .doFinally(signalType -> running.set(false));
    }

    /**
     * Internal implementation for processing multiple input messages.
     * Subclasses must implement their specific logic here.
     *
     * @param msgs Input messages
     * @return Response message
     */
    protected abstract Mono<Msg> doCall(List<Msg> msgs);

    /**
     * Internal implementation for processing multiple messages with structured output.
     * Subclasses that support structured output must override this method.
     * Default implementation throws UnsupportedOperationException.
     *
     * @param msgs Input messages
     * @param structuredOutputClass Class defining the structure
     * @return Response message with structured data in metadata
     */
    protected Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + getClass().getSimpleName()));
    }

    /**
     * Internal implementation for processing multiple messages with structured output.
     * Subclasses that support structured output must override this method.
     * Default implementation throws UnsupportedOperationException.
     *
     * @param msgs Input messages
     * @param outputSchema com.fasterxml.jackson.databind.JsonNode instance defining the structure
     * @return Response message with structured data in metadata
     */
    protected Mono<Msg> doCall(List<Msg> msgs, JsonNode outputSchema) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Structured output not supported by " + outputSchema.asText()));
    }

    public static void addSystemHook(Hook hook) {
        systemHooks.add(hook);
    }

    public static void removeSystemHook(Hook hook) {
        systemHooks.remove(hook);
    }

    /**
     * Interrupt the current agent execution.
     * Sets an interrupt flag that will be checked by the agent at appropriate checkpoints.
     */
    @Override
    public void interrupt() {
        interruptFlag.set(true);
    }

    /**
     * Interrupt the current agent execution with a user message.
     * Sets an interrupt flag and associates a user message with the interruption.
     *
     * @param msg User message associated with the interruption
     */
    @Override
    public void interrupt(Msg msg) {
        interruptFlag.set(true);
        if (msg != null) {
            userInterruptMessage.set(msg);
        }
    }

    /**
     * Check if the agent execution has been interrupted (reactive version).
     * Returns a Mono that completes normally if not interrupted, or errors with
     * InterruptedException if interrupted.
     *
     * <p>Subclasses should call this at appropriate checkpoints in their Mono chains.
     * For simple agents (like UserAgent), checkpoints may not be needed.
     * For complex agents (like ReActAgent), call this at:
     * <ul>
     *   <li>Start of each iteration</li>
     *   <li>Before/after reasoning</li>
     *   <li>Before/after each tool execution</li>
     *   <li>During streaming (each chunk)</li>
     * </ul>
     *
     * <p>Example usage:
     * <pre>{@code
     * return checkInterruptedAsync()
     *     .then(reasoning())
     *     .flatMap(result -> checkInterruptedAsync().thenReturn(result))
     *     .flatMap(result -> executeTools(result));
     * }</pre>
     *
     * @return Mono that completes if not interrupted, or errors if interrupted
     */
    protected Mono<Void> checkInterruptedAsync() {
        return Mono.defer(
                () ->
                        interruptFlag.get()
                                ? Mono.error(
                                        new InterruptedException("Agent execution interrupted"))
                                : Mono.empty());
    }

    /**
     * Reset the interrupt flag and associated state.
     * This is called at the beginning of each call() to prepare for new execution.
     */
    protected void resetInterruptFlag() {
        interruptFlag.set(false);
        userInterruptMessage.set(null);
    }

    /**
     * Create interrupt context from current interrupt state.
     * Helper method to avoid code duplication.
     *
     * @return InterruptContext with current user message
     */
    private InterruptContext createInterruptContext() {
        return InterruptContext.builder()
                .source(InterruptSource.USER)
                .userMessage(userInterruptMessage.get())
                .build();
    }

    /**
     * Create error handler for call() methods.
     * Handles InterruptedException specially and delegates to handleInterrupt,
     * while notifying hooks for other errors.
     *
     * @param originalArgs Original arguments to pass to handleInterrupt
     * @return Function that handles errors appropriately
     */
    private Function<Throwable, Mono<Msg>> createErrorHandler(Msg... originalArgs) {
        return error -> {
            if (error instanceof InterruptedException
                    || (error.getCause() instanceof InterruptedException)) {
                return handleInterrupt(createInterruptContext(), originalArgs);
            }
            return notifyError(error).then(Mono.error(error));
        };
    }

    /**
     * Get the interrupt flag for access by subclasses.
     * Subclasses can use this flag to implement custom interrupt-checking logic
     * in addition to the standard checkInterruptedAsync() method.
     *
     * @return The atomic boolean interrupt flag
     */
    protected AtomicBoolean getInterruptFlag() {
        return interruptFlag;
    }

    /**
     * Observe a message without generating a reply.
     * This allows agents to receive messages from other agents or the environment
     * without responding. It's commonly used in multi-agent collaboration scenarios.
     *
     * <p>Common implementation patterns:
     * <ul>
     *   <li>Stateless agents: Empty implementation if observation is not needed</li>
     *   <li>Stateful agents: Store message in memory/context for use in future calls</li>
     *   <li>Collaborative agents: Update shared knowledge or trigger side effects</li>
     * </ul>
     *
     * @param msg The message to observe
     * @return Mono that completes when observation is done
     */
    protected Mono<Void> doObserve(Msg msg) {
        return Mono.empty();
    }

    /**
     * Handle an interruption that occurred during execution.
     * Subclasses must implement this to provide recovery logic based on the interrupt context.
     *
     * <p>Implementation guidance:
     * <ul>
     *   <li>Simple agents: Return a basic interrupt acknowledgment message</li>
     *   <li>Complex agents: Generate a summary including any pending operations or partial results</li>
     *   <li>Stateful agents: Ensure state is saved appropriately before returning</li>
     * </ul>
     *
     * @param context The interrupt context containing metadata about the interruption
     * @param originalArgs The original arguments passed to the call() method (empty, single Msg,
     *     or List)
     * @return Recovery message to return to the user
     */
    protected abstract Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs);

    /**
     * Get the list of hooks for this agent.
     * Protected to allow subclasses to access hooks for custom notification logic.
     *
     * @return List of hooks
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    /**
     * Add a hook to this agent dynamically.
     *
     * <p>Hooks can be added during agent execution to provide temporary functionality.
     * This is commonly used for structured output handling or other short-lived behaviors.
     *
     * @param hook The hook to add
     */
    protected void addHook(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
        }
    }

    /**
     * Remove a hook from this agent dynamically.
     *
     * <p>Hooks should be removed when they are no longer needed to avoid memory leaks
     * and unintended side effects.
     *
     * @param hook The hook to remove
     */
    protected void removeHook(Hook hook) {
        if (hook != null) {
            hooks.remove(hook);
        }
    }

    /**
     * Get hooks sorted by priority (lower value = higher priority).
     * Hooks with the same priority maintain registration order.
     *
     * @return Sorted list of hooks
     */
    protected List<Hook> getSortedHooks() {
        return hooks.stream().sorted(java.util.Comparator.comparingInt(Hook::priority)).toList();
    }

    /**
     * Notify all hooks that agent is starting (preCall hook).
     *
     * <p>Hooks may modify the input messages via {@link PreCallEvent#setInputMessages(List)}.
     * Hooks are executed sequentially, with each hook receiving the event modified by previous hooks.
     *
     * @param msgs Input messages
     * @return Mono containing the messages after all hooks have processed them (may be modified)
     */
    private Mono<List<Msg>> notifyPreCall(List<Msg> msgs) {
        PreCallEvent event = new PreCallEvent(this, msgs);
        Mono<PreCallEvent> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        return result.map(PreCallEvent::getInputMessages);
    }

    /**
     * Notify all hooks about completion (postCall hook).
     * After hook notification, broadcasts the message to all subscribers.
     *
     * @param finalMsg Final message
     * @return Mono containing potentially modified final message
     */
    private Mono<Msg> notifyPostCall(Msg finalMsg) {
        if (finalMsg == null) {
            return Mono.error(new IllegalStateException("Agent returned null message"));
        }
        PostCallEvent event = new PostCallEvent(this, finalMsg);
        Mono<PostCallEvent> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }
        // After hooks, broadcast to subscribers
        return result.map(PostCallEvent::getFinalMessage)
                .flatMap(msg -> broadcastToSubscribers(msg).thenReturn(msg));
    }

    /**
     * Notify all hooks about error.
     *
     * @param error The error
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyError(Throwable error) {
        ErrorEvent event = new ErrorEvent(this, error);
        return Flux.fromIterable(getSortedHooks()).flatMap(hook -> hook.onEvent(event)).then();
    }

    /**
     * Remove all subscribers for a specific MsgHub.
     * This method is typically called when a MsgHub is being destroyed or reset.
     * After calling this method, the agent will no longer receive messages from the specified hub.
     *
     * @param hubId MsgHub identifier
     */
    public void removeSubscribers(String hubId) {
        hubSubscribers.remove(hubId);
    }

    /**
     * Reset the subscriber list for a specific MsgHub.
     * This replaces any existing subscribers for the given hub with the new list.
     * Typically called by MsgHub when the subscription topology changes.
     *
     * @param hubId MsgHub identifier
     * @param subscribers New list of subscribers (will be copied)
     */
    public void resetSubscribers(String hubId, List<AgentBase> subscribers) {
        hubSubscribers.put(hubId, new ArrayList<>(subscribers));
    }

    /**
     * Check if this agent has any subscribers.
     * Subscribers are agents that will receive messages published through MsgHub.
     *
     * @return True if agent has one or more subscribers
     */
    public boolean hasSubscribers() {
        return !hubSubscribers.isEmpty()
                && hubSubscribers.values().stream().anyMatch(list -> !list.isEmpty());
    }

    /**
     * Get the total number of subscribers across all MsgHubs.
     * Subscribers are agents that will receive messages published through MsgHub.
     *
     * @return Total count of subscribers
     */
    public int getSubscriberCount() {
        return hubSubscribers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Broadcast a message to all subscribers across all MsgHubs.
     * This method is called automatically after each agent call to implement
     * the MsgHub auto-broadcast functionality.
     *
     * @param msg Message to broadcast
     * @return Mono that completes when all subscribers have observed the message
     */
    private Mono<Void> broadcastToSubscribers(Msg msg) {
        if (hubSubscribers.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(hubSubscribers.values())
                .flatMap(Flux::fromIterable)
                .flatMap(subscriber -> subscriber.observe(msg))
                .then();
    }

    /**
     * Observe a single message without generating a reply.
     * This is the public API that delegates to doObserve implementation.
     *
     * @param msg Message to observe
     * @return Mono that completes when observation is done
     */
    @Override
    public final Mono<Void> observe(Msg msg) {
        return doObserve(msg);
    }

    /**
     * Observe multiple messages without generating a reply.
     * This is the public API that delegates to doObserve implementation.
     *
     * @param msgs Messages to observe
     * @return Mono that completes when all observations are done
     */
    @Override
    public final Mono<Void> observe(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(msgs).flatMap(this::doObserve).then();
    }

    /**
     * Stream with multiple input messages.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    @Override
    public final Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return createEventStream(options, () -> call(msgs));
    }

    /**
     * Stream with multiple input messages.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Optional class defining the structure
     * @return Flux of events emitted during execution
     */
    @Override
    public final Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return createEventStream(options, () -> call(msgs, structuredModel));
    }

    /**
     * Helper method to create an event stream with proper hook lifecycle management.
     *
     * <p>This method handles the common logic for streaming events during agent execution,
     * including:
     * <ul>
     *   <li>Creating and registering a temporary StreamingHook</li>
     *   <li>Managing the hook lifecycle (add/remove from hooks list)</li>
     *   <li>Optionally emitting the final agent result as an event</li>
     *   <li>Properly propagating errors and completion signals</li>
     * </ul>
     *
     * @param options Stream configuration options
     * @param callSupplier Supplier that executes the agent call (either single message or list)
     * @return Flux of events emitted during execution
     */
    private Flux<Event> createEventStream(StreamOptions options, Supplier<Mono<Msg>> callSupplier) {
        return Flux.deferContextual(
                ctxView ->
                        Flux.<Event>create(
                                        sink -> {
                                            // Create streaming hook with options
                                            StreamingHook streamingHook =
                                                    new StreamingHook(sink, options);

                                            // Add temporary hook
                                            hooks.add(streamingHook);

                                            // Use Mono.defer to ensure trace context propagation
                                            // while maintaining streaming hook functionality
                                            Mono.defer(() -> callSupplier.get())
                                                    .contextWrite(
                                                            context -> context.putAll(ctxView))
                                                    .doFinally(
                                                            signalType -> {
                                                                // Remove temporary hook
                                                                hooks.remove(streamingHook);
                                                            })
                                                    .subscribe(
                                                            finalMsg -> {
                                                                if (options.shouldStream(
                                                                        EventType.AGENT_RESULT)) {
                                                                    sink.next(
                                                                            new Event(
                                                                                    EventType
                                                                                            .AGENT_RESULT,
                                                                                    finalMsg,
                                                                                    true));
                                                                }

                                                                // Complete the stream
                                                                sink.complete();
                                                            },
                                                            sink::error);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                .publishOn(Schedulers.boundedElastic()));
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }
}
