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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook for injecting pending sub-agent tool results during sub-agent execution.
 *
 * <p>This hook works in conjunction with {@link SubAgentContext} to enable resuming
 * suspended sub-agent executions. When a sub-agent is suspended and then resumed,
 * this hook intercepts the tool call and injects the previously pending results.
 *
 * <p>The hook operates during the PreActingEvent phase:
 * <ul>
 *   <li>Checks if the called tool has pending results in the context</li>
 *   <li>Injects pending results into the tool use block's metadata</li>
 *   <li>Updates the tool input with the session_id from the pending context</li>
 * </ul>
 *
 * <p>Result injection mechanism:
 * <ul>
 *   <li>Pending results are stored in metadata under the key {@link #PREVIOUS_TOOL_RESULT}</li>
 *   <li>The session_id is added to the tool input for proper context tracking</li>
 *   <li>After injection, pending results are cleared from the context</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * SubAgentContext context = new SubAgentContext();
 * SubAgentHook hook = new SubAgentHook(context);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("MainAgent")
 *     .hook(hook)
 *     .build();
 * }</pre>
 */
public class SubAgentHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentHook.class);

    private final SubAgentContext context;

    public static final String PREVIOUS_TOOL_RESULT = "previous_tool_result";

    /**
     * Creates a new SubAgentHook with the given context.
     *
     * @param context The SubAgentContext for managing pending results
     */
    public SubAgentHook(SubAgentContext context) {
        this.context = context;
    }

    /**
     * Gets the SubAgentContext associated with this hook.
     *
     * @return The SubAgentContext
     */
    public SubAgentContext getContext() {
        return context;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActingEvent) {
            return handlePreActing(preActingEvent).map(e -> (T) e);
        }
        return Mono.just(event);
    }

    /**
     * Handles PreActingEvent to inject pending sub-agent results
     *
     * <p>This method checks whether the called tool has any pending results in the context.
     * If pending results exist, it injects them into the tool use block's metadata
     * and updates the tool input with the session_id from the pending context.
     *
     * <p>Injection process:
     * <ul>
     *   <li>Retrieves pending context by tool ID from {@link SubAgentContext}</li>
     *   <li>Stores pending results in metadata under {@link #PREVIOUS_TOOL_RESULT}</li>
     *   <li>Updates tool input with session_id for context tracking</li>
     *   <li>Clears the pending result from context after injection</li>
     * </ul>
     *
     * @param event PreActingEvent
     * @return A Mono containing the possibly modified event
     */
    private Mono<PreActingEvent> handlePreActing(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null) {
            return Mono.just(event);
        }

        // Extract session_id from tool input
        Map<String, Object> input = toolUse.getInput();
        if (input == null) {
            return Mono.just(event);
        }

        // Check if there's a pending result for this session
        Optional<SubAgentPendingContext> pendingContext =
                context.consumePendingResult(toolUse.getId());
        Optional<List<ToolResultBlock>> pendingResult =
                pendingContext.map(SubAgentPendingContext::pendingResults);
        if (pendingResult.isEmpty()) {
            return Mono.just(event);
        }

        // Inject the result into tool use input (create new mutable maps)
        Map<String, Object> metadata = new HashMap<>(toolUse.getMetadata());
        metadata.put(PREVIOUS_TOOL_RESULT, pendingResult.get());
        Map<String, Object> newInput = new HashMap<>(toolUse.getInput());
        newInput.put("session_id", pendingContext.get().sessionId());

        ToolUseBlock modifiedToolUse =
                ToolUseBlock.builder()
                        .id(toolUse.getId())
                        .name(toolUse.getName())
                        .input(newInput)
                        .content(toolUse.getContent())
                        .metadata(metadata)
                        .build();

        event.setToolUse(modifiedToolUse);

        return Mono.just(event);
    }

    @Override
    public int priority() {
        // High priority to ensure result injection happens before tool execution
        return 10;
    }
}
