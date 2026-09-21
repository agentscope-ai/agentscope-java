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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import reactor.core.publisher.Flux;

/**
 * Event bus abstraction used by Agent Protocol task SSE endpoints.
 *
 * <p>Implementations are responsible for assigning a monotonically increasing sequence per task,
 * publishing events, and replaying events after {@code fromSeq}. The default implementation is
 * {@link AgentProtocolTaskEventBus}, which keeps the replay buffer in process memory. Applications
 * that need cross-instance streaming can provide a shared implementation, such as a Redis Streams
 * adapter, as the {@code AgentProtocolEventBus} bean.
 */
public interface AgentProtocolEventBus {

    /** Publishes an event for a task and returns the event after protocol fields are assigned. */
    RemoteAgentEvent publish(String taskId, RemoteAgentEvent event);

    /**
     * Subscribes to a task's event stream, replaying only events whose sequence is greater than
     * {@code fromSeq}.
     */
    Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq);

    /** Completes and releases the event stream for a terminal task. */
    void complete(String taskId);
}
