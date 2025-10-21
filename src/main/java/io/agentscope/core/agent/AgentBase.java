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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.StateModuleBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for all agents in the AgentScope framework.
 *
 * <p>This class provides common functionality for agents including memory management, state
 * persistence, and hook integration. It aligns with Python AgentBase patterns while leveraging
 * Java's type safety and object-oriented features.
 */
public abstract class AgentBase extends StateModuleBase implements Agent {

    private final String agentId;
    private final String name;
    private final List<Hook> hooks;
    private Memory memory;
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

    /**
     * Constructor for AgentBase.
     *
     * @param name Agent name
     * @param memory Memory instance for storing conversation history
     */
    public AgentBase(String name, Memory memory) {
        this(name, memory, List.of());
    }

    /**
     * Constructor for AgentBase with hooks.
     *
     * @param name Agent name
     * @param memory Memory instance for storing conversation history
     * @param hooks List of hooks for monitoring/intercepting execution
     */
    public AgentBase(String name, Memory memory, List<Hook> hooks) {
        super();
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.memory = memory;
        this.hooks = List.copyOf(hooks != null ? hooks : List.of());

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
     * @param msg Input message
     * @return Response message
     */
    @Override
    public final Mono<Msg> call(Msg msg) {
        return notifyStart(msg)
                .flatMap(
                        modifiedMsg ->
                                doCall(modifiedMsg)
                                        .flatMap(
                                                finalMsg -> {
                                                    if (finalMsg == null) {
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "Agent returned null"
                                                                                + " message"));
                                                    }
                                                    // Call onReasoning for final message
                                                    return notifyReasoning(finalMsg)
                                                            .then(notifyComplete(finalMsg));
                                                }))
                .onErrorResume(error -> notifyError(error).then(Mono.error(error)));
    }

    /**
     * Process a list of input messages and generate a response with hook execution.
     *
     * @param msgs Input messages
     * @return Response message
     */
    @Override
    public final Mono<Msg> call(List<Msg> msgs) {
        return notifyStart(msgs)
                .flatMap(
                        modifiedMsgs ->
                                doCall(modifiedMsgs)
                                        .flatMap(
                                                finalMsg -> {
                                                    if (finalMsg == null) {
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "Agent returned null"
                                                                                + " message"));
                                                    }
                                                    // Call onReasoning for final message
                                                    return notifyReasoning(finalMsg)
                                                            .then(notifyComplete(finalMsg));
                                                }))
                .onErrorResume(error -> notifyError(error).then(Mono.error(error)));
    }

    /**
     * Continue generation based on current memory state without adding new input.
     *
     * @return Response message
     */
    @Override
    public final Mono<Msg> call() {
        return doCall().flatMap(
                        finalMsg -> {
                            if (finalMsg == null) {
                                return Mono.error(
                                        new IllegalStateException("Agent returned null message"));
                            }
                            // Call onReasoning for final message
                            return notifyReasoning(finalMsg).then(notifyComplete(finalMsg));
                        })
                .onErrorResume(error -> notifyError(error).then(Mono.error(error)));
    }

    /**
     * Internal implementation for processing a single message.
     * Subclasses should return the final response message.
     * All intermediate messages (reasoning chunks, tool calls, tool results) should be
     * saved to memory and hooks should be notified during processing.
     */
    protected abstract Mono<Msg> doCall(Msg msg);

    /**
     * Internal implementation for processing multiple input messages.
     * Subclasses should return the final response message.
     * All intermediate messages (reasoning chunks, tool calls, tool results) should be
     * saved to memory and hooks should be notified during processing.
     */
    protected abstract Mono<Msg> doCall(List<Msg> msgs);

    /**
     * Internal implementation for continuing generation based on current memory.
     * Subclasses should return the final response message.
     * All intermediate messages (reasoning chunks, tool calls, tool results) should be
     * saved to memory and hooks should be notified during processing.
     * Default implementation delegates to doCall(List) with empty list.
     */
    protected Mono<Msg> doCall() {
        return doCall(List.of());
    }

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
     * Get the list of hooks for this agent.
     * Protected to allow subclasses to access hooks for custom notification logic.
     *
     * @return List of hooks
     */
    protected List<Hook> getHooks() {
        return hooks;
    }

    /**
     * Notify all hooks that agent is starting.
     *
     * @param msg Input message
     * @return Mono containing the original message
     */
    private Mono<Msg> notifyStart(Msg msg) {
        return Flux.fromIterable(hooks).flatMap(hook -> hook.onStart(this)).then(Mono.just(msg));
    }

    /**
     * Notify all hooks that agent is starting.
     *
     * @param msgs Input messages
     * @return Mono containing the original messages
     */
    private Mono<List<Msg>> notifyStart(List<Msg> msgs) {
        return Flux.fromIterable(hooks).flatMap(hook -> hook.onStart(this)).then(Mono.just(msgs));
    }

    /**
     * Notify all hooks about reasoning chunk based on their preferred mode.
     * Protected to allow subclasses to call this during streaming.
     *
     * @param chunk The incremental chunk message
     * @param accumulated The accumulated message so far
     * @return Mono that completes when all hooks are notified
     */
    protected Mono<Void> notifyReasoningChunk(Msg chunk, Msg accumulated) {
        return Flux.fromIterable(hooks)
                .flatMap(
                        hook -> {
                            // Determine which message to send based on hook's preference
                            Msg msgToSend =
                                    hook.reasoningChunkMode()
                                                    == io.agentscope.core.hook.ChunkMode.CUMULATIVE
                                            ? accumulated
                                            : chunk;
                            return hook.onReasoningChunk(this, msgToSend);
                        })
                .then();
    }

    /**
     * Notify all hooks about final reasoning message.
     *
     * @param msg Reasoning message
     * @return Mono containing potentially modified message
     */
    private Mono<Msg> notifyReasoning(Msg msg) {
        Mono<Msg> result = Mono.just(msg);
        for (Hook hook : hooks) {
            result = result.flatMap(m -> hook.onReasoning(this, m));
        }
        return result;
    }

    /**
     * Notify all hooks about tool call.
     * Protected to allow subclasses to call this when tool calls are generated.
     *
     * @param toolUse Tool use block
     * @return Mono containing potentially modified tool use block
     */
    protected Mono<ToolUseBlock> notifyToolCall(ToolUseBlock toolUse) {
        Mono<ToolUseBlock> result = Mono.just(toolUse);
        for (Hook hook : hooks) {
            result = result.flatMap(t -> hook.onToolCall(this, t));
        }
        return result;
    }

    /**
     * Notify all hooks about tool result.
     * Protected to allow subclasses to call this when tool results are available.
     *
     * @param toolResult Tool result block
     * @return Mono containing potentially modified tool result block
     */
    protected Mono<ToolResultBlock> notifyToolResult(ToolResultBlock toolResult) {
        Mono<ToolResultBlock> result = Mono.just(toolResult);
        for (Hook hook : hooks) {
            result = result.flatMap(t -> hook.onToolResult(this, t));
        }
        return result;
    }

    /**
     * Notify all hooks about completion.
     *
     * @param finalMsg Final message
     * @return Mono containing potentially modified final message
     */
    private Mono<Msg> notifyComplete(Msg finalMsg) {
        Mono<Msg> result = Mono.just(finalMsg);
        for (Hook hook : hooks) {
            result = result.flatMap(m -> hook.onComplete(this, m));
        }
        return result;
    }

    /**
     * Notify all hooks about error.
     *
     * @param error The error
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyError(Throwable error) {
        return Flux.fromIterable(hooks).flatMap(h -> h.onError(this, error)).then();
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

    @Override
    public String toString() {
        return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), agentId, name);
    }
}
