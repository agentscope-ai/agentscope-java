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
package io.agentscope.core.agent;

import java.util.Optional;
import reactor.util.context.ContextView;

/**
 * A lightweight channel that allows synchronously-invoked subagents to push their {@link Event}s
 * into the parent agent's {@code Flux<Event>} stream.
 *
 * <p>An instance is created inside {@link AgentBase#createEventStream} and stored in the Reactor
 * Context under {@link #CONTEXT_KEY}. When a tool method (e.g. {@code agent_spawn}) runs inside
 * that stream pipeline, it can retrieve the bus via {@link #fromContext(ContextView)} and forward
 * each child event to the parent sink.
 *
 * <p>Events emitted through the bus <em>must</em> already carry an {@link EventSource} (attached
 * by the caller) so downstream consumers can identify the originating subagent.
 *
 * <p>When the parent agent is invoked via {@code call()} (non-streaming), no bus is present in the
 * context and {@link #fromContext} returns {@link Optional#empty()}, allowing callers to fall back
 * gracefully to the blocking path.
 */
public interface SubagentEventBus {

    /**
     * Reactor Context key under which the bus instance is stored. Internal use only; use
     * {@link #fromContext(ContextView)} for retrieval.
     */
    String CONTEXT_KEY = "agentscope.subagent.event.bus";

    /**
     * Emits a subagent event into the parent stream. The event should carry a non-null
     * {@link EventSource} identifying the originating subagent.
     *
     * <p>This method is safe to call from any thread; the underlying {@code FluxSink.next} is
     * thread-safe.
     *
     * @param event the event to forward (must have {@link EventSource} set)
     */
    void emit(Event event);

    /**
     * Retrieves the {@link SubagentEventBus} from the Reactor Context, if present.
     *
     * @param ctx the current Reactor subscriber context
     * @return an {@link Optional} containing the bus, or empty when running outside a streaming
     *     pipeline
     */
    static Optional<SubagentEventBus> fromContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(CONTEXT_KEY, null));
    }
}
