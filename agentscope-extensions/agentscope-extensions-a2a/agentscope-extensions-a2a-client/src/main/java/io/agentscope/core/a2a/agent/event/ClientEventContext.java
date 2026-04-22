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
import io.agentscope.core.message.Msg;
import java.util.List;
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
}
