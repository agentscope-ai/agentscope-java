/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.benchmark.scenarios;

import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.Pipeline;
import io.agentscope.core.pipeline.SequentialPipeline;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for Pipeline performance.
 * Tests various aspects of Pipeline functionality including execution and chaining.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class PipelineBenchmark {

    private Pipeline pipeline;
    private List<Msg> testMessages;

    @Setup(Level.Trial)
    public void setup() {
        // Create a simple pipeline with a few operations
        Function<List<Msg>, List<Msg>> op1 =
                msgs -> {
                    // Simulate some processing
                    return msgs;
                };

        Function<List<Msg>, List<Msg>> op2 =
                msgs -> {
                    // Simulate some processing
                    return msgs;
                };

        pipeline = new SequentialPipeline(Arrays.asList(op1, op2));
        testMessages =
                Arrays.asList(
                        TestUtils.createUserMessage("User", "Message 1"),
                        TestUtils.createUserMessage("User", "Message 2"));
    }

    /**
     * Benchmark for pipeline execution
     */
    @Benchmark
    public void benchmarkPipelineExecution(Blackhole blackhole) {
        List<Msg> result = pipeline.execute(testMessages);
        blackhole.consume(result);
    }

    /**
     * Benchmark for pipeline creation
     */
    @Benchmark
    public Pipeline benchmarkPipelineCreation() {
        Function<List<Msg>, List<Msg>> op = msgs -> msgs;
        return new SequentialPipeline(Arrays.asList(op));
    }
}
