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
package io.agentscope.core.hook;

import reactor.core.publisher.Mono;

/**
 * Hook interface for monitoring and intercepting agent execution.
 *
 * <p>All agent execution events are delivered through a single {@link #onEvent(HookEvent)} method.
 * This unified event model provides a clean, type-safe way to intercept and modify agent behavior.
 *
 * <p><b>Hook Priority:</b> Hooks are executed in priority order (lower value = higher priority).
 * Default priority is 100. Hooks with the same priority execute in registration order.
 *
 * <p><b>Event Modifiability:</b> Whether an event is modifiable is indicated by the presence of
 * setter methods:
 * <ul>
 *   <li>Events with setters (e.g., {@link PreReasoningEvent#setInputMessages}) allow
 *       modification</li>
 *   <li>Events without setters are notification-only</li>
 * </ul>
 *
 * <p><b>Example Usage:</b>
 *
 * <pre>{@code
 * // Basic hook with default priority
 * Hook loggingHook = new Hook() {
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreReasoningEvent e -> {
 *                 System.out.println("Reasoning with model: " + e.getModelName());
 *                 yield Mono.just(e);
 *             }
 *             case ReasoningChunkEvent e -> {
 *                 // Display streaming output
 *                 System.out.print(extractText(e.getIncrementalChunk()));
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 *
 * // High priority hook (executes first)
 * Hook authHook = new Hook() {
 *     @Override
 *     public int priority() {
 *         return 10;  // High priority
 *     }
 *
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreActingEvent e -> {
 *                 // Inject auth token before any other hook
 *                 ToolUseBlock toolUse = e.getToolUse();
 *                 // ... add auth
 *                 e.setToolUse(toolUse);
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 *
 * // Modifying events
 * Hook hintInjector = new Hook() {
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreReasoningEvent e -> {
 *                 // Modify messages before LLM reasoning
 *                 List<Msg> msgs = new ArrayList<>(e.getInputMessages());
 *                 msgs.add(0, Msg.builder()
 *                         .role(MsgRole.SYSTEM)
 *                         .content(new TextBlock("Think step by step"))
 *                         .build());
 *                 e.setInputMessages(msgs);
 *                 yield Mono.just(e);
 *             }
 *             case PostActingEvent e -> {
 *                 // Modify tool result
 *                 ToolResultBlock result = e.getToolResult();
 *                 // ... process result
 *                 e.setToolResult(result);
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 * }</pre>
 *
 * @see HookEvent
 * @see HookEventType
 */
public interface Hook {

    /**
     * Handle a hook event.
     *
     * <p>This method is called for all agent execution events. Use pattern matching to handle
     * specific event types.
     *
     * <p><b>Modifiable Events:</b> For events with setters, you can modify the context and the
     * changes will affect agent execution:
     * <ul>
     *   <li>{@link PreReasoningEvent} - Modify messages before LLM reasoning</li>
     *   <li>{@link PostReasoningEvent} - Modify reasoning results</li>
     *   <li>{@link PreActingEvent} - Modify tool parameters before execution</li>
     *   <li>{@link PostActingEvent} - Modify tool results</li>
     *   <li>{@link PostCallEvent} - Modify final agent response</li>
     * </ul>
     *
     * <p><b>Notification Events:</b> Events without setters are read-only:
     * <ul>
     *   <li>{@link PreCallEvent} - Notified when agent starts</li>
     *   <li>{@link ReasoningChunkEvent} - Streaming reasoning chunks</li>
     *   <li>{@link ActingChunkEvent} - Streaming tool execution chunks</li>
     *   <li>{@link ErrorEvent} - Errors during execution</li>
     * </ul>
     *
     * @param event The hook event
     * @param <T> The concrete event type
     * @return Mono containing the potentially modified event
     */
    <T extends HookEvent> Mono<T> onEvent(T event);

    /**
     * The priority of this hook (lower value = higher priority).
     *
     * <p>Hooks are executed in ascending priority order. Hooks with the same priority execute in
     * their registration order.
     *
     * <p><b>Common Priority Ranges:</b>
     * <ul>
     *   <li>0-50: Critical system hooks (auth, security)</li>
     *   <li>51-100: High priority hooks (validation, preprocessing)</li>
     *   <li>101-500: Normal priority hooks (business logic)</li>
     *   <li>501-1000: Low priority hooks (logging, metrics)</li>
     * </ul>
     *
     * @return The priority value (default: 100)
     */
    default int priority() {
        return 100;
    }
}
