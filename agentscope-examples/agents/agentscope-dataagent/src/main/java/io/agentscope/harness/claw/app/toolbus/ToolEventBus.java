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
package io.agentscope.harness.claw.app.toolbus;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * Pluggable bus for tool-call events emitted by HarnessAgent hooks. Publishers call {@link
 * #publish(ToolEvent)} from a hook when a tool call is about to execute; consumers subscribe via
 * {@link #subscribe(String)} filtered by session key.
 *
 * <p>Two implementations are anticipated:
 *
 * <ul>
 *   <li>{@link LocalToolEventBus} — in-process Reactor sink; the default and only impl wired by
 *       Spring today. Cluster visibility is limited to one replica.
 *   <li>A future {@code RedisToolEventBus} — Redis pub/sub-backed fanout for horizontal scale.
 *       Bring-up requires wiring against {@code agentscope-extensions-session-redis} and is
 *       deferred to Phase 3.1-Redis. Event payload is already JSON-serialisable via Jackson.
 * </ul>
 */
public interface ToolEventBus {

    /** Publishes a tool-call event to all current subscribers. */
    void publish(ToolEvent event);

    /**
     * Returns a {@link Flux} filtered to events matching the given session key. Callers should
     * manage the flux lifecycle (e.g. take-until-signal).
     */
    Flux<ToolEvent> subscribe(String sessionKey);

    /**
     * A single tool-call event.
     *
     * @param sessionKey the session key that produced this event
     * @param eventType {@code TOOL_CALL}
     * @param toolName the name of the tool
     * @param data additional event data (input args)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolEvent(
            String sessionKey, String eventType, String toolName, Map<String, Object> data) {

        public static ToolEvent toolCall(
                String sessionKey, String toolName, Map<String, Object> input) {
            return new ToolEvent(sessionKey, "TOOL_CALL", toolName, input);
        }
    }
}
