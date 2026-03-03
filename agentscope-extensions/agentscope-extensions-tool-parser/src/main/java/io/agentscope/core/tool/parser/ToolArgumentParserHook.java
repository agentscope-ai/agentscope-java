/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook for automatically correcting tool call arguments before execution.
 *
 * <p>This hook intercepts {@link PreActingEvent} (fired before tool execution) and applies
 * a multi-stage cleanup strategy to fix common JSON format issues in LLM-generated tool
 * arguments:
 *
 * <ol>
 *   <li>Remove Markdown code blocks (triple-backtick json ... triple-backtick)
 *   <li>Strip JavaScript-style comments (double-slash and slash-star ... star-slash)
 *   <li>Convert single quotes to double quotes (via Jackson lenient mode)
 *   <li>Fix missing brackets and trailing commas
 *   <li>Fallback to original input if all stages fail
 * </ol>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create hook with SMART mode (default)
 * ToolArgumentParserHook hook = new ToolArgumentParserHook();
 *
 * // Or specify mode explicitly
 * ToolArgumentParserHook strictHook = new ToolArgumentParserHook(HookMode.STRICT);
 *
 * // Register with agent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .hooks(List.of(hook))
 *     .build();
 * }</pre>
 *
 *
 * <p><b>Priority:</b> This hook has default priority 100 (normal priority). Hooks with lower
 * priority values execute first.
 *
 * @see Hook
 * @see ToolArgumentParser
 * @since 1.0.10
 */
public class ToolArgumentParserHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolArgumentParserHook.class);

    /**
     * ObjectMapper for converting Map to JSON string.
     * Uses standard settings (not lenient) to avoid double-processing.
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Handles hook events with tool argument correction.
     *
     * <p>This method only processes {@link PreActingEvent} (before tool execution).
     * All other event types are passed through unchanged.
     *
     * @param event The hook event
     * @param <T>   The concrete event type
     * @return Mono containing the potentially modified event
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        // Only process PreActingEvent
        if (!(event instanceof PreActingEvent preActingEvent)) {
            return Mono.just(event);
        }

        // Process PreActingEvent
        return Mono.fromCallable(() -> processPreActingEvent(preActingEvent))
                .onErrorResume(
                        ex -> {
                            log.error(
                                    "Hook execution failed for event type: {}, tool: {}，"
                                            + "Returning original event. Error: {}",
                                    event.getClass().getSimpleName(),
                                    preActingEvent.getToolUse().getName(),
                                    ex.getMessage(),
                                    ex);
                            return Mono.just(preActingEvent); // Return original event on error
                        })
                .map(processedEvent -> (T) processedEvent);
    }

    /**
     * Processes PreActingEvent to correct tool arguments.
     *
     * @param event The PreActingEvent
     * @return The processed event (potentially modified)
     */
    @SuppressWarnings("unchecked")
    private PreActingEvent processPreActingEvent(PreActingEvent event) {
        ToolUseBlock toolUse = event.getToolUse();
        String toolName = toolUse.getName();
        // the raw content for streaming tool calls
        String rawContent = toolUse.getContent();

        // Validate input before processing
        if (rawContent == null) {
            return event;
        }

        ParseResult result = ToolArgumentParser.parse(rawContent, toolName);

        if (!result.isSuccess()) {
            // Parsing failed, log and return original
            log.error(
                    "Tool argument parsing failed for tool '{}': "
                            + "stage={}, error={}, jsonLength={}, mode=SMART",
                    toolName,
                    result.stage(),
                    result.errorMessage(),
                    rawContent.length());
            return event;
        }

        // Check if correction was applied
        if (result.isDirectSuccess()) {
            // No correction needed
            return event;
        }

        // Correction was applied, log it
        log.info("Tool argument corrected for tool '{}': stage={}", toolName, result.stage());

        // Update ToolUseBlock with corrected arguments
        String correctedContent = result.parsedArguments();

        ToolUseBlock correctedToolUse =
                ToolUseBlock.builder()
                        .id(toolUse.getId())
                        .name(toolUse.getName())
                        .input(toolUse.getInput())
                        .content(correctedContent)
                        .metadata(toolUse.getMetadata())
                        .build();

        // Update event with corrected ToolUseBlock
        event.setToolUse(correctedToolUse);
        return event;
    }

    /**
     * Returns the priority of this hook (100 = normal priority).
     *
     * <p>Lower values execute first. Hooks with the same priority execute in registration order.
     *
     * @return The priority value (default: 100)
     */
    @Override
    public int priority() {
        return 100; // Normal priority, executes after high-priority hooks
    }
}
