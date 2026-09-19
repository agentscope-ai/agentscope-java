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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Optional capability for agents that emit fine-grained {@link AgentEvent}s.
 *
 * <p>This is the v2 streaming surface that replaces the deprecated v1 {@code StreamableAgent}.
 * Protocol adapters such as AG-UI detect this interface instead of concrete agent types, so
 * custom agents can plug in without extending {@code ReActAgent} or {@code HarnessAgent}.
 *
 * <p>Implementations should emit the full lifecycle that callers already expect from
 * {@code ReActAgent#streamEvents}, including {@link io.agentscope.core.event.AgentResultEvent}
 * immediately before {@link io.agentscope.core.event.AgentEndEvent} when a terminal message is
 * produced.
 */
public interface EventStreamingAgent {

    /**
     * Stream fine-grained {@link AgentEvent}s with a caller-supplied {@link RuntimeContext}.
     *
     * @param msgs input messages
     * @param context runtime context to propagate into the call, may be {@code null}
     * @return event stream covering the full agent invocation lifecycle
     */
    Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context);

    /**
     * Stream fine-grained {@link AgentEvent}s from the full agent lifecycle.
     *
     * @param msgs input messages
     * @return event stream covering the full agent invocation lifecycle
     */
    default Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, null);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single input message.
     *
     * @param msg input message
     * @return event stream covering the full agent invocation lifecycle
     */
    default Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg));
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single input message with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param msg input message
     * @param context runtime context to propagate into the call, may be {@code null}
     * @return event stream covering the full agent invocation lifecycle
     */
    default Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext context) {
        return streamEvents(List.of(msg), context);
    }
}
