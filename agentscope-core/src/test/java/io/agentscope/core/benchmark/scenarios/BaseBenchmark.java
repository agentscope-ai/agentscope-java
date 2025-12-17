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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.concurrent.TimeUnit;

/**
 * Base class for AgentScope benchmarks.
 * Provides common setup and utilities for benchmark scenarios.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BaseBenchmark {

    protected ReActAgent agent;
    protected Model model;
    protected Toolkit toolkit;
    protected InMemoryMemory memory;

    @Setup(Level.Trial)
    public void setup() {
        // Initialize common components for benchmarks
        memory = new InMemoryMemory();
        model = new MockModel("Benchmark response");
        toolkit = new MockToolkit();

        agent =
                ReActAgent.builder()
                        .name("BenchmarkAgent")
                        .sysPrompt("You are a benchmark agent.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // Clean up resources if needed
        agent = null;
        model = null;
        toolkit = null;
        memory = null;
    }
}
