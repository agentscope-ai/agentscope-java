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
package io.agentscope.core.studio;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook for automatically forwarding agent messages to Studio.
 *
 * <p>This hook intercepts PostCallEvent events (fired after an agent produces a response)
 * and sends the output message to Studio for visualization. If message forwarding fails,
 * the error is logged but does not interrupt agent execution.
 *
 * <p>Usage:
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .hook(new StudioMessageHook(studioClient))
 *     .build();
 * }</pre>
 */
public class StudioMessageHook implements Hook {
    private static final Logger logger = LoggerFactory.getLogger(StudioMessageHook.class);

    private final StudioClient studioClient;

    /**
     * Creates a new Studio message hook.
     *
     * @param studioClient The Studio HTTP client for sending messages
     */
    public StudioMessageHook(StudioClient studioClient) {
        this.studioClient = studioClient;
    }

    /**
     * Handles hook events.
     *
     * <p>This method listens for PostCallEvent and forwards the agent's output message
     * to Studio. Other event types are passed through unchanged.
     *
     * @param event The hook event to process
     * @param <T> The event type
     * @return A Mono that emits the event (possibly after async processing)
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent) {
            PostCallEvent e = (PostCallEvent) event;
            // Get the agent's output message
            Msg msg = e.getFinalMessage();

            // Check if Studio client is available
            if (studioClient == null) {
                logger.warn(
                        "StudioMessageHook has null studioClient. Initialize StudioManager before"
                            + " creating hooks, or remove this hook from the agent configuration."
                            + " Skipping message forwarding.");
                return Mono.just(event);
            }

            // Send to Studio asynchronously (fire and forget)
            // Don't block agent execution even if Studio is unavailable
            return studioClient
                    .pushMessage(msg)
                    .thenReturn(event)
                    .onErrorResume(
                            ex -> {
                                // Log error but don't fail agent execution
                                logger.error("Failed to push message to Studio", ex);
                                return Mono.just(event);
                            });
        }
        return Mono.just(event);
    }
}
