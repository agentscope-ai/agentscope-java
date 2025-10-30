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
package io.agentscope.core.pipeline;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Sequential pipeline implementation for agent orchestration.
 *
 * This pipeline executes agents in sequence, where each agent's output becomes
 * the input for the next agent.
 *
 * Execution flow:
 * Input -> Agent1 -> Agent2 -> ... -> AgentN -> Output
 *
 * Features:
 * - Chain pattern execution
 * - State propagation through the pipeline
 * - Error handling and recovery
 * - Support for empty agent lists
 */
public class SequentialPipeline implements Pipeline<Msg> {

    private final List<AgentBase> agents;
    private final String description;

    /**
     * Create a sequential pipeline with the specified agents.
     *
     * @param agents List of agents to execute in sequence
     */
    public SequentialPipeline(List<AgentBase> agents) {
        this.agents = List.copyOf(agents != null ? agents : List.of());
        this.description = String.format("SequentialPipeline[%d agents]", this.agents.size());
    }

    @Override
    public Mono<Msg> execute(Msg input) {
        if (agents.isEmpty()) {
            return Mono.justOrEmpty(input);
        }
        Mono<Msg> chain = Mono.justOrEmpty(input);
        for (AgentBase agent : agents) {
            chain = chain.flatMap(agent::call);
        }
        return chain;
    }

    /**
     * Get the list of agents in this pipeline.
     *
     * @return Copy of the agents list
     */
    public List<AgentBase> getAgents() {
        return agents;
    }

    /**
     * Get the number of agents in this pipeline.
     *
     * @return Number of agents
     */
    public int size() {
        return agents.size();
    }

    /**
     * Check if this pipeline is empty (has no agents).
     *
     * @return True if pipeline has no agents
     */
    public boolean isEmpty() {
        return agents.isEmpty();
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return String.format(
                "%s{agents=%s}",
                getClass().getSimpleName(), agents.stream().map(AgentBase::getName).toList());
    }

    /**
     * Create a builder for constructing sequential pipelines.
     *
     * @return New pipeline builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating sequential pipelines with fluent API.
     */
    public static class Builder {
        private final java.util.ArrayList<AgentBase> agents = new java.util.ArrayList<>();

        /**
         * Add an agent to the pipeline.
         *
         * @param agent Agent to add
         * @return This builder for method chaining
         */
        public Builder addAgent(AgentBase agent) {
            if (agent != null) {
                agents.add(agent);
            }
            return this;
        }

        /**
         * Add multiple agents to the pipeline.
         *
         * @param agentList List of agents to add
         * @return This builder for method chaining
         */
        public Builder addAgents(List<AgentBase> agentList) {
            if (agentList != null) {
                agents.addAll(agentList);
            }
            return this;
        }

        /**
         * Build the sequential pipeline.
         *
         * @return Configured sequential pipeline
         */
        public SequentialPipeline build() {
            return new SequentialPipeline(agents);
        }
    }
}
