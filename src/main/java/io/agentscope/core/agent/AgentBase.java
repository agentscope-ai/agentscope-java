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

import io.agentscope.core.hook.AgentHookType;
import io.agentscope.core.hook.HookManager;
import io.agentscope.core.hook.PostHook;
import io.agentscope.core.hook.PreHook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
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
 * This class provides common functionality for agents including memory management,
 * state persistence, and hook integration. It aligns with Python AgentBase patterns
 * while leveraging Java's type safety and object-oriented features.
 */
public abstract class AgentBase extends StateModuleBase implements Agent {

    private final String agentId;
    private final String name;
    private final HookManager hookManager;
    private Memory memory;
    private final Map<String, List<AgentBase>> hubSubscribers = new ConcurrentHashMap<>();

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
     * @param x Input message
     * @return Response message
     */
    public final Mono<Msg> reply(Msg x) {
        return hookManager
                .executeWithHooks(this, AgentHookType.PRE_REPLY, "reply", () -> stream(x), x)
                .collectList()
                .flatMap(
                        list -> {
                            if (list == null || list.isEmpty()) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "Stream completed without emitting any Msg"));
                            }
                            return Mono.just(mergeLastRoundMessages(list));
                        });
    }

    /**
     * Process a list of input messages and generate a response with hook execution.
     *
     * @param x Input messages
     * @return Response message
     */
    public final Mono<Msg> reply(List<Msg> x) {
        return hookManager
                .executeWithHooks(this, AgentHookType.PRE_REPLY, "reply", () -> stream(x), x)
                .collectList()
                .flatMap(
                        list -> {
                            if (list == null || list.isEmpty()) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "Stream completed without emitting any Msg"));
                            }
                            return Mono.just(mergeLastRoundMessages(list));
                        });
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
