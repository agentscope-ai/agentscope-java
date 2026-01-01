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
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Utility class providing functional-style pipeline operations.
 *
 * This class provides static methods offering convenient ways to execute agent
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
        return sequential(agents, (Msg) null);
    }

    /**
     * Execute agents in a sequential pipeline with structured output.
     *
     * @param agents List of agents to execute sequentially
     * @param input Initial input message
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing the final result with structured output
     */
    public static Mono<Msg> sequential(
            List<AgentBase> agents, Msg input, Class<?> structuredOutputClass) {
        return new SequentialPipeline(agents).execute(input, structuredOutputClass);
    }

    /**
     * Execute agents in a sequential pipeline with structured output and no initial input.
     *
     * @param agents List of agents to execute sequentially
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing the final result with structured output
     */
    public static Mono<Msg> sequential(List<AgentBase> agents, Class<?> structuredOutputClass) {
        return sequential(agents, null, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution.
     *
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
        return fanout(agents, (Msg) null);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution and structured output.
     *
     * @param agents List of agents to execute in parallel
     * @param input Input message to distribute to all agents
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanout(
            List<AgentBase> agents, Msg input, Class<?> structuredOutputClass) {
        return new FanoutPipeline(agents, true).execute(input, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with concurrent execution, structured output, and no
     * input.
     *
     * @param agents List of agents to execute in parallel
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanout(List<AgentBase> agents, Class<?> structuredOutputClass) {
        return fanout(agents, null, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution.
     *
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
        return fanoutSequential(agents, (Msg) null);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution and structured output.
     *
     * @param agents List of agents to execute sequentially (but independently)
     * @param input Input message to distribute to all agents
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanoutSequential(
            List<AgentBase> agents, Msg input, Class<?> structuredOutputClass) {
        return new FanoutPipeline(agents, false).execute(input, structuredOutputClass);
    }

    /**
     * Execute agents in a fanout pipeline with sequential execution, structured output, and no
     * input.
     *
     * @param agents List of agents to execute sequentially (but independently)
     * @param structuredOutputClass The class type for structured output
     * @return Mono containing list of all results with structured output
     */
    public static Mono<List<Msg>> fanoutSequential(
            List<AgentBase> agents, Class<?> structuredOutputClass) {
        return fanoutSequential(agents, null, structuredOutputClass);
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
        public Mono<Msg> execute(Msg input, Class<?> structuredOutputClass) {
            // Only the second pipeline uses structured output
            return first.execute(input).flatMap(msg -> second.execute(msg, structuredOutputClass));
        }

        @Override
        public String getDescription() {
            return String.format(
                    "Composed[%s -> %s]", first.getDescription(), second.getDescription());
        }
    }
}
