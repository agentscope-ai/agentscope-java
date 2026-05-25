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
import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Single-file ReAct agent for the AgentScope 2.0 contract.
 *
 * <p>This class is the new home for the full reply / reasoning / acting loop and is
 * intended to coexist with the legacy {@code ReActAgent} while the migration to the
 * 2.0 API surface completes. It composes:
 * <ul>
 *   <li>A {@link ChatModelBase} for LLM calls</li>
 *   <li>A {@link Toolkit} carrying registered tool implementations</li>
 *   <li>A list of {@link MiddlewareBase} hooks wrapping reply / reasoning / acting / model-call /
 *       system-prompt</li>
 *   <li>An {@link AgentState} carrying conversation context, permission context, tool context, and
 *       tasks context</li>
 *   <li>{@link ModelConfig}, {@link ContextConfig}, {@link ReactConfig} for retry / compression /
 *       loop tuning</li>
 *   <li>A {@link PermissionEngine} constructed from {@link AgentState#getPermissionContext()}</li>
 * </ul>
 *
 * <p>Stage 7c only provides the constructor, getters, and stub {@code call} / {@code stream}
 * methods that throw {@link UnsupportedOperationException}. Stages 7d–7f implement the reply
 * loop, context compression, and HITL re-entry respectively.
 */
public final class Agent2 implements Agent {

    private final String agentId;
    private final String name;
    private final String systemPrompt;
    private final ChatModelBase model;
    private final Toolkit toolkit;
    private final List<MiddlewareBase> middlewares;
    private final AgentState state;
    private final ModelConfig modelConfig;
    private final ContextConfig contextConfig;
    private final ReactConfig reactConfig;
    private final PermissionEngine permissionEngine;

    private final AtomicBoolean interruptFlag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userInterruptMessage = new AtomicReference<>(null);

    public Agent2(
            String name,
            String systemPrompt,
            ChatModelBase model,
            Toolkit toolkit,
            List<MiddlewareBase> middlewares,
            AgentState state,
            ModelConfig modelConfig,
            ContextConfig contextConfig,
            ReactConfig reactConfig) {
        this.agentId = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name");
        this.systemPrompt = systemPrompt;
        this.model = Objects.requireNonNull(model, "model");
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
        this.state = Objects.requireNonNull(state, "state");
        this.modelConfig = modelConfig == null ? ModelConfig.defaults() : modelConfig;
        this.contextConfig = contextConfig == null ? ContextConfig.defaults() : contextConfig;
        this.reactConfig = reactConfig == null ? ReactConfig.defaults() : reactConfig;
        this.permissionEngine = new PermissionEngine(state.getPermissionContext());
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ChatModelBase getModel() {
        return model;
    }

    public Toolkit getToolkit() {
        return toolkit;
    }

    public List<MiddlewareBase> getMiddlewares() {
        return middlewares;
    }

    public AgentState getState() {
        return state;
    }

    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    public ContextConfig getContextConfig() {
        return contextConfig;
    }

    public ReactConfig getReactConfig() {
        return reactConfig;
    }

    public PermissionEngine getPermissionEngine() {
        return permissionEngine;
    }

    @Override
    public void interrupt() {
        interruptFlag.set(true);
    }

    @Override
    public void interrupt(Msg msg) {
        interruptFlag.set(true);
        if (msg != null) {
            userInterruptMessage.set(msg);
        }
    }

    /** Visible to Stage 7d for reply-loop interrupt checks. */
    AtomicBoolean interruptFlag() {
        return interruptFlag;
    }

    /** Visible to Stage 7d to surface user-supplied interrupt content. */
    AtomicReference<Msg> userInterruptMessage() {
        return userInterruptMessage;
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return Mono.error(
                new UnsupportedOperationException("Agent2.call: Stage 7d implements reply loop"));
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Agent2.call(structured): Stage 7d implements reply loop"));
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Agent2.call(schema): Stage 7d implements reply loop"));
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return Flux.error(
                new UnsupportedOperationException("Agent2.stream: Stage 7d implements reply loop"));
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent2.stream(structured): Stage 7d implements reply loop"));
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent2.stream(schema): Stage 7d implements reply loop"));
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        if (msg == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> state.contextMutable().add(msg));
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> state.contextMutable().addAll(msgs));
    }
}
