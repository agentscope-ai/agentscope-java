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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
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
        // Track accumulated text for CUMULATIVE mode hooks
        StringBuilder accumulatedText = new StringBuilder();

        return notifyStart(msg)
                .flatMap(
                        modifiedMsg ->
                                doCall(modifiedMsg)
                                        .flatMap(
                                                m ->
                                                        notifyStreamingMsg(m, accumulatedText)
                                                                .thenReturn(m))
                                        .collectList()
                                        .flatMap(
                                                list -> {
                                                    if (list == null || list.isEmpty()) {
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "Stream completed without"
                                                                                + " emitting any"
                                                                                + " Msg"));
                                                    }
                                                    Msg finalMsg = mergeLastRoundMessages(list);
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
        // Track accumulated text for CUMULATIVE mode hooks
        StringBuilder accumulatedText = new StringBuilder();

        return notifyStart(msgs)
                .flatMap(
                        modifiedMsgs ->
                                doCall(modifiedMsgs)
                                        .flatMap(
                                                m ->
                                                        notifyStreamingMsg(m, accumulatedText)
                                                                .thenReturn(m))
                                        .collectList()
                                        .flatMap(
                                                list -> {
                                                    if (list == null || list.isEmpty()) {
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "Stream completed without"
                                                                                + " emitting any"
                                                                                + " Msg"));
                                                    }
                                                    Msg finalMsg = mergeLastRoundMessages(list);
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
        // Track accumulated text for CUMULATIVE mode hooks
        StringBuilder accumulatedText = new StringBuilder();

        return Mono.just(List.<Msg>of())
                .flatMap(
                        empty ->
                                doCall().flatMap(
                                                m ->
                                                        notifyStreamingMsg(m, accumulatedText)
                                                                .thenReturn(m))
                                        .collectList()
                                        .flatMap(
                                                list -> {
                                                    if (list == null || list.isEmpty()) {
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "Stream completed without"
                                                                                + " emitting any"
                                                                                + " Msg"));
                                                    }
                                                    Msg finalMsg = mergeLastRoundMessages(list);
                                                    // Call onReasoning for final message
                                                    return notifyReasoning(finalMsg)
                                                            .then(notifyComplete(finalMsg));
                                                }))
                .onErrorResume(error -> notifyError(error).then(Mono.error(error)));
    }

    /**
     * Internal implementation for processing a single message.
     * Subclasses should emit intermediate reasoning/acting results and complete.
     */
    protected abstract Flux<Msg> doCall(Msg msg);

    /**
     * Internal implementation for processing multiple input messages.
     * Subclasses should emit intermediate reasoning/acting results and complete.
     */
    protected abstract Flux<Msg> doCall(List<Msg> msgs);

    /**
     * Internal implementation for continuing generation based on current memory.
     * Subclasses should emit intermediate reasoning/acting results and complete.
     * Default implementation delegates to doCall(List) with empty list.
     */
    protected Flux<Msg> doCall() {
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
     * @return Mono containing potentially modified message
     */
    private Mono<Msg> notifyStart(Msg msg) {
        Mono<Msg> result = Mono.just(msg);
        for (Hook hook : hooks) {
            result = result.flatMap(m -> hook.onStart(this, m));
        }
        return result;
    }

    /**
     * Notify all hooks that agent is starting.
     *
     * @param msgs Input messages
     * @return Mono containing potentially modified messages
     */
    private Mono<List<Msg>> notifyStart(List<Msg> msgs) {
        Mono<List<Msg>> result = Mono.just(msgs);
        for (Hook hook : hooks) {
            result = result.flatMap(m -> hook.onStart(this, m));
        }
        return result;
    }

    /**
     * Notify hooks about streaming messages (reasoning chunks, tool calls, tool results).
     *
     * @param msg The streaming message
     * @param accumulatedText StringBuilder tracking accumulated text for CUMULATIVE mode
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyStreamingMsg(Msg msg, StringBuilder accumulatedText) {
        ContentBlock content = msg.getContent();
        if (content instanceof TextBlock tb) {
            // For text blocks, accumulate and call onReasoningChunk (not onReasoning)
            accumulatedText.append(tb.getText());

            Msg accumulated =
                    Msg.builder()
                            .id(msg.getId())
                            .name(msg.getName())
                            .role(msg.getRole())
                            .content(TextBlock.builder().text(accumulatedText.toString()).build())
                            .build();

            return notifyReasoningChunk(msg, accumulated).then();
        } else if (content instanceof ThinkingBlock) {
            // For thinking blocks, call onReasoningChunk without accumulation
            return notifyReasoningChunk(msg, msg).then();
        } else if (content instanceof ToolUseBlock tub) {
            return notifyToolCall(tub).then();
        } else if (content instanceof ToolResultBlock trb) {
            return notifyToolResult(trb).then();
        }
        return Mono.empty();
    }

    /**
     * Notify all hooks about reasoning chunk based on their preferred mode.
     *
     * @param chunk The incremental chunk message
     * @param accumulated The accumulated message so far
     * @return Mono that completes when all hooks are notified
     */
    private Mono<Void> notifyReasoningChunk(Msg chunk, Msg accumulated) {
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
     *
     * @param toolUse Tool use block
     * @return Mono containing potentially modified tool use block
     */
    private Mono<ToolUseBlock> notifyToolCall(ToolUseBlock toolUse) {
        Mono<ToolUseBlock> result = Mono.just(toolUse);
        for (Hook hook : hooks) {
            result = result.flatMap(t -> hook.onToolCall(this, t));
        }
        return result;
    }

    /**
     * Notify all hooks about tool result.
     *
     * @param toolResult Tool result block
     * @return Mono containing potentially modified tool result block
     */
    private Mono<ToolResultBlock> notifyToolResult(ToolResultBlock toolResult) {
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
