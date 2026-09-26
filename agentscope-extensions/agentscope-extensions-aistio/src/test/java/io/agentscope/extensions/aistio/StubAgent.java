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
package io.agentscope.extensions.aistio;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Minimal {@link Agent} standing in for a user's agent. Only the parts the adapter reads — id,
 * name, state, toolkit and interrupt — carry behaviour.
 */
public class StubAgent implements Agent {

    private final String agentId;
    private final AtomicInteger interrupts = new AtomicInteger();

    private volatile AgentState state;
    private volatile Toolkit toolkit;

    public StubAgent(String agentId, AgentState state) {
        this.agentId = agentId;
        this.state = state;
    }

    public int interruptCount() {
        return interrupts.get();
    }

    public void setState(AgentState state) {
        this.state = state;
    }

    public void setToolkit(Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getName() {
        return agentId;
    }

    @Override
    public AgentState getAgentState() {
        return state;
    }

    @Override
    public Toolkit getToolkit() {
        return toolkit;
    }

    @Override
    public void interrupt() {
        interrupts.incrementAndGet();
    }

    @Override
    public void interrupt(Msg msg) {
        interrupts.incrementAndGet();
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return Mono.empty();
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return Mono.empty();
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return Mono.empty();
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return Flux.empty();
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return Flux.empty();
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return Flux.empty();
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return Mono.empty();
    }
}
