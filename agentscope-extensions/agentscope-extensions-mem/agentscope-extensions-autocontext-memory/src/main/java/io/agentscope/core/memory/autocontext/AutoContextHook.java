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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Hook that wires auto context compression into ReActAgent. */
@SuppressWarnings("deprecation")
public class AutoContextHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AutoContextHook.class);

    private final AutoContextConfig config;
    private final io.agentscope.core.model.Model model;
    private final String hookId;
    private final ConcurrentHashMap<String, AutoContextMemory> memories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReActAgent, Boolean> registeredAgents =
            new ConcurrentHashMap<>();
    private final AtomicBoolean registrationFailed = new AtomicBoolean(false);

    public AutoContextHook() {
        this(AutoContextConfig.builder().build(), null);
    }

    public AutoContextHook(AutoContextConfig config, io.agentscope.core.model.Model model) {
        this.config = Objects.requireNonNull(config, "config");
        this.model = model;
        this.hookId = java.util.UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> mono = (Mono<T>) handlePreCall(preCallEvent);
            return mono;
        }
        if (event instanceof PreReasoningEvent preReasoningEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> mono = (Mono<T>) handlePreReasoning(preReasoningEvent);
            return mono;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public List<Object> tools() {
        return List.of();
    }

    Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        Agent agent = event.getAgent();
        if (!(agent instanceof ReActAgent reactAgent)) {
            return Mono.just(event);
        }
        if (registeredAgents.putIfAbsent(reactAgent, Boolean.TRUE) != null) {
            return Mono.just(event);
        }
        try {
            Toolkit toolkit = reactAgent.getToolkit();
            if (toolkit != null && !toolkit.getToolNames().contains("context_reload")) {
                toolkit.registerTool(new ContextOffloadTool(this));
            }
        } catch (Exception e) {
            registrationFailed.set(true);
            log.warn(
                    "Failed to register context_reload tool for agent {}", reactAgent.getName(), e);
        }
        return Mono.just(event);
    }

    Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent event) {
        Agent agent = event.getAgent();
        if (!(agent instanceof ReActAgent reactAgent)) {
            return Mono.just(event);
        }
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext runtimeContext = reactAgent.getRuntimeContext();
                            AgentState state =
                                    RuntimeContext.resolveAgentState(runtimeContext, reactAgent);
                            if (state == null) {
                                return event;
                            }

                            AutoContextMemory memory = memoryFor(reactAgent, runtimeContext);
                            memory.mergeWithContext(state.getContext());
                            boolean compressed = memory.compressIfNeeded();
                            if (compressed) {
                                state.contextMutable().clear();
                                state.contextMutable().addAll(memory.getMessages());
                            }

                            event.setInputMessages(new ArrayList<>(memory.getMessages()));
                            event.appendSystemContent(
                                    "You may see compressed messages containing <!--"
                                        + " CONTEXT_OFFLOAD: uuid=... -->.\n"
                                        + "- Use the UUID to call context_reload if you need full"
                                        + " details.\n"
                                        + "- Never mention UUIDs, offload tags, or internal"
                                        + " metadata in your response.");

                            persistMemory(reactAgent, runtimeContext, memory);
                            return event;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    List<Msg> reload(String uuid, Agent agent, RuntimeContext runtimeContext) {
        AutoContextMemory memory = memoryFor(agent, runtimeContext);
        return memory.reload(uuid);
    }

    AutoContextMemory memoryFor(Agent agent, RuntimeContext runtimeContext) {
        String key = memoryKey(agent, runtimeContext);
        return memories.computeIfAbsent(
                key,
                ignored -> {
                    AutoContextMemory memory = new AutoContextMemory(config, model);
                    AgentState state = RuntimeContext.resolveAgentState(runtimeContext, agent);
                    if (agent instanceof ReActAgent reactAgent) {
                        AgentStateStore store = reactAgent.getStateStore();
                        String userId = runtimeContext != null ? runtimeContext.getUserId() : null;
                        String sessionId =
                                runtimeContext != null ? runtimeContext.getSessionId() : null;
                        if (store != null && sessionId != null && !sessionId.isBlank()) {
                            memory.loadFrom(store, userId, sessionId, stateKey(agent));
                        }
                    }
                    if (state != null) {
                        memory.mergeWithContext(state.getContext());
                    }
                    return memory;
                });
    }

    private void persistMemory(
            ReActAgent agent, RuntimeContext runtimeContext, AutoContextMemory memory) {
        AgentStateStore store = agent.getStateStore();
        if (store == null || runtimeContext == null || runtimeContext.getSessionId() == null) {
            return;
        }
        memory.saveTo(
                store, runtimeContext.getUserId(), runtimeContext.getSessionId(), stateKey(agent));
    }

    private String memoryKey(Agent agent, RuntimeContext runtimeContext) {
        return stateKey(agent)
                + "|"
                + (runtimeContext != null ? safe(runtimeContext.getUserId()) : "__anon__")
                + "|"
                + (runtimeContext != null ? safe(runtimeContext.getSessionId()) : "__default__");
    }

    private String stateKey(Agent agent) {
        return "autocontext_" + hookId + "_" + Integer.toHexString(System.identityHashCode(agent));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "__anon__" : value;
    }
}
