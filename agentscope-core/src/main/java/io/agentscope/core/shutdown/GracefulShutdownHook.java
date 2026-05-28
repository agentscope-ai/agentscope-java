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
package io.agentscope.core.shutdown;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * System hook that integrates graceful shutdown into the agent lifecycle.
 *
 * <p>Request registration and unregistration are handled by {@code AgentBase.call()}
 * via {@code Mono.using} (setup registers, cleanup unregisters), guaranteeing that
 * every registered request is always unregistered regardless of success, error, or cancel.
 *
 * <p>This hook is responsible for:
 * <ul>
 *   <li>{@link PreCallEvent} — deduplicate input if resuming from a shutdown-interrupted session</li>
 * </ul>
 *
 * <p>Shutdown checkpoints — when the system is in SHUTTING_DOWN state, the agent is
 * interrupted at these safe points (after the current phase has fully completed):
 * <ul>
 *   <li>{@link PostReasoningEvent} — all LLM output chunks received</li>
 *   <li>{@link PostActingEvent} — tool execution finished</li>
 *   <li>{@link PostSummaryEvent} — summary generation finished</li>
 * </ul>
 *
 * <p>This means during shutdown, reasoning/acting/summary phases are allowed to complete
 * before the interrupt is issued — output tokens are not wasted. Only when the global
 * shutdown timeout is reached will the agent be force-interrupted mid-phase (handled by
 * GracefulShutdownManager#enforceTimeoutAndInterrupt).
 */
public final class GracefulShutdownHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHook.class);

    private final GracefulShutdownManager manager;

    public GracefulShutdownHook(GracefulShutdownManager manager) {
        this.manager = manager;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof PreCallEvent pre) {
            deduplicateIfResuming(pre);
        }

        // --- Shutdown checkpoints: interrupt agent if system is shutting down ---
        if (event instanceof PostReasoningEvent e && e.getReasoningMessage() != null) {
            manager.interruptIfShuttingDown(e.getAgent());
        } else if (event instanceof PostActingEvent e && e.getToolResultMsg() != null) {
            manager.interruptIfShuttingDown(e.getAgent());
        } else if (event instanceof PostSummaryEvent e && e.getSummaryMessage() != null) {
            manager.interruptIfShuttingDown(e.getAgent());
        }

        return Mono.just(event);
    }

    /**
     * If the agent's session was previously interrupted by shutdown, the client is likely
     * retrying with the same user prompt that already exists in memory. Discard the
     * duplicate input so the agent resumes purely from its saved memory context.
     *
     * <p>If the new input differs from the last user message in memory, it is kept
     * to avoid discarding legitimate new messages.
     */
    private void deduplicateIfResuming(PreCallEvent event) {
        if (manager.checkAndClearShutdownInterrupted(event.getAgent())) {
            List<Msg> newInput = event.getInputMessages();

            // No new input, nothing to deduplicate
            if (newInput == null || newInput.isEmpty()) {
                return;
            }

            // Extract the text of the first incoming message
            String newInputText = newInput.get(0).getTextContent();

            // Find the last USER message in memory
            Memory memory = event.getMemory();
            if (memory == null) {
                event.setInputMessages(List.of());
                return;
            }
            List<Msg> memoryMsgs = memory.getMessages();
            String lastUserText = null;
            for (int i = memoryMsgs.size() - 1; i >= 0; i--) {
                Msg msg = memoryMsgs.get(i);
                if (msg.getRole() == MsgRole.USER) {
                    lastUserText = msg.getTextContent();
                    break;
                }
            }

            // Only discard when content is identical
            if (newInputText != null && newInputText.equals(lastUserText)) {
                log.info(
                        "Detected shutdown-interrupted session for agent {}, "
                                + "discarding duplicate input (content matches last user message)",
                        event.getAgent().getName());
                event.setInputMessages(List.of());
            } else {
                log.info(
                        "Detected shutdown-interrupted session for agent {}, "
                                + "but input differs from last user message, keeping input",
                        event.getAgent().getName());
                // Keep the new input as-is
            }
        }
    }

    @Override
    public int priority() {
        return 0;
    }
}
