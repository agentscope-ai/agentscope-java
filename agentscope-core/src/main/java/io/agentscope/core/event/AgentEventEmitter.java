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
package io.agentscope.core.event;

import java.util.Optional;
import reactor.util.context.ContextView;

/**
 * Public interface for emitting {@link AgentEvent}s into the parent agent's {@code streamEvents()}
 * event stream from within tool execution code.
 *
 * <p>An instance is created inside {@link io.agentscope.core.ReActAgent#streamEvents} and stored
 * in the Reactor Context under {@link #CONTEXT_KEY}. When a tool method (e.g. {@code agent_spawn})
 * runs inside that stream pipeline, it can retrieve the emitter via
 * {@link #fromContext(ContextView)} and inject custom events (e.g. {@link SubagentExposedEvent}).
 *
 * <p>When the parent agent is invoked via {@code call()} (non-streaming), no emitter is present
 * in the context and {@link #fromContext} returns {@link Optional#empty()}, allowing callers to
 * fall back gracefully.
 *
 * <p>This is the {@link AgentEvent} counterpart of
 * {@link io.agentscope.core.agent.SubagentEventBus}, which serves the deprecated
 * {@link io.agentscope.core.agent.Event} type.
 */
@FunctionalInterface
public interface AgentEventEmitter {

    /**
     * Reactor Context key under which the emitter instance is stored. Use
     * {@link #fromContext(ContextView)} for retrieval.
     */
    String CONTEXT_KEY = "agentscope.agent.event.emitter";

    /**
     * Reactor Context key for a forwarding emitter injected by a parent tool (e.g.
     * {@code agent_spawn}) into a child agent's Reactor Context. When present, the child's
     * {@code publishEvent()} uses this emitter instead of the direct {@code FluxSink}, so every
     * child event is tagged with a source path before entering the parent's stream.
     *
     * <p>This is intentionally separate from {@link #CONTEXT_KEY}: the regular key carries the
     * parent's own emitter (used by tools to emit custom events like
     * {@link SubagentExposedEvent}), while this key carries a source-tagging wrapper for the
     * child's internal events.
     */
    String FORWARDING_CONTEXT_KEY = "agentscope.agent.event.forwarding.emitter";

    /**
     * Emits an {@link AgentEvent} into the parent's {@code streamEvents()} stream.
     *
     * <p>This method is safe to call from any thread; the underlying {@code FluxSink.next} is
     * thread-safe.
     *
     * @param event the event to emit
     */
    void emit(AgentEvent event);

    /**
     * Retrieves the {@link AgentEventEmitter} from the Reactor Context, if present.
     *
     * @param ctx the current Reactor subscriber context
     * @return an {@link Optional} containing the emitter, or empty when running outside a
     *     streaming pipeline
     */
    static Optional<AgentEventEmitter> fromContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(CONTEXT_KEY, null));
    }

    /**
     * Retrieves the forwarding {@link AgentEventEmitter} from the Reactor Context, if present.
     *
     * @param ctx the current Reactor subscriber context
     * @return an {@link Optional} containing the forwarding emitter, or empty when not in a
     *     child-agent forwarding context
     */
    static Optional<AgentEventEmitter> fromForwardingContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(FORWARDING_CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(FORWARDING_CONTEXT_KEY, null));
    }
}
