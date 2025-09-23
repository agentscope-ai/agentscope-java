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
 * Utility class providing functional-style pipeline operations.
 *
 * This class provides static methods that mirror the Python agentscope
 * functional pipeline API, offering convenient ways to execute agent
 * pipelines without creating explicit pipeline objects.
 *
 * These methods are stateless and suitable for one-time use, while the
 * class-based Pipeline implementations are better for reusable configurations.
 */
public class Pipelines {

    private Pipelines() {
        // Utility class - prevent instantiation
    }

    /**
     * Execute agents in a sequential pipeline.
     *
     * This is equivalent to Python's sequential_pipeline() function.
     * The output of each agent becomes the input of the next agent.
     *
     * @param agents List of agents to execute sequentially
     * @param input Initial input message
     * @return Mono containing the final result
     */
    public static Mono<Msg> sequential(List<AgentBase> agents, Msg input) {
        return new SequentialPipeline(agents).execute(input);
    }

    /**
     * Execute agents in a sequential pipeline with no initial input.
     *
     * @param agents List of agents to execute sequentially
     * @return Mono containing the final result
     */
    public static Mono<Msg> sequential(List<AgentBase> agents) {
        return sequential(agents, null);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution.
     *
     * This is equivalent to Python's fanout_pipeline() function with enable_gather=True.
     * All agents receive the same input and execute concurrently.
     *
     * @param agents List of agents to execute in parallel
     * @param input Input message to distribute to all agents
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanout(List<AgentBase> agents, Msg input) {
        return new FanoutPipeline(agents, true).execute(input);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution and no input.
     *
     * @param agents List of agents to execute in parallel
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanout(List<AgentBase> agents) {
        return fanout(agents, null);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution.
     *
     * This is equivalent to Python's fanout_pipeline() function with enable_gather=False.
     * All agents receive the same input but execute one after another.
     *
     * @param agents List of agents to execute sequentially (but independently)
     * @param input Input message to distribute to all agents
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanoutSequential(List<AgentBase> agents, Msg input) {
        return new FanoutPipeline(agents, false).execute(input);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution and no input.
     *
     * @param agents List of agents to execute sequentially (but independently)
     * @return Mono containing list of all results
     */
    public static Mono<List<Msg>> fanoutSequential(List<AgentBase> agents) {
        return fanoutSequential(agents, null);
    }

    /**
     * Create a reusable sequential pipeline.
     *
     * @param agents List of agents for the pipeline
     * @return Sequential pipeline instance
     */
    public static SequentialPipeline createSequential(List<AgentBase> agents) {
        return new SequentialPipeline(agents);
    }

    /**
     * Create a reusable fanout pipeline with concurrent execution.
     *
     * @param agents List of agents for the pipeline
     * @return Concurrent fanout pipeline instance
     */
    public static FanoutPipeline createFanout(List<AgentBase> agents) {
        return new FanoutPipeline(agents, true);
    }

    /**
     * Create a reusable fanout pipeline with sequential execution.
     *
     * @param agents List of agents for the pipeline
     * @return Sequential fanout pipeline instance
     */
    public static FanoutPipeline createFanoutSequential(List<AgentBase> agents) {
        return new FanoutPipeline(agents, false);
    }

    /**
     * Compose two sequential pipelines into a single pipeline.
     *
     * @param first First pipeline to execute
     * @param second Second pipeline to execute with output from first
     * @return Composed pipeline
     */
    public static Pipeline<Msg> compose(SequentialPipeline first, SequentialPipeline second) {
        return new ComposedSequentialPipeline(first, second);
    }

    /**
     * Internal class for composing sequential pipelines.
     */
    private static class ComposedSequentialPipeline implements Pipeline<Msg> {
        private final SequentialPipeline first;
        private final SequentialPipeline second;

        ComposedSequentialPipeline(SequentialPipeline first, SequentialPipeline second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public Mono<Msg> execute(Msg input) {
            return first.execute(input).flatMap(second::execute);
        }

        @Override
        public String getDescription() {
            return String.format(
                    "Composed[%s -> %s]", first.getDescription(), second.getDescription());
        }
    }
}
