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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook for automatically registering AutoContextMemory integration with ReActAgent.
 *
 * <p>This hook automatically performs the following setup when a ReActAgent with
 * AutoContextMemory is first called:
 * <ol>
 *   <li>Registers {@link ContextOffloadTool} to the agent's toolkit</li>
 *   <li>Attaches the agent's PlanNotebook to AutoContextMemory (if available)</li>
 * </ol>
 *
 * <p>Additionally, this hook handles {@link PreReasoningEvent} to trigger memory
 * compression before LLM reasoning. This ensures compression happens at a
 * deterministic point in the execution flow, and the compressed messages are
 * used for reasoning.
 *
 * <p>This hook ensures that AutoContextMemory is properly integrated with the agent
 * without requiring manual setup steps. It uses an internal flag to ensure setup
 * is only performed once, even if the hook is called multiple times.
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * AutoContextMemory memory = new AutoContextMemory(autoContextConfig, model);
 *
 * AutoContextHook hook = new AutoContextHook();
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .memory(memory)
 *     .hook(hook)  // Register the hook
 *     .enablePlan()
 *     .toolkit(toolkit)
 *     .build();
 *
 * // The hook will automatically:
 * // 1. Register ContextOffloadTool when agent is first called
 * // 2. Attach PlanNotebook to AutoContextMemory if available
 * // 3. Trigger compression before each LLM reasoning call
 * }</pre>
 *
 * <p><b>Priority:</b> This hook has high priority (50) to ensure setup occurs early
 * in the event chain, before other hooks process the event.
 *
 * @see AutoContextMemory
 * @see ContextOffloadTool
 */
