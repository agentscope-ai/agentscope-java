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

package io.agentscope.core.a2a.server.executor.runner;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Interface for running agents in A2A Server.
 *
 * <p>This interface defines the core contract for running agents in the A2A Server.
 * It provides methods for starting, stopping, and get messages from agents.
 *
 * <p>This interface is designed for extending actual handling logics for {@link io.agentscope.core.agent.Agent},
 * methods will use {@link Msg} as input and use {@link Event} as output.
 * Developers can do some pre-processing before call {@link io.agentscope.core.agent.Agent} or post-processing after
 * calling {@link io.agentscope.core.agent.Agent}.
 */
public interface AgentRunner {

    /**
     * Get agent name to build AgentCard.
     *
     * @return agent name
     */
    String getAgentName();

    /**
     * Get agent description to build AgentCard.
     *
     * @return agent description
     */
    String getAgentDescription();

    /**
     * Start to handle agent request with streaming output.
     *
     * @param requestMessages the messages from a2a client
     * @param options the options for agent request, such as `taskId`, `sessionId` or `userId` of this request
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options);

    /**
     * Stop to handle agent request.
     *
     * @param taskId the taskId of request needed to stop
     */
    void stop(String taskId);
}
