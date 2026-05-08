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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that performs conversation compaction before each LLM reasoning call.
 *
 * <p>Fires on {@link PreReasoningEvent}. When the compaction threshold is exceeded:
 * <ol>
 *   <li>Long-term memories are flushed from the prefix via {@link MemoryFlushManager}.</li>
 *   <li>The full conversation is offloaded to the session JSONL.</li>
 *   <li>The prefix is distilled into a structured summary via one LLM call.</li>
 *   <li>The agent's working {@link Memory} is replaced with
 *       {@code [summaryMsg] + preservedTail}.</li>
 *   <li>{@link PreReasoningEvent#setInputMessages} is updated to the compacted conversation
 *       ({@code [summaryMsg] + preservedTail}). The system message is managed separately in
 *       {@link PreReasoningEvent#getSystemMessage()} and prepended by {@code ReActAgent}
 *       just before {@code model.stream()}, so this hook no longer splits or re-merges
 *       SYSTEM messages.</li>
 * </ol>
 *
 * <p>This hook runs at priority 10 — before
 * {@link io.agentscope.harness.agent.hook.WorkspaceContextHook} (priority 900): compaction
 * runs on the conversation first; the workspace context is then appended to the system message
 * by {@code WorkspaceContextHook} on the same {@link PreReasoningEvent} chain.
 *
 * <p>{@link RuntimeContext} is bound on each call by {@link io.agentscope.core.ReActAgent}.
 */
public class CompactionHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(CompactionHook.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;

    private RuntimeContext runtimeContext;

    public CompactionHook(WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
    }

    @Override
    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent pre) {
            // Must emit a value: Reactor's thenReturn() would not run if the source completed
            // "empty" (e.g. Mono.empty() from flatMap), which would drop all later hooks.
            return (Mono<T>) (Mono<?>) handlePreReasoning(pre);
        }
        return Mono.just(event);
    }

    // -------------------------------------------------------------------------
    // Core compaction flow
    // -------------------------------------------------------------------------

    private Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent event) {
        if (!(event.getAgent() instanceof ReActAgent reActAgent)) {
            return Mono.just(event);
        }

        // inputMessages contains only conversation messages — SYSTEM is managed separately
        // via event.getSystemMessage() / event.setSystemMessage()
        List<Msg> conversationMsgs = event.getInputMessages();

        String agentId = event.getAgent().getName();
        String sessionId = sessionId();

        MemoryFlushManager flushManager = buildFlushManager();
        ConversationCompactor compactor = new ConversationCompactor(model, flushManager);

        return compactor
                .compactIfNeeded(conversationMsgs, config, agentId, sessionId)
                .flatMap(
                        optResult -> {
                            if (optResult.isEmpty()) {
                                return Mono.just(event);
                            }
                            List<Msg> compacted = optResult.get();
                            applyToMemory(reActAgent.getMemory(), compacted);
                            event.setInputMessages(compacted);
                            log.debug(
                                    "Updated PreReasoningEvent to {} compacted messages",
                                    compacted.size());
                            return Mono.just(event);
                        })
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Compaction failed, continuing without compaction: {}",
                                    e.getMessage());
                            return Mono.just(event);
                        });
    }

    /**
     * Replaces the agent's working memory with the compacted message list.
     *
     * <p>Uses {@link Memory#clear()} + {@link Memory#addMessage(Msg)} to synchronise the
     * in-memory state so subsequent reasoning rounds start from the compacted baseline.
     */
    private static void applyToMemory(Memory memory, List<Msg> compacted) {
        try {
            memory.clear();
            for (Msg msg : compacted) {
                memory.addMessage(msg);
            }
            log.debug("Applied compacted messages to memory ({} messages)", compacted.size());
        } catch (Exception e) {
            log.warn("Failed to apply compacted messages to memory: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MemoryFlushManager buildFlushManager() {
        return new MemoryFlushManager(workspaceManager, model);
    }

    private String sessionId() {
        RuntimeContext ctx = this.runtimeContext;
        if (ctx != null && ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return "default";
    }
}