public class AutoContextHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AutoContextHook.class);

    private final AtomicBoolean registered = new AtomicBoolean(false);

    /**
     * Creates a new AutoContextHook.
     *
     * <p>The hook will automatically detect AutoContextMemory from the agent's memory
     * when processing PreCallEvent.
     */
    public AutoContextHook() {
        // No parameters needed - memory is obtained from the event
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
            return result;
        }
        if (event instanceof PreReasoningEvent preReasoningEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreReasoning(preReasoningEvent);
            return result;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // High priority to execute early in the hook chain
        return 0;
    }

    /**
     * Handles PreCallEvent by registering AutoContextMemory integration.
     *
     * <p>This method checks if the agent is a ReActAgent and if its memory is an
     * AutoContextMemory instance. If so, and if this is the first time processing,
     * it:
     * <ol>
     *   <li>Registers ContextOffloadTool to the agent's toolkit</li>
     *   <li>Attaches the agent's PlanNotebook to AutoContextMemory (if available)</li>
     * </ol>
     *
     * @param event the PreCallEvent
     * @return Mono containing the unmodified event
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        // Check if we've already registered
        if (registered.get()) {
            return Mono.just(event);
        }

        Agent agent = event.getAgent();

        // Only process ReActAgent instances
        if (!(agent instanceof ReActAgent reActAgent)) {
            return Mono.just(event);
        }

        // Get memory from agent and verify it's an AutoContextMemory instance
        Memory memory = reActAgent.getMemory();
        if (!(memory instanceof AutoContextMemory autoContextMemory)) {
            return Mono.just(event);
        }

        // Try to set the flag atomically (only one thread will succeed)
        if (!registered.compareAndSet(false, true)) {
            // Another thread already registered
            return Mono.just(event);
        }

        try {
            // Register ContextOffloadTool
            Toolkit toolkit = reActAgent.getToolkit();
            if (toolkit != null) {
                ContextOffloadTool contextOffloadTool = new ContextOffloadTool(autoContextMemory);
                toolkit.registerTool(contextOffloadTool);
                log.debug(
                        "ContextOffloadTool registered for agent: {}",
                        agent.getClass().getSimpleName());
            } else {
                log.warn("Toolkit is null, cannot register ContextOffloadTool");
            }

            // Attach PlanNotebook if available
            PlanNotebook planNotebook = reActAgent.getPlanNotebook();
            if (planNotebook != null) {
                autoContextMemory.attachPlanNote(planNotebook);
                log.debug(
                        "PlanNotebook attached to AutoContextMemory for agent: {}",
                        agent.getClass().getSimpleName());
            } else {
                log.debug(
                        "No PlanNotebook available for agent: {}",
                        agent.getClass().getSimpleName());
            }

            log.info(
                    "AutoContextMemory integration completed for agent: {}",
                    agent.getClass().getSimpleName());
        } catch (Exception e) {
            // Log error but don't interrupt the flow
            log.error(
                    "Failed to register AutoContextMemory integration for agent: {}",
                    agent.getClass().getSimpleName(),
                    e);
            // Reset flag so we can retry on next call
            registered.set(false);
        }

        return Mono.just(event);
    }

    /**
     * Handles PreReasoningEvent by triggering compression if needed and updating input messages.
     *
     * <p>This method checks if the agent's memory is an AutoContextMemory instance and
     * triggers compression before LLM reasoning. After compression, it updates the
     * input messages in the event to reflect the compressed working memory.
     *
     * <p>This ensures compression happens at a deterministic point (before reasoning)
     * and the LLM receives the compressed context.
     *
     * @param event the PreReasoningEvent
     * @return Mono containing the potentially modified event
     */
    private Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent event) {
        Agent agent = event.getAgent();

        // Only process ReActAgent instances
        if (!(agent instanceof ReActAgent reActAgent)) {
            return Mono.just(event);
        }

        // Get memory from agent and verify it's an AutoContextMemory instance
        Memory memory = reActAgent.getMemory();
        if (!(memory instanceof AutoContextMemory autoContextMemory)) {
            return Mono.just(event);
        }

        // Trigger compression if needed (this modifies workingMemoryStorage in place)
        autoContextMemory.compressIfNeeded();

        // Always append system prompt instruction about compressed messages
        List<Msg> originalInputMessages = event.getInputMessages();
        List<Msg> newInputMessages = new ArrayList<>();

        if (!originalInputMessages.isEmpty()
                && originalInputMessages.get(0).getRole() == MsgRole.SYSTEM) {
            // Append instruction to existing system prompt
            Msg originalSystemMsg = originalInputMessages.get(0);
            String originalSystemText = originalSystemMsg.getTextContent();
            String appendedInstruction =
                    "\n\n"
                            + "You may see compressed messages containing <!-- CONTEXT_OFFLOAD"
                            + " uuid=... -->.\n"
                            + "- Use the UUID to call context_reload if you need full details.\n"
                            + "- NEVER mention, quote, or refer to UUIDs, offload tags, or internal"
                            + " metadata in your response.";

            String newSystemText =
                    originalSystemText != null
                            ? originalSystemText + appendedInstruction
                            : appendedInstruction.trim();

            Msg updatedSystemMsg =
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .name(originalSystemMsg.getName())
                            .content(TextBlock.builder().text(newSystemText).build())
                            .metadata(originalSystemMsg.getMetadata())
                            .build();

            newInputMessages.add(updatedSystemMsg);
        } else {
            // No system message exists, create a new one with the instruction
            String instruction =
                    "You may see compressed messages containing <!-- CONTEXT_OFFLOAD uuid=..."
                            + " -->.\n"
                            + "- Use the UUID to call context_reload if you need full details.\n"
                            + "- NEVER mention, quote, or refer to UUIDs, offload tags, or internal"
                            + " metadata in your response.";

            newInputMessages.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .name("system")
                            .content(TextBlock.builder().text(instruction).build())
                            .build());
        }

        // Add memory messages (compressed or not)
        newInputMessages.addAll(autoContextMemory.getMessages());
        event.setInputMessages(newInputMessages);

        return Mono.just(event);
    }
}
