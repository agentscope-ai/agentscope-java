/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent;

import io.agentscope.core.exception.InterruptSource;
import io.agentscope.core.exception.ToolInterruptedException;
import io.agentscope.core.hook.AgentHookType;
import io.agentscope.core.hook.HookManager;
import io.agentscope.core.hook.PostHook;
import io.agentscope.core.hook.PreHook;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.StateModuleBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Abstract base class for all agents in the AgentScope framework.
 *
 * <p>This class provides common functionality for agents including memory management,
 * state persistence, hook integration, and interruption handling.
 * It aligns with Python AgentBase patterns while leveraging Java's type safety.
 *
 * <p><b>Interruption Support:</b>
 * <ul>
 *   <li>User can call {@link #interrupt()} to cancel ongoing agent execution</li>
 *   <li>Subclasses must implement {@link #handleInterrupt(InterruptContext, Msg...)}
 *       to define recovery behavior</li>
 *   <li>Interruption state is tracked and cleaned up automatically</li>
 * </ul>
 */
public abstract class AgentBase extends StateModuleBase implements Agent {

    private static final Logger logger = LoggerFactory.getLogger(AgentBase.class);

    private final String agentId;
    private final String name;
    private final HookManager hookManager;
    private Memory memory;
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    // Interruption support (aligns with Python's interrupt/handle_interrupt pattern)
    private final AtomicReference<Sinks.One<InterruptContext>> interruptSink =
            new AtomicReference<>();
    private final AtomicReference<InterruptContext> interruptContext = new AtomicReference<>();

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     * @param memory Memory instance for storing conversation history
     */
    public AgentBase(String name, Memory memory) {
        super();
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.memory = memory;
        this.hookManager = new HookManager();

        // Register memory as a nested state module
        addNestedModule("memory", memory);

        // Register basic agent state - map to expected keys
        registerState("id", obj -> this.agentId, obj -> obj);
        registerState("name", obj -> this.name, obj -> obj);
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Memory getMemory() {
        return memory;
    }

    @Override
    public void setMemory(Memory memory) {
        this.memory = memory;
        // Update the nested module reference
        addNestedModule("memory", memory);
    }

    /**
     * Process a single input message and generate a response with hook execution.
     *
     * <p>This method wraps the stream execution and handles interruptions.
     * Aligns with Python's __call__() method pattern.
     *
     * @param x Input message
     * @return Response message
     */
    public final Mono<Msg> reply(Msg x) {
        Mono<Msg> execution =
                hookManager
                        .executeWithHooks(
                                this, AgentHookType.PRE_REPLY, "reply", () -> stream(x), x)
                        .collectList()
                        .flatMap(this::collectAndMergeMessages);

        return executeWithInterruption(execution);
    }

    /**
     * Process a list of input messages and generate a response with hook execution.
     *
     * @param x Input messages
     * @return Response message
     */
    public final Mono<Msg> reply(List<Msg> x) {
        Mono<Msg> execution =
                hookManager
                        .executeWithHooks(
                                this, AgentHookType.PRE_REPLY, "reply", () -> stream(x), x)
                        .collectList()
                        .flatMap(this::collectAndMergeMessages);

        return executeWithInterruption(execution);
    }

    /**
     * Execute a Mono with interruption support.
     * Extracts common interrupt setup/teardown logic to reduce duplication.
     *
     * @param execution The execution to wrap with interruption handling
     * @return Execution wrapped with interrupt handling
     */
    private Mono<Msg> executeWithInterruption(Mono<Msg> execution) {
        // Reset interrupt context for new execution
        interruptContext.set(null);
        Sinks.One<InterruptContext> sink = Sinks.one();
        interruptSink.set(sink);

        return execution
                .onErrorResume(this::handleReplyError)
                .takeUntilOther(sink.asMono())
                .doFinally(signal -> interruptSink.set(null));
    }

    /**
     * Collect and merge messages from stream execution.
     *
     * @param list List of messages from stream
     * @return Merged message
     */
    private Mono<Msg> collectAndMergeMessages(List<Msg> list) {
        if (list == null || list.isEmpty()) {
            return Mono.error(
                    new IllegalStateException("Stream completed without emitting any Msg"));
        }
        return Mono.just(mergeLastRoundMessages(list));
    }

    /**
     * Reactive Streams implementation for a single message.
     * Subclasses should emit intermediate reasoning/acting results and complete.
     */
    protected abstract Flux<Msg> doStream(Msg msg);

    /**
     * Reactive Streams implementation for multiple input messages.
     * Subclasses should emit intermediate reasoning/acting results and complete.
     */
    protected abstract Flux<Msg> doStream(List<Msg> msgs);

    /**
     * Helper method to add a message to memory.
     *
     * @param message Message to add
     */
    protected void addToMemory(Msg message) {
        if (memory != null && message != null) {
            memory.addMessage(message);
        }
    }

    /**
     * Get the hook manager for this agent.
     *
     * @return Hook manager instance
     */
    protected HookManager getHookManager() {
        return hookManager;
    }

    /**
     * Stream-based reply for a single message (default: one-shot).
     *
     * @param msg Input message
     * @return Stream of response messages
     */
    public Flux<Msg> stream(Msg msg) {
        return doStream(msg);
    }

    /**
     * Stream-based reply for multiple messages (default: one-shot).
     *
     * @param msgs Input messages
     * @return Stream of response messages
     */
    public Flux<Msg> stream(List<Msg> msgs) {
        return doStream(msgs);
    }

    /**
     * Remove subscribers for a specific MsgHub.
     * This is used by MsgHub for cleanup operations.
     *
     * @param hubId MsgHub identifier
     */
    public void removeSubscribers(String hubId) {
        hubSubscribers.remove(hubId);
    }

    /**
     * Reset subscribers for a specific MsgHub.
     * This is used by MsgHub to update subscriber relationships.
     *
     * @param hubId MsgHub identifier
     * @param subscribers List of current subscribers
     */
    public void resetSubscribers(String hubId, List<AgentBase> subscribers) {
        hubSubscribers.put(hubId, new ArrayList<>(subscribers));
    }

    /**
     * Check if this agent has any subscribers.
     * This is used by MsgHub tests.
     *
     * @return True if agent has subscribers
     */
    public boolean hasSubscribers() {
        return !hubSubscribers.isEmpty()
                && hubSubscribers.values().stream().anyMatch(list -> !list.isEmpty());
    }

    /**
     * Get the number of subscribers.
     * This is used by MsgHub tests.
     *
     * @return Number of subscribers
     */
    public int getSubscriberCount() {
        return hubSubscribers.values().stream().mapToInt(List::size).sum();
    }

    // Hook management methods

    /**
     * Register an instance-level pre-hook.
     *
     * @param hookType Type of hook
     * @param hookName Unique name for the hook
     * @param hook Hook implementation
     */
    public void registerInstancePreHook(
            AgentHookType hookType, String hookName, PreHook<? extends AgentBase> hook) {
        hookManager.registerInstancePreHook(hookType, hookName, hook);
    }

    /**
     * Register an instance-level post-hook.
     *
     * @param hookType Type of hook
     * @param hookName Unique name for the hook
     * @param hook Hook implementation
     */
    public void registerInstancePostHook(
            AgentHookType hookType, String hookName, PostHook<? extends AgentBase> hook) {
        hookManager.registerInstancePostHook(hookType, hookName, hook);
    }

    /**
     * Remove an instance-level hook.
     *
     * @param hookType Type of hook
     * @param hookName Name of hook to remove
     * @return True if hook was removed
     */
    public boolean removeInstanceHook(AgentHookType hookType, String hookName) {
        return hookManager.removeInstanceHook(hookType, hookName);
    }

    /**
     * Clear all instance-level hooks of a specific type.
     *
     * @param hookType Type of hooks to clear (null to clear all types)
     */
    public void clearInstanceHooks(AgentHookType hookType) {
        hookManager.clearInstanceHooks(hookType);
    }

    /**
     * Clear all instance-level hooks.
     */
    public void clearInstanceHooks() {
        clearInstanceHooks(null);
    }

    // ===== Interruption Support =====

    /**
     * Interrupt the current agent execution.
     * Aligns with Python's interrupt() method.
     *
     * <p>This method will:
     * <ul>
     *   <li>Create an interrupt context with USER source</li>
     *   <li>Dispose the current reactive execution</li>
     *   <li>Trigger handleInterrupt() to generate recovery message</li>
     * </ul>
     */
    public void interrupt() {
        interrupt(null);
    }

    /**
     * Interrupt the current agent execution with a custom user message.
     *
     * @param userMessage Optional message from user explaining the interruption
     */
    public void interrupt(Msg userMessage) {
        logger.info("Agent {} interrupted by user", name);

        // Create interrupt context
        InterruptContext ctx =
                InterruptContext.builder()
                        .source(InterruptSource.USER)
                        .userMessage(userMessage)
                        .timestamp(Instant.now())
                        .build();

        interruptContext.set(ctx);

        // Emit interrupt signal to cancel execution
        Sinks.One<InterruptContext> sink = interruptSink.get();
        if (sink != null) {
            sink.tryEmitValue(ctx);
        }
    }

    /**
     * Handle interruption and generate recovery message.
     * Aligns with Python's handle_interrupt() method.
     *
     * <p>Subclasses must implement this to define their interruption recovery behavior.
     * The default behavior should be to acknowledge the interruption and ask how to proceed.
     *
     * @param context The interruption context
     * @param originalArgs Original arguments passed to reply()
     * @return Recovery message to return to user
     */
    protected abstract Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs);

    /**
     * Get the current interrupt context (for subclasses).
     *
     * @return Current interrupt context, or null if not interrupted
     */
    protected InterruptContext getInterruptContext() {
        return interruptContext.get();
    }

    /**
     * Handle errors during reply execution, including interruptions.
     */
    private Mono<Msg> handleReplyError(Throwable e) {
        // Handle ToolInterruptedException
        if (e instanceof ToolInterruptedException) {
            ToolInterruptedException tie = (ToolInterruptedException) e;
            InterruptContext ctx = interruptContext.get();

            // If no context exists, create one from the exception
            if (ctx == null) {
                ctx =
                        InterruptContext.builder()
                                .source(tie.getSource())
                                .timestamp(Instant.now())
                                .build();
                interruptContext.set(ctx);
            }

            logger.info("Handling interruption from {}", ctx.getSource());
            return handleInterrupt(ctx);
        }

        // Propagate other errors
        return Mono.error(e);
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }

    private Msg mergeLastRoundMessages(List<Msg> messages) {
        int n = messages.size();
        // Locate the last ToolUseBlock as a marker of the final round (finish function)
        int lastToolIdx = -1;
        for (int i = n - 1; i >= 0; i--) {
            if (messages.get(i).getContent() instanceof ToolUseBlock) {
                lastToolIdx = i;
                break;
            }
        }
        int start = Math.max(lastToolIdx, 0);
        List<Msg> lastRound = messages.subList(start, n);

        // Merge text contents from last round; ignore non-text content when building final text
        StringBuilder combined = new StringBuilder();
        String id = null;
        for (Msg m : lastRound) {
            id = m.getId();
            ContentBlock cb = m.getContent();
            if (cb instanceof TextBlock tb) {
                combined.append(tb.getText());
            } else if (cb instanceof ThinkingBlock) {
                // Optionally include thinking
                // if (!combined.isEmpty()) combined.append("\n");
                // combined.append("<thinking>").append(tb.getThinking()).append("</thinking>");
            }
        }

        return Msg.builder()
                .id(id)
                .name(getName())
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(combined.toString()).build())
                .build();
    }
}
