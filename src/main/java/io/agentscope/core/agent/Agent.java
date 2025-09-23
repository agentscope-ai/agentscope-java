/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for all agents in the AgentScope framework.
 *
 * This interface defines the core contract for agents, including
 * basic properties and capabilities.
 */
public interface Agent {

    /**
     * Get the unique identifier for this agent.
     *
     * @return Agent ID
     */
    String getAgentId();

    /**
     * Get the name of this agent.
     *
     * @return Agent name
     */
    String getName();

    /**
     * Get the memory associated with this agent.
     *
     * @return Memory instance
     */
    Memory getMemory();

    /**
     * Set the memory for this agent.
     *
     * @param memory Memory instance to set
     */
    void setMemory(Memory memory);

    /**
     * Process a single input message and generate a response.
     *
     * @param msg Input message
     * @return Response message
     */
    Mono<Msg> reply(Msg msg);

    /**
     * Process a list of input messages and generate a response.
     *
     * @param msgs Input messages
     * @return Response message
     */
    Mono<Msg> reply(List<Msg> msgs);

    /**
     * Stream-based processing for a single input message using Reactive Streams.
     *
     * @param msg Input message
     * @return Publisher that emits response messages
     */
    Flux<Msg> stream(Msg msg);

    /**
     * Stream-based processing for multiple input messages using Reactive Streams.
     *
     * @param msgs Input messages
     * @return Publisher that emits response messages
     */
    Flux<Msg> stream(List<Msg> msgs);
}
