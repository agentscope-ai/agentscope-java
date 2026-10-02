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
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
@SuppressWarnings("deprecation")
public abstract class AgentBase implements Agent {

    private final String agentId;
    private final String name;
    private final String description;
    private final List<Hook> hooks;
    private static final List<Hook> systemHooks = new CopyOnWriteArrayList<>();
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    private static final Comparator<Hook> HOOK_COMPARATOR = Comparator.comparingInt(Hook::priority);

    private final CopyOnWriteArrayList<RuntimeContextAware> runtimeContextAwareHooks =
            new CopyOnWriteArrayList<>();

    /**
     * Per-key call serialization tails. Each entry holds the completion signal of the most recently
     * enqueued call for that key; the next call for the same key chains after it, so calls sharing a
     * key run one-at-a-time (FIFO) while different keys run concurrently. See {@link
     * #callSerializationKey(RuntimeContext)} and {@link #serializeOnKey(Object, Mono)}.
     */
    private final ConcurrentHashMap<Object, Mono<Void>> callGates = new ConcurrentHashMap<>();

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     */
    public AgentBase(String name) {
        this(name, null, List.of());
    }

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     * @param description Agent description
     */
    public AgentBase(String name, String description) {
        this(name, description, List.of());
    }

    /**
     * @deprecated Use {@link #AgentBase(String, String, List)} instead.
     */
    @Deprecated
    public AgentBase(String name, String description, boolean checkRunning, List<Hook> hooks) {
        this(name, description, hooks);
    }

