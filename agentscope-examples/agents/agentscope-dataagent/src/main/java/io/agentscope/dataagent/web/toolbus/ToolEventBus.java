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
package io.agentscope.dataagent.web.toolbus;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Singleton in-memory event bus for tool-call events emitted by HarnessAgent hooks.
 *
 * <p>Consumers subscribe via {@link #subscribe(String)} filtering by session key.
 * Publishers call {@link #publish(ToolEvent)} from the hook when a tool call is about to execute.
 */
@Component
public class ToolEventBus {

    private final Sinks.Many<ToolEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    /** Publishes a tool-call event to all current subscribers. */
    public void publish(ToolEvent event) {
        sink.tryEmitNext(event);
    }

    /**
     * Returns a {@link Flux} filtered to events matching the given session key.
     * Callers should manage the flux lifecycle (e.g. take-until-signal).
     */
    public Flux<ToolEvent> subscribe(String sessionKey) {
        return sink.asFlux().filter(e -> sessionKey.equals(e.sessionKey()));
    }

    /**
     * A single tool-call or tool-result event.
     *
     * @param sessionKey the session key that produced this event
     * @param eventType {@code TOOL_CALL} or {@code TOOL_RESULT}
     * @param toolName the name of the tool
     * @param data additional event data (input args or result text)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolEvent(
            String sessionKey, String eventType, String toolName, Map<String, Object> data) {

        public static ToolEvent toolCall(
                String sessionKey, String toolName, Map<String, Object> input) {
            return new ToolEvent(sessionKey, "TOOL_CALL", toolName, input);
        }
    }
}
