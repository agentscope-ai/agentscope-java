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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.MemoryIndex;
import io.agentscope.harness.agent.memory.MemoryMaintenanceScheduler;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
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
 *   <li>{@link PreReasoningEvent#setInputMessages} is updated so the LLM sees the
 *       compacted view: {@code systemMsgs + [summaryMsg] + preservedTail}.</li>
 * </ol>
 *
 * <p>This hook runs at priority 10 — before {@link io.agentscope.harness.agent.hook.WorkspaceContextHook}
 * (priority 900): compaction runs on the conversation portion first; workspace files are merged into
 * the system message afterwards on the same {@link PreReasoningEvent} chain.
 *
 * <p>{@link RuntimeContext} must be injected via {@link #setRuntimeContext} before the hook fires.
 */
public class CompactionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(CompactionHook.class);

    private final WorkspaceManager workspaceManager;
    private final Model model;
    private final CompactionConfig config;

    private RuntimeContext runtimeContext;
    private volatile MemoryIndex memoryIndex;
    private volatile MemoryMaintenanceScheduler maintenanceScheduler;

    public CompactionHook(WorkspaceManager workspaceManager, Model model, CompactionConfig config) {
        this.workspaceManager = workspaceManager;
        this.model = model;
        this.config = config;
    }

    public void setRuntimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    public void setMemoryIndex(MemoryIndex memoryIndex) {
        this.memoryIndex = memoryIndex;
    }

    /** Wires the maintenance scheduler so flushes can opportunistically consolidate MEMORY.md. */
    public void setMaintenanceScheduler(MemoryMaintenanceScheduler scheduler) {
        this.maintenanceScheduler = scheduler;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent pre) {
            return handlePreReasoning(pre).thenReturn(event);
        }
        return Mono.just(event);
    }

    // -------------------------------------------------------------------------
    // Core compaction flow
    // -------------------------------------------------------------------------

    private Mono<Void> handlePreReasoning(PreReasoningEvent event) {
        if (!(event.getAgent() instanceof ReActAgent reActAgent)) {
            return Mono.empty();
        }

        // Separate system messages (injected by WorkspaceContextHook etc.) from conversation
        List<Msg> inputMessages = event.getInputMessages();
        List<Msg> systemMsgs = new ArrayList<>();
        List<Msg> conversationMsgs = new ArrayList<>();
        for (Msg m : inputMessages) {
            if (m.getRole() == MsgRole.SYSTEM) {
                systemMsgs.add(m);
            } else {
                conversationMsgs.add(m);
            }
        }

        String agentId = event.getAgent().getName();
        String sessionId = sessionId();

        MemoryFlushManager flushManager = buildFlushManager();
        ConversationCompactor compactor = new ConversationCompactor(model, flushManager);

        return compactor
                .compactIfNeeded(conversationMsgs, config, agentId, sessionId)
                .<Void>flatMap(
                        optResult -> {
                            if (optResult.isEmpty()) {
                                return Mono.empty();
                            }
                            List<Msg> compacted = optResult.get();
                            applyToMemory(reActAgent.getMemory(), compacted);
                            applyToEvent(event, systemMsgs, compacted);
                            return Mono.empty();
                        })
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Compaction failed, continuing without compaction: {}",
                                    e.getMessage());
                            return Mono.empty();
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

    /**
     * Updates the event's input message list so the LLM sees the compacted view.
     *
     * <p>System messages are always placed at the front, followed by the compacted
     * conversation (summary + preserved tail).
     */
    private static void applyToEvent(
            PreReasoningEvent event, List<Msg> systemMsgs, List<Msg> compacted) {
        List<Msg> rebuilt = new ArrayList<>(systemMsgs.size() + compacted.size());
        rebuilt.addAll(systemMsgs);
        rebuilt.addAll(compacted);
        event.setInputMessages(rebuilt);
        log.debug(
                "Updated PreReasoningEvent: {} system + {} conversation = {} total messages",
                systemMsgs.size(),
                compacted.size(),
                rebuilt.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MemoryFlushManager buildFlushManager() {
        MemoryFlushManager fm = new MemoryFlushManager(workspaceManager, model);
        fm.setMemoryIndex(memoryIndex);
        fm.setMaintenanceScheduler(maintenanceScheduler);
        return fm;
    }

    private String sessionId() {
        RuntimeContext ctx = this.runtimeContext;
        if (ctx != null && ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return "default";
    }
}