    /**
     * Constructor for AgentBase with hooks.
     *
     * @param name Agent name
     * @param description Agent description
     * @param hooks List of hooks for monitoring/intercepting execution
     */
    public AgentBase(String name, String description, List<Hook> hooks) {
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.hooks = new CopyOnWriteArrayList<>(hooks != null ? hooks : List.of());
        this.hooks.addAll(systemHooks);
        sortHooks();
        for (Hook h : this.hooks) {
            registerRuntimeContextHookIfNeeded(h);
        }
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

    /** @deprecated No longer enforced; per-session serialization handles concurrency. */
    @Deprecated
    public final boolean isCheckRunning() {
        return false;
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
        return callInternal(msgs, null, this::doCall);
    }

    /**
     * Extension point called by every {@code call()} overload, allowing subclasses to wrap the
     * entire invocation in additional middleware (e.g. the {@code onAgent} chain in
     * {@code ReActAgent}).
     *
     * <p>The default implementation attaches {@code context} to the Reactor Context (when
     * non-null) and delegates straight to {@link #runLifecycle}. Subclasses that override this
     * method must eventually invoke {@code runLifecycle(msgs, doCallFn)} to run the standard
     * lifecycle (shutdown guard, serialization gate, pre/post hooks, tracing).
     *
     * @param msgs     input messages
     * @param context  caller-supplied per-call {@link RuntimeContext}, or {@code null}
     * @param doCallFn the concrete call implementation ({@link #doCall} or a structured-output
     *                 variant)
     * @return response message
     */
    protected Mono<Msg> callInternal(
            List<Msg> msgs, RuntimeContext context, Function<List<Msg>, Mono<Msg>> doCallFn) {
        Mono<Msg> lifecycle = runLifecycle(msgs, doCallFn);
        return context == null
                ? lifecycle
                : lifecycle.contextWrite(c -> c.put(RUNTIME_CONTEXT_KEY, context));
    }

    /**
     * Reactor Context key carrying the per-call scope object returned by {@link
     * #beforeAgentExecution(List, RuntimeContext)}. Agents that maintain per-call state (e.g. {@code ReActAgent}'s
     * {@code CallExecution}) read it back from the Context in {@link #doCall} / {@link
     * #handleInterrupt} so concurrent calls on one instance never share mutable per-call state.
     */
    public static final String CALL_SCOPE_KEY = "io.agentscope.core.agent.AgentBase.callScope";

    /**
     * Reactor Context key carrying the caller-supplied per-call {@link RuntimeContext}. Set by the
     * {@code call(msgs, context)} / {@code stream(..., context)} / {@code streamEvents(msgs,
     * context)} overloads via {@code contextWrite}, and read back here at call entry so the RC flows
     * per-subscription (concurrency-safe) instead of through a shared instance field.
     */
    public static final String RUNTIME_CONTEXT_KEY =
            "io.agentscope.core.agent.AgentBase.runtimeContext";

    /**
     * Reactor Context key carrying this call's graceful-shutdown {@code requestId} (issued by {@link
     * GracefulShutdownManager#registerRequest}). Threaded per-subscription so shutdown
     * interrupt/save target the exact in-flight call even when one agent instance serves many
     * concurrent calls — keying shutdown tracking by agent id would otherwise collapse concurrent
     * calls into a single entry.
     */
    public static final String SHUTDOWN_REQUEST_ID_KEY =
            "io.agentscope.core.agent.AgentBase.shutdownRequestId";

    /**
     * Shared {@code call()} lifecycle: acquire execution, then (inside {@code deferContextual} so
     * the caller-supplied {@link RuntimeContext} is read per-subscription) run {@link
     * #beforeAgentExecution(List, RuntimeContext)} (which returns this call's per-call scope), carry
     * that scope on the Reactor Context, and run the preCall → doCall → postCall chain with error
     * handling, releasing execution on terminate.
     */
    protected Mono<Msg> runLifecycle(List<Msg> msgs, Function<List<Msg>, Mono<Msg>> doCallFn) {
        return Mono.using(
                this::acquireExecution,
                resource ->
                        Mono.deferContextual(
                                cv -> {
                                    RuntimeContext rc =
                                            (RuntimeContext)
                                                    cv.getOrDefault(RUNTIME_CONTEXT_KEY, null);
                                    // Track this call as a distinct shutdown request (keyed by its
                                    // own requestId, not the shared agent id) so concurrent calls
                                    // on
                                    // one instance are interrupted/saved/unregistered
                                    // independently.
                                    String requestId =
                                            GracefulShutdownManager.getInstance()
                                                    .registerRequest(this);
                                    Object gateKey = callSerializationKey(rc);
                                    // Build the per-call lifecycle lazily so it only runs once the
                                    // serialization gate (if any) admits this call:
                                    // beforeAgentExecution resolves/loads the session slot and must
                                    // not race a concurrent same-session call.
                                    Mono<Msg> lifecycle =
                                            Mono.defer(
                                                    () ->
                                                            runLifecycleBody(
                                                                    msgs, rc, doCallFn, requestId));
                                    Mono<Msg> gated =
                                            gateKey == null
                                                    ? lifecycle
                                                    : serializeOnKey(gateKey, lifecycle);
                                    return gated.contextWrite(
                                                    c ->
                                                            requestId == null || requestId.isEmpty()
                                                                    ? c
                                                                    : c.put(
                                                                            SHUTDOWN_REQUEST_ID_KEY,
                                                                            requestId))
                                            .doFinally(
                                                    sig ->
                                                            GracefulShutdownManager.getInstance()
                                                                    .unregisterRequest(requestId));
                                }),
                this::releaseExecution,
                true);
    }

    private Mono<Msg> runLifecycleBody(
            List<Msg> msgs,
            RuntimeContext rc,
            Function<List<Msg>, Mono<Msg>> doCallFn,
            String requestId) {
        Object scope = beforeAgentExecution(msgs, rc);
        // Bind this call's resolved per-session state to the tracked shutdown request so graceful
        // shutdown interrupts / saves the exact (userId, sessionId) session rather than the agent's
        // no-arg "most-recently-active" accessors.
        GracefulShutdownManager.getInstance().bindRequestState(requestId, stateForCall(scope));
        Mono<Msg> body =
                TracerRegistry.get()
                        .callAgent(
                                this,
                                msgs,
                                () ->
                                        notifyPreCall(msgs, scope)
                                                .flatMap(doCallFn)
                                                .flatMap(this::notifyPostCall)
                                                .onErrorResume(
                                                        createErrorHandler(
                                                                msgs.toArray(new Msg[0]))));
        return scope == null ? body : body.contextWrite(c -> c.put(CALL_SCOPE_KEY, scope));
    }

    /**
     * Returns a key that serializes concurrent calls sharing it: calls with an equal, non-null key
     * run one-at-a-time in FIFO order, while calls with different keys (or a {@code null} key) run
     * concurrently. The default returns {@code null} (no serialization). {@code ReActAgent} returns
     * its {@code (userId, sessionId)} slot key so same-session calls do not corrupt shared
     * conversation state while distinct sessions run in parallel.
     *
     * @param rc the caller-supplied per-call {@link RuntimeContext} (may be {@code null})
     * @return the serialization key, or {@code null} to run without serialization
     */
    protected Object callSerializationKey(RuntimeContext rc) {
        return null;
    }

    /**
     * Serializes {@code action} against other actions sharing {@code key}: this call waits for the
     * previously-enqueued call with the same key to terminate before running, then becomes the tail
     * the next same-key call waits on. Releases its slot on any terminal signal (complete, error, or
     * cancel) so a failed/cancelled call never blocks the queue.
     */
    private <T> Mono<T> serializeOnKey(Object key, Mono<T> action) {
        return Mono.defer(
                () -> {
                    Sinks.Empty<Void> release = Sinks.empty();
                    Mono<Void> releaseMono = release.asMono();
                    @SuppressWarnings("unchecked")
                    Mono<Void>[] prev = new Mono[1];
                    callGates.compute(
                            key,
                            (k, tail) -> {
                                prev[0] = tail == null ? Mono.empty() : tail;
                                return releaseMono;
                            });
                    return prev[0].onErrorComplete()
                            .then(action)
                            .doFinally(
                                    sig -> {
                                        release.tryEmitEmpty();
                                        callGates.remove(key, releaseMono);
                                    });
                });
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
        return callInternal(msgs, null, m -> doCall(m, structuredOutputClass));
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
        return callInternal(msgs, null, m -> doCall(m, schema));
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

    /** @deprecated Subclasses should implement per-session interrupt via RuntimeContext. */
    @Deprecated
    @Override
    public void interrupt() {}

    /** @deprecated Subclasses should implement per-session interrupt via RuntimeContext. */
    @Deprecated
    @Override
    public void interrupt(Msg msg) {}

    /** @deprecated Subclasses should implement per-session interrupt via RuntimeContext. */
    @Deprecated
    public void interrupt(InterruptSource source) {}

    /** @deprecated No longer needed; ReActAgent uses per-session InterruptControl. */
    @Deprecated
    protected Mono<Void> checkInterruptedAsync() {
        return Mono.empty();
    }

    /** @deprecated No-op; per-session interrupt state is managed by AgentState.interruptControl(). */
    @Deprecated
    protected void resetInterruptFlag() {}

    private InterruptContext createInterruptContext() {
        return InterruptContext.builder().source(InterruptSource.USER).build();
    }

    /**
     * Acquire execution resources for a {@code call()} invocation.
     * Used as the {@code resourceSupplier} in {@link Mono#using} to guarantee that
     * {@link #releaseExecution} is always called on completion, error, or cancellation.
     *
     * @return this agent instance
     */
    private AgentBase acquireExecution() {
        GracefulShutdownManager.getInstance().ensureAcceptingRequests();
        return this;
    }

    private void releaseExecution(AgentBase resource) {
        afterAgentExecution();
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

    /** @deprecated No-op stub. */
    @Deprecated
    protected AtomicBoolean getInterruptFlag() {
        return new AtomicBoolean(false);
    }

    /** @deprecated Returns USER. Per-session interrupt source is on AgentState.interruptControl(). */
    @Deprecated
    protected InterruptSource getInterruptSource() {
        return InterruptSource.USER;
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
     * Returns the agent's mutable runtime state, or {@code null} if this agent type does not
     * maintain an {@link AgentState}.
     */
    public AgentState getAgentState() {
        return null;
    }

    /**
     * Returns the current per-call {@link RuntimeContext}, or {@code null} when the agent keeps no
     * per-call scope. The base implementation returns {@code null}; agents with per-call state
     * (e.g. {@code ReActAgent}) override this to return their active call scope's context. Because
     * the value is sourced from the agent's most-recently-activated scope, under concurrent calls
     * on one instance this reflects the latest call — middlewares/tools that need their own call's
     * context should read it from the per-subscription {@link RuntimeContext} they are handed.
     */
    public RuntimeContext getRuntimeContext() {
        return null;
    }

    /**
     * Invoked at the start of a {@code call} / stream-backed call, after {@link
     * #acquireExecution} and before any hooks. {@link io.agentscope.core.ReActAgent} uses this to
     * activate the per-call session slot from the supplied {@link RuntimeContext} and returns the
     * freshly-built per-call scope object, which is carried on the Reactor Context under {@link
     * #CALL_SCOPE_KEY}. Returning the scope here (rather than via a separate accessor) avoids any
     * window in which a concurrent call could overwrite a shared field between construction and
     * capture.
     *
     * @param msgs the messages passed by the caller to {@code call()}
     * @param rc the caller-supplied per-call {@link RuntimeContext}, or {@code null} when none was
     *     provided (read from the Reactor Context, so concurrency-safe)
     * @return this call's per-call scope object, or {@code null} if this agent type keeps none
     */
    protected Object beforeAgentExecution(List<Msg> msgs, RuntimeContext rc) {
        return null;
    }

    /**
     * Invoked in {@code Mono.using} cleanup, before clearing the running state. Pairs with {@link
     * #beforeAgentExecution(List, RuntimeContext)}. The default is a no-op.
     */
    protected void afterAgentExecution() {}

    /**
     * Pushes {@code ctx} to all {@link RuntimeContextAware} hooks registered for this agent. The
     * per-call {@link RuntimeContext} itself is no longer stored on a shared instance field; it
     * lives on the agent's per-call scope (see {@link #getRuntimeContext()}).
     */
    protected void bindRuntimeContextToHooks(RuntimeContext ctx) {
        for (RuntimeContextAware h : runtimeContextAwareHooks) {
            h.setRuntimeContext(ctx);
        }
    }

    /**
     * Clears the {@link RuntimeContext} previously pushed to all {@link RuntimeContextAware} hooks.
     */
    protected void unbindRuntimeContextFromHooks() {
        for (RuntimeContextAware h : runtimeContextAwareHooks) {
            h.setRuntimeContext(null);
        }
    }

    private void registerRuntimeContextHookIfNeeded(Hook hook) {
        if (hook instanceof RuntimeContextAware r && !runtimeContextAwareHooks.contains(r)) {
            runtimeContextAwareHooks.add(r);
        }
    }

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
            registerRuntimeContextHookIfNeeded(hook);
            sortHooks();
        }
    }

    private void sortHooks() {
        this.hooks.sort(HOOK_COMPARATOR);
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
            if (hook instanceof RuntimeContextAware r) {
                runtimeContextAwareHooks.remove(r);
            }
        }
    }

    /**
     * Get hooks sorted by priority (lower value = higher priority).
     * Hooks with the same priority maintain registration order.
     *
     * @return Sorted list of hooks
     */
    public List<Hook> getSortedHooks() {
        return hooks;
    }

    /**
     * Returns the initial system message to seed into {@link PreCallEvent} before hooks run.
     *
     * <p>The default implementation returns {@code null}. Subclasses (e.g. {@code ReActAgent})
     * override this to build a system message from their configured {@code sysPrompt}.
     *
     * @param callScope the per-call scope captured at call entry (may be {@code null}); lets
     *     subclasses resolve the call's {@link RuntimeContext} for system-prompt middlewares without
     *     reading a shared instance field
     * @return the seed system message, or {@code null} if none
     */
    protected Msg seedSystemMsg(Object callScope) {
        return null;
    }

    /**
     * Returns the {@link AgentState} whose conversation buffer should seed the pre-call memory
     * snapshot for this invocation. The default reads the agent-level {@link #getAgentState()};
     * subclasses with per-call scope (e.g. {@code ReActAgent}) return the scope's session state so
     * the snapshot is concurrency-correct.
     *
     * @param callScope the per-call scope captured at call entry (may be {@code null})
     * @return the state to snapshot, or {@code null} if none
     */
    protected AgentState stateForCall(Object callScope) {
        return getAgentState();
    }

    /**
     * Called after {@link PreCallEvent} hooks have run, with the final system message value.
     *
     * <p>The default implementation is a no-op. Subclasses (e.g. {@code ReActAgent}) override
     * this to persist the system message into a per-call {@code AtomicReference} so it is
     * available to subsequent events ({@code PreReasoningEvent}, {@code PreSummaryEvent}).
     *
     * @param systemMsg the system message produced by all PreCall hooks (may be null)
     * @param callScope the per-call scope captured at call entry (see {@link #beforeAgentExecution(List, RuntimeContext)});
     *     may be {@code null}
     */
    protected void consumeSystemMsgAfterPreCall(Msg systemMsg, Object callScope) {}

    /**
     * Notify all hooks that agent is starting (preCall hook).
     *
     * <p>The event's {@code inputMessages} contains the full message view:
     * a snapshot of the agent's current memory followed by the {@code callArgs} passed to
     * {@code call()}. Hooks may append non-SYSTEM messages to the tail. Injecting
     * {@link MsgRole#SYSTEM} messages via {@code setInputMessages} is forbidden and
     * detected at the end of this method — use {@link PreCallEvent#setSystemMessage} or
     * {@link PreCallEvent#appendSystemContent} instead.
     *
     * <p>After hooks run the system message is handed off via
     * {@link #consumeSystemMsgAfterPreCall(Msg, Object)}, and only the tail (messages beyond the
     * snapshot boundary) is returned for {@code doCall} to add to memory.
     *
     * @param callArgs messages passed by the caller to {@code call()}
     * @return Mono containing the new tail messages that {@code doCall} should add to memory
     */
    private Mono<List<Msg>> notifyPreCall(List<Msg> callArgs, Object callScope) {
        // Take a state snapshot before hooks run (pre-hook view), from this call's scope state.
        List<Msg> snapshot = List.of();
        AgentState agentState = stateForCall(callScope);
        if (agentState != null) {
            snapshot = agentState.getContext();
        }
        final int snapshotSize = snapshot.size();

        // Build full input for hooks: snapshot + callArgs
        List<Msg> fullInput = new ArrayList<>(snapshot);
        if (callArgs != null) {
            fullInput.addAll(callArgs);
        }

        PreCallEvent event = new PreCallEvent(this, fullInput);
        event.setSystemMessage(seedSystemMsg(callScope));

        Mono<PreCallEvent> result = Mono.just(event);
        for (Hook hook : getSortedHooks()) {
            result = result.flatMap(hook::onEvent);
        }

        return result.map(
                e -> {
                    // Hand off the system message to the per-call state
                    consumeSystemMsgAfterPreCall(e.getSystemMessage(), callScope);

                    // Extract the tail: messages appended beyond the snapshot boundary
                    List<Msg> currentInput = e.getInputMessages();
                    List<Msg> tail;
                    if (currentInput == null || currentInput.size() <= snapshotSize) {
                        tail = List.of();
                    } else {
                        tail =
                                new ArrayList<>(
                                        currentInput.subList(snapshotSize, currentInput.size()));
                    }

                    // Guard (ReActAgent only): hooks must not inject SYSTEM messages into the
                    // tail, since the tail is persisted to memory and SYSTEM messages would
                    // accumulate. Agents without memory (e.g. UserAgent) may legitimately
                    // pass SYSTEM messages as call arguments.
                    if (AgentBase.this instanceof io.agentscope.core.ReActAgent) {
                        for (Msg msg : tail) {
                            if (msg != null && msg.getRole() == MsgRole.SYSTEM) {
                                throw new IllegalStateException(
                                        "Hooks must not inject SYSTEM messages into"
                                                + " PreCallEvent.inputMessages. Use"
                                                + " event.setSystemMessage() or"
                                                + " event.appendSystemContent() instead.");
                            }
                        }
                    }

                    return tail;
                });
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
     * @deprecated since 2.0.0, for removal. Use {@code ReActAgent#streamEvents(List)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return createEventStream(options, () -> call(msgs));
    }

    /**
     * Stream with multiple input messages.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Optional class defining the structure
     * @return Flux of events emitted during execution
     * @deprecated since 2.0.0, for removal. Use {@code ReActAgent#streamEvents(...)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return createEventStream(options, () -> call(msgs, structuredModel));
    }

    /**
     * Stream with multiple input messages using a JSON schema.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param schema JSON schema defining the structure of the response
     * @return Flux of events emitted during execution
     * @deprecated since 2.0.0, for removal. Use {@code ReActAgent#streamEvents(...)} for the
     *     fine-grained {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return createEventStream(options, () -> call(msgs, schema));
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
                                            addHook(streamingHook);

                                            // Bus that subagent tools use to push child events
                                            // into this parent sink without an extra Flux layer.
                                            SubagentEventBus bus = sink::next;

                                            // Use Mono.defer to ensure trace context propagation
                                            // while maintaining streaming hook functionality
                                            Disposable callDisposable =
                                                    Mono.defer(() -> callSupplier.get())
                                                            .contextWrite(
                                                                    context ->
                                                                            context.put(
                                                                                            SubagentEventBus
                                                                                                    .CONTEXT_KEY,
                                                                                            bus)
                                                                                    .putAll(
                                                                                            ctxView))
                                                            .doFinally(
                                                                    signalType -> {
                                                                        // Remove temporary hook
                                                                        hooks.remove(streamingHook);
                                                                    })
                                                            .subscribe(
                                                                    finalMsg -> {
                                                                        if (options.shouldStream(
                                                                                EventType
                                                                                        .AGENT_RESULT)) {
                                                                            sink.next(
                                                                                    new Event(
                                                                                            EventType
                                                                                                    .AGENT_RESULT,
                                                                                            finalMsg,
                                                                                            true));
                                                                        }
                                                                    },
                                                                    sink::error,
                                                                    sink::complete);
                                            sink.onCancel(callDisposable);
                                        },
                                        FluxSink.OverflowStrategy.BUFFER)
                                .publishOn(Schedulers.boundedElastic()));
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }
}
