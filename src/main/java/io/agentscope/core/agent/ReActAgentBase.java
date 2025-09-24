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

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Base class for ReAct agents in AgentScope.
 * <p>
 * To support the ReAct algorithm, this class extends the AgentBase class by
 * adding two abstract interfaces: reasoning and acting, following the
 * Python version's ReAct agent architecture.
 */
public abstract class ReActAgentBase extends AgentBase {

    protected int maxIters;

    public ReActAgentBase(String name) {
        super(name, null);
        this.maxIters = 10; // Default max iterations
    }

    public ReActAgentBase(String name, Memory memory) {
        super(name, memory);
        this.maxIters = 10; // Default max iterations
    }

    public ReActAgentBase(String name, Memory memory, int maxIters) {
        super(name, memory);
        this.maxIters = maxIters;
    }

    /**
     * Get maximum iterations for ReAct loop.
     *
     * @return maximum iterations
     */
    public int getMaxIters() {
        return maxIters;
    }

    /**
     * Set maximum iterations for ReAct loop.
     *
     * @param maxIters maximum iterations
     */
    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    /**
     * The reasoning step in ReAct algorithm.
     * This method corresponds to Python's reasoning() method.
     *
     * @return Reasoning result message
     */
    public abstract Flux<Msg> reasoning();

    /**
     * The acting step in ReAct algorithm.
     * This method corresponds to Python's acting() method.
     *
     * @return Acting result message
     */
    public abstract Flux<Msg> acting();

    @Override
    protected Flux<Msg> doStream(Msg msg) {
        addToMemory(msg);
        return createLoopFlux();
    }

    @Override
    protected Flux<Msg> doStream(List<Msg> msgs) {
        for (Msg m : msgs) {
            addToMemory(m);
        }
        return createLoopFlux();
    }

    private Flux<Msg> createLoopFlux() {
        return iterateAsync(0);
    }

    private Flux<Msg> iterateAsync(int iter) {
        if (iter >= maxIters) {
            return Flux.empty();
        }

        // Reasoning: stream outputs to caller while persisting every piece into memory
        Flux<Msg> reasoningFlux = reasoning();

        Flux<Msg> actingFlux =
                Mono.just(new Object())
                        .flatMapMany(
                                ignore -> {
                                    List<Msg> messages = getMemory().getMessages();
                                    Msg lastReasoningMsg =
                                            messages != null && !messages.isEmpty()
                                                    ? messages.get(messages.size() - 1)
                                                    : null;
                                    if (lastReasoningMsg == null || isFinished(lastReasoningMsg)) {
                                        return Flux.empty();
                                    }
                                    Flux<Msg> actingFlux0 = acting();
                                    return Flux.concat(actingFlux0, iterateAsync(iter + 1));
                                });
        return Flux.concat(reasoningFlux, actingFlux);
    }

    /**
     * Observe a single message for ReAct algorithm.
     */
    protected reactor.core.publisher.Mono<Void> doObserve(Msg x) {
        return Mono.fromRunnable(() -> addToMemory(x));
    }

    /**
     * Observe multiple messages for ReAct algorithm.
     */
    protected reactor.core.publisher.Mono<Void> doObserve(List<Msg> x) {
        return Mono.fromRunnable(
                () -> {
                    for (Msg m : x) {
                        addToMemory(m);
                    }
                });
    }

    /**
     * Check if the given message indicates the ReAct loop should finish.
     * This can be overridden by subclasses to define custom finishing conditions.
     *
     * @param msg Message to check
     * @return true if the loop should finish, false otherwise
     */
    protected boolean isFinished(Msg msg) {
        // Default implementation: always continue unless explicitly finished
        // Subclasses should override this method to implement proper finish logic
        return false;
    }

    /**
     * Convert single message input to Msg.
     */
    protected Msg convertToMsg(Msg x) {
        return x;
    }

    /**
     * Convert multiple message input to Msg (use the last message).
     */
    protected Msg convertToMsg(List<Msg> x) {
        if (x != null && !x.isEmpty()) {
            return x.get(x.size() - 1);
        }
        throw new IllegalArgumentException("Cannot convert empty message list to Msg");
    }

    /**
     * Get the latest message from memory; fallback to provided message if unavailable.
     */
    private Msg getLastMessageFromMemoryOr(Msg fallback) {
        try {
            Memory mem = getMemory();
            if (mem != null) {
                java.util.List<Msg> msgs = mem.getMessages();
                if (msgs != null && !msgs.isEmpty()) {
                    return msgs.get(msgs.size() - 1);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
