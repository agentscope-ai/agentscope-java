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
package io.agentscope.harness.agent.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that triggers memory flush and message offload at the end of each agent call.
 *
 * <p>Fires on {@link PostCallEvent} to ensure long-term memories are extracted and
 * persisted after every call, even when conversation compaction was not triggered during
 * that call. When CompactionHook is active, it handles flush/offload for the messages it summarizes;
 * this hook covers the remaining tail of messages that were kept verbatim.
 *
 * <p>Priority is 5 — runs early so state is persisted before the session-persistence hook
 * (priority 900) saves the overall agent state.
 */
public class MemoryFlushHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(MemoryFlushHook.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private RuntimeContext runtimeContext;

    public MemoryFlushHook(WorkspaceManager workspaceManager, Model model) {
        this.workspaceManager = workspaceManager;
        this.model = model;
    }

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent) {
            return doFlush(event.getAgent()).thenReturn(event);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 5;
    }

    private Mono<Void> doFlush(Agent agent) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return Mono.empty();
        }

        Memory memory = reActAgent.getMemory();
        List<Msg> messages = memory.getMessages();
        if (messages.isEmpty()) {
            return Mono.empty();
        }

        MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);

        Mono<Void> flushMono =
                flushManager
                        .flushMemories(messages)
                        .doOnSuccess(v -> log.debug("Memory flush completed"))
                        .onErrorResume(
                                e -> {
                                    log.warn("Memory flush failed: {}", e.getMessage());
                                    return Mono.empty();
                                });

        String agentId = agent.getName();
        String sessionId =
                runtimeContext != null && runtimeContext.getSessionId() != null
                        ? runtimeContext.getSessionId()
                        : "default";

        Mono<Void> offloadMono =
                Mono.fromRunnable(() -> flushManager.offloadMessages(messages, agentId, sessionId))
                        .then()
                        .doOnSuccess(v -> log.debug("Message offload completed"))
                        .onErrorResume(
                                e -> {
                                    log.warn("Message offload failed: {}", e.getMessage());
                                    return Mono.empty();
                                });

        return flushMono.then(offloadMono);
    }
}
