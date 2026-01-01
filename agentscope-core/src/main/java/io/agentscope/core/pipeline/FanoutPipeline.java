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
package io.agentscope.core.pipeline;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.exception.CompositeAgentException;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Fanout pipeline implementation for parallel agent execution.
 *
 * This pipeline distributes the same input to multiple agents and executes
 * them either concurrently or sequentially, collecting all results.
 *
 * Execution flow:
 * Input -> [Agent1, Agent2, ..., AgentN] -> [Output1, Output2, ..., OutputN]
 *
 * Features:
 * - Fan-out pattern execution (one input, multiple outputs)
 * - Configurable concurrent vs sequential execution
 * - Input isolation (each agent gets a copy of the input)
 * - Result aggregation into a list
 * - Enhanced error handling with detailed agent failure information
 * - Composite exception collection for multiple agent failures
 * - Individual agent error isolation without affecting others
 */
public class FanoutPipeline implements Pipeline<List<Msg>> {

    private final List<AgentBase> agents;
    private final boolean enableConcurrent;
    private final String description;
    private final Scheduler scheduler;

    /**
     * Create a fanout pipeline with the specified agents and execution mode.
     * Uses boundedElastic scheduler by default for concurrent execution.
     *
     * @param agents List of agents to execute in parallel
     * @param enableConcurrent True for concurrent execution, false for sequential
     */
    public FanoutPipeline(List<AgentBase> agents, boolean enableConcurrent) {
        this.agents = List.copyOf(agents != null ? agents : List.of());
        this.enableConcurrent = enableConcurrent;
        this.scheduler = Schedulers.boundedElastic();
        this.description =
                String.format(
                        "FanoutPipeline[%d agents, %s]",
                        this.agents.size(), enableConcurrent ? "concurrent" : "sequential");
    }

    /**
     * Create a fanout pipeline with the specified agents, execution mode and scheduler.
     *
     * @param agents List of agents to execute in parallel
     * @param enableConcurrent True for concurrent execution, false for sequential
     * @param scheduler Custom scheduler for execution
     */
    public FanoutPipeline(List<AgentBase> agents, boolean enableConcurrent, Scheduler scheduler) {
        this.agents = List.copyOf(agents != null ? agents : List.of());
        this.enableConcurrent = enableConcurrent;
        this.scheduler = scheduler != null ? scheduler : Schedulers.boundedElastic();
        this.description =
                String.format(
                        "FanoutPipeline[%d agents, %s]",
                        this.agents.size(), enableConcurrent ? "concurrent" : "sequential");
    }

    /**
     * Create a fanout pipeline with concurrent execution enabled by default.
     *
     * @param agents List of agents to execute in parallel
     */
    public FanoutPipeline(List<AgentBase> agents) {
        this(agents, true);
    }

    @Override
    public Mono<List<Msg>> execute(Msg input) {
        return execute(input, null);
    }

    @Override
    public Mono<List<Msg>> execute(Msg input, Class<?> structuredOutputClass) {
        if (agents.isEmpty()) {
            return Mono.just(List.of());
        }
        return enableConcurrent
                ? executeConcurrent(input, structuredOutputClass)
                : executeSequential(input, structuredOutputClass);
    }

    /**
     * Execute agents concurrently using reactive merge with true parallelism.
     * All agents are executed even if some fail, but the all error is propagated.
     *
     * <p>Implementation: Each agent's call is subscribed on a separate thread from the
     * configured scheduler, enabling true concurrent execution of HTTP requests to the LLM API.
     * By default, the {@link Schedulers#boundedElastic()} scheduler is used, which is optimal
     * for I/O-bound operations. A custom scheduler can also be provided via constructor.
     *
     * @param input Input message to distribute to all agents
     * @param structuredOutputClass The class type for structured output (optional)
     * @return Mono containing list of all agent results
     */
    private Mono<List<Msg>> executeConcurrent(Msg input, Class<?> structuredOutputClass) {
        // Collect all agent results and errors

        List<CompositeAgentException.AgentExceptionInfo> errors =
                Collections.synchronizedList(new ArrayList<>());

        List<Mono<Msg>> agentMonos =
                agents.stream()
                        .map(
                                agent -> {
                                    // Choose call method based on structured output parameter
                                    Mono<Msg> mono =
                                            structuredOutputClass != null
                                                    ? agent.call(input, structuredOutputClass)
                                                    : agent.call(input);

                                    return mono.subscribeOn(scheduler)
                                            .doOnError(
                                                    throwable ->
                                                            errors.add(
                                                                    new CompositeAgentException
                                                                            .AgentExceptionInfo(
                                                                            agent.getAgentId(),
                                                                            agent.getName(),
                                                                            throwable)))
                                            .onErrorResume(e -> Mono.empty());
                                })
                        .toList();

        return Flux.merge(agentMonos)
                .collectList()
                .flatMap(
                        results -> {
                            // If there was an error, propagate the first one
                            if (!errors.isEmpty()) {
                                return Mono.error(
                                        new CompositeAgentException(
                                                "Multiple agent execution failures occurred",
                                                errors));
                            }
                            return Mono.just(results);
                        });
    }

    /**
     * Execute agents sequentially with independent inputs.
     *
     * @param input Input message to distribute to all agents
     * @param structuredOutputClass The class type for structured output (optional)
     * @return Mono containing list of all agent results
     */
    private Mono<List<Msg>> executeSequential(Msg input, Class<?> structuredOutputClass) {
        List<Mono<Msg>> chain = new ArrayList<>();
        for (AgentBase agent : agents) {
            // Choose call method based on structured output parameter
            Mono<Msg> mono =
                    structuredOutputClass != null
                            ? agent.call(input, structuredOutputClass)
                            : agent.call(input);
            chain.add(mono);
        }
        return Flux.concat(chain).collectList();
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

    /**
     * Check if concurrent execution is enabled.
     *
     * @return True if agents execute concurrently
     */
    public boolean isConcurrentEnabled() {
        return enableConcurrent;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return String.format(
                "%s{agents=%s, concurrent=%s}",
                getClass().getSimpleName(),
                agents.stream().map(AgentBase::getName).toList(),
                enableConcurrent);
    }

    /**
     * Create a builder for constructing fanout pipelines.
     *
     * @return New pipeline builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating fanout pipelines with fluent API.
     */
    public static class Builder {
        private final List<AgentBase> agents = new ArrayList<>();
        private boolean enableConcurrent = true;
        private Scheduler scheduler;

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
         * Set whether to enable concurrent execution.
         *
         * @param concurrent True for concurrent execution, false for sequential
         * @return This builder for method chaining
         */
        public Builder concurrent(boolean concurrent) {
            this.enableConcurrent = concurrent;
            return this;
        }

        /**
         *
         * @param scheduler
         * @return
         */
        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Enable concurrent execution (default).
         *
         * @return This builder for method chaining
         */
        public Builder concurrent() {
            return concurrent(true);
        }

        /**
         * Enable sequential execution.
         *
         * @return This builder for method chaining
         */
        public Builder sequential() {
            return concurrent(false);
        }

        /**
         * Build the fanout pipeline.
         *
         * @return Configured fanout pipeline
         */
        public FanoutPipeline build() {
            return new FanoutPipeline(agents, enableConcurrent, scheduler);
        }
    }
}
