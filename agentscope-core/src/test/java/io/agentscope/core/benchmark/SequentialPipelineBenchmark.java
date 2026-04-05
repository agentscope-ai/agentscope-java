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
package io.agentscope.core.benchmark;

import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.SequentialPipeline;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Benchmarks for sequential pipeline orchestration. */
@BenchmarkMode({Mode.SampleTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Thread)
public class SequentialPipelineBenchmark {

    private SequentialPipeline pipeline;
    private Msg input;

    @Setup(Level.Iteration)
    public void setUp() {
        pipeline = BenchmarkSupport.createSequentialPipeline(3);
        input = BenchmarkSupport.createInputMessage("Run the sequential pipeline benchmark.");
    }

    @Benchmark
    public Msg sequentialPipelineExecution() {
        return pipeline.execute(input).block(BenchmarkSupport.DEFAULT_TIMEOUT);
    }
}
