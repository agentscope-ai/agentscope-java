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

package io.agentscope.core.a2a.agent.event;

import io.a2a.spec.Task;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Context for handler {@link io.a2a.client.ClientEvent}.
 *
 * <p>One A2A task might respond multiple times, so we need a context to store the response.
 */
public class ClientEventContext {

    private static final String A2A_REASONING_START = "a2a.reasoning.start";
    private static final String A2A_REASONING_CHUNK = "a2a.reasoning.chunk";
    private static final String A2A_REASONING_END = "a2a.reasoning.end";

    private final String currentRequestId;

    private final A2aAgent agent;

    private MonoSink<Msg> sink;

    private List<Hook> hooks;

    private List<MiddlewareBase> middlewares = List.of();

    private Task task;

    /**
     * Temporarily store the complete historical dialogue context at the time of this call,
     * specifically for use in constructing PreReasoning Events using the {@link #publishPreReasoning()} method.
     */
    private List<Msg> inputMessages;

    // Ensure that lifecycle events are triggered only once
    private final AtomicBoolean preReasoningFired = new AtomicBoolean(false);
    private final AtomicBoolean postReasoningFired = new AtomicBoolean(false);
    private final AtomicBoolean terminalDelivered = new AtomicBoolean(false);

    public ClientEventContext(String currentRequestId, A2aAgent agent) {
        this.currentRequestId = currentRequestId;
        this.agent = agent;
    }

    public String getCurrentRequestId() {
        return currentRequestId;
    }

    public A2aAgent getAgent() {
        return agent;
    }

    public MonoSink<Msg> getSink() {
        return sink;
    }

    public void setSink(MonoSink<Msg> sink) {
        this.sink = sink;
    }

    public List<Hook> getHooks() {
        return hooks;
    }

    public void setHooks(List<Hook> hooks) {
        this.hooks = hooks;
    }

    public void setMiddlewares(List<MiddlewareBase> middlewares) {
        this.middlewares = middlewares != null ? middlewares : List.of();
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = inputMessages;
    }

    public boolean isTerminalDelivered() {
        return terminalDelivered.get();
    }

    public boolean complete(Msg msg) {
        if (sink == null || !terminalDelivered.compareAndSet(false, true)) {
            return false;
        }
        sink.success(msg);
        return true;
    }

    public void completeExceptionally(Throwable error) {
        if (sink == null || !terminalDelivered.compareAndSet(false, true)) {
            return;
        }
        sink.error(error);
    }

    // ==========================================
    // Unified Event Publishing API
    // ==========================================

    /**
     * Trigger PreReasoningEvent (triggered only once)
     */
    void publishPreReasoning() {
        if (!preReasoningFired.compareAndSet(false, true)) {
            return;
        }

        List<Msg> msgs = inputMessages == null ? List.of() : inputMessages;
        if (hooks != null && !hooks.isEmpty()) {
            PreReasoningEvent preEvent = new PreReasoningEvent(agent, "A2A", null, msgs);

            Mono<PreReasoningEvent> eventMono = Mono.just(preEvent);
            for (Hook hook : hooks) {
                eventMono = eventMono.flatMap(hook::onEvent);
            }
            eventMono.block();
        }
        publishMiddlewareEvent(A2A_REASONING_START, Map.of("messages", msgs));
    }

    /**
     * Trigger ReasoningChunkEvent (streaming process)
     */
    void publishReasoningChunk(Msg chunkMsg) {
        publishPreReasoning(); // If not sent Pre before, send Pre first
        if (hooks != null && !hooks.isEmpty()) {
            ReasoningChunkEvent chunkEvent =
                    new ReasoningChunkEvent(agent, "A2A", null, chunkMsg, chunkMsg);

            Mono<ReasoningChunkEvent> eventMono = Mono.just(chunkEvent);
            for (Hook hook : hooks) {
                eventMono = eventMono.flatMap(hook::onEvent);
            }
            eventMono.block();
        }
        publishMiddlewareEvent(A2A_REASONING_CHUNK, Map.of("message", chunkMsg));
    }

    /**
     * Trigger PostReasoningEvent (triggered only once) and return the final reasoning message
     * after hooks have had a chance to modify it.
     *
     * @param finalMsg the original final reasoning message
     * @return the hook-modified reasoning message, or {@code finalMsg} if no hooks ran or no
     * modification was applied
     */
    Msg publishPostReasoning(Msg finalMsg) {
        if (!postReasoningFired.compareAndSet(false, true)) {
            return finalMsg;
        }

        publishPreReasoning();
        Msg result = finalMsg;
        if (hooks != null && !hooks.isEmpty()) {
            PostReasoningEvent postEvent = new PostReasoningEvent(agent, "A2A", null, finalMsg);

            Mono<PostReasoningEvent> eventMono = Mono.just(postEvent);
            for (Hook hook : hooks) {
                eventMono = eventMono.flatMap(hook::onEvent);
            }

            postEvent = eventMono.block();
            if (postEvent != null && postEvent.getReasoningMessage() != null) {
                result = postEvent.getReasoningMessage();
            }
        }
        publishMiddlewareEvent(A2A_REASONING_END, Map.of("message", result));
        return result;
    }

    /**
     * Sends the remote reasoning lifecycle event through the configured middleware chain.
     *
     * <p>The initial A2A bridge intentionally keeps the existing Hook and {@code stream()} paths
     * unchanged. Middleware receives a {@link CustomEvent} describing the A2A phase; its returned
     * stream is consumed so normal Reactor lifecycle operators (for example logging and metrics)
     * are executed.
     */
    private void publishMiddlewareEvent(String name, Map<String, Object> value) {
        if (middlewares.isEmpty()) {
            return;
        }
        AgentEvent event = new CustomEvent(name, value);
        Flux<AgentEvent> stream =
                MiddlewareChain.build(
                                middlewares,
                                agent,
                                null,
                                MiddlewareBase::onReasoning,
                                input -> Flux.just(event))
                        .apply(
                                new ReasoningInput(
                                        inputMessages == null ? List.of() : inputMessages,
                                        List.of(),
                                        null));
        stream.then().block();
    }
}
