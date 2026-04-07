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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.MonoSink;

/**
 * Context for handler {@link io.a2a.client.ClientEvent}.
 *
 * <p>One A2A task might respond multiple times, so we need a context to store the response.
 */
public class ClientEventContext {

    private final String currentRequestId;

    private final A2aAgent agent;

    private MonoSink<Msg> sink;

    private List<Hook> hooks;

    private Task task;

    /**
     * Temporarily store the complete historical dialogue context at the time of this call,
     * specifically for use in constructing PreReasoning Events using the {@link #publishPreReasoning()} method.
     */
    private List<Msg> inputMessages;

    // Ensure that lifecycle events are triggered only once
    private final AtomicBoolean preReasoningFired = new AtomicBoolean(false);
    private final AtomicBoolean postReasoningFired = new AtomicBoolean(false);

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

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = inputMessages;
    }

    // ==========================================
    // Unified Event Publishing API
    // ==========================================

    /**
     * Trigger PreReasoningEvent (triggered only once)
     */
    void publishPreReasoning() {
        if (hooks != null && !hooks.isEmpty() && preReasoningFired.compareAndSet(false, true)) {
            List<Msg> msgs = inputMessages == null ? List.of() : inputMessages;
            PreReasoningEvent preEvent = new PreReasoningEvent(agent, "A2A", null, msgs);
            hooks.forEach(hook -> hook.onEvent(preEvent).block());
        }
    }

    /**
     * Trigger ReasoningChunkEvent (streaming process)
     */
    void publishReasoningChunk(Msg chunkMsg) {
        if (hooks != null && !hooks.isEmpty()) {
            publishPreReasoning(); // If not sent Pre before, send Pre first
            ReasoningChunkEvent chunkEvent =
                    new ReasoningChunkEvent(agent, "A2A", null, chunkMsg, chunkMsg);
            hooks.forEach(hook -> hook.onEvent(chunkEvent).block());
        }
    }

    /**
     * Trigger PostReasoningEvent (triggered only once)
     */
    void publishPostReasoning(Msg finalMsg) {
        if (hooks != null && !hooks.isEmpty() && postReasoningFired.compareAndSet(false, true)) {
            publishPreReasoning();
            PostReasoningEvent postEvent = new PostReasoningEvent(agent, "A2A", null, finalMsg);
            hooks.forEach(hook -> hook.onEvent(postEvent).block());
        }
    }
}
