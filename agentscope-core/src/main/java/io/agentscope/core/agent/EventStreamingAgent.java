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
 * Capability interface for agents that can emit the fine-grained 2.0 {@link AgentEvent} stream.
 *
 * <p>This is the modern replacement for the deprecated v1 {@link StreamableAgent} {@code
 * stream(...)} API, which only surfaced coarse {@code REASONING} / {@code SUMMARY} / {@code
 * TOOL_RESULT} events and, critically, could not carry human-in-the-loop signals such as {@code
 * RequireUserConfirmEvent} / {@code RequestStopEvent}.
 *
 * <p>Both {@code io.agentscope.core.ReActAgent} and {@code io.agentscope.harness.agent.HarnessAgent}
 * (which wraps a {@code ReActAgent} delegate) implement this interface. Consumers such as the AG-UI
 * adapter can branch on {@code agent instanceof EventStreamingAgent} to consume the v2 stream
 * uniformly, regardless of the concrete agent type, while still falling back to the deprecated v1
 * path for custom {@link Agent} implementations that do not support event streaming.
 */
public interface EventStreamingAgent {

    /**
     * Stream fine-grained {@link AgentEvent}s covering the full agent invocation lifecycle.
     *
     * @param msgs input messages
     * @param context runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context);
}
