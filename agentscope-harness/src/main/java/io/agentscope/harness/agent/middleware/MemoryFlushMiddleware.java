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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that triggers memory flush and message offload at the end of each agent call.
 *
 * <p>Runs in {@link #onAgent}'s {@code doOnSuccess}-equivalent (via the {@code Flux}
 * completion signal) so long-term memories are extracted and persisted after every call,
 * even when conversation compaction was not triggered during that call. When
 * {@link CompactionMiddleware} is active, it handles flush/offload for the messages it
 * summarizes; this middleware covers the remaining tail of messages that were kept
 * verbatim.
 */
public class MemoryFlushMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;

    public MemoryFlushMiddleware(WorkspaceManager workspaceManager, Model model) {
        this.workspaceManager = workspaceManager;
        this.model = model;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        RuntimeContext runtimeContext = agent != null ? agent.getRuntimeContext() : null;
        final RuntimeContext rc = runtimeContext != null ? runtimeContext : RuntimeContext.empty();
        return next.apply(input).doOnComplete(() -> doFlush(agent, rc).subscribe());
    }

    private reactor.core.publisher.Mono<Void> doFlush(Agent agent, RuntimeContext rc) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return reactor.core.publisher.Mono.empty();
        }
        AgentState state = reActAgent.getAgentState();
        if (state == null) {
            return reactor.core.publisher.Mono.empty();
        }
        List<Msg> messages = state.getContext();
        if (messages.isEmpty()) {
            return reactor.core.publisher.Mono.empty();
        }

        MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);

        reactor.core.publisher.Mono<Void> flushMono =
                flushManager
                        .flushMemories(rc, messages)
                        .doOnSuccess(v -> log.debug("Memory flush completed"))
                        .onErrorResume(
                                e -> {
                                    log.warn("Memory flush failed: {}", e.getMessage());
                                    return reactor.core.publisher.Mono.empty();
                                });

        String agentId = agent.getName();
        String sessionId = rc != null && rc.getSessionId() != null ? rc.getSessionId() : "default";

        reactor.core.publisher.Mono<Void> offloadMono =
                reactor.core.publisher.Mono.fromRunnable(
                                () ->
                                        flushManager.offloadMessages(
                                                rc, messages, agentId, sessionId))
                        .then()
                        .doOnSuccess(v -> log.debug("Message offload completed"))
                        .onErrorResume(
                                e -> {
                                    log.warn("Message offload failed: {}", e.getMessage());
                                    return reactor.core.publisher.Mono.empty();
                                });

        return flushMono.then(offloadMono);
    }
}
