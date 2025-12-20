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

import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.tool.ToolSchema;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for Tool performance.
 * Tests various aspects of Tool functionality including registration, lookup, and execution.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ToolBenchmark {

    private MockToolkit toolkit;
    private ToolSchema testToolSchema;

    @Setup(Level.Trial)
    public void setup() {
        toolkit = new MockToolkit();
        // Assuming MockToolkit has some predefined tools
        // We'll use the first tool schema for benchmarking
        if (!toolkit.listTools().isEmpty()) {
            testToolSchema = toolkit.listTools().get(0);
        }
    }

    /**
     * Benchmark for tool lookup operation
     */
    @Benchmark
    public void benchmarkToolLookup(Blackhole blackhole) {
        if (testToolSchema != null) {
            blackhole.consume(toolkit.getTool(testToolSchema.getName()));
        }
    }

    /**
     * Benchmark for listing all tools
     */
    @Benchmark
    public void benchmarkToolListing(Blackhole blackhole) {
        blackhole.consume(toolkit.listTools());
    }

    /**
     * Benchmark for tool registration
     */
    @Benchmark
    public MockToolkit benchmarkToolRegistration() {
        MockToolkit newToolkit = new MockToolkit();
        // Registration happens in the constructor of MockToolkit
        return newToolkit;
    }
}
