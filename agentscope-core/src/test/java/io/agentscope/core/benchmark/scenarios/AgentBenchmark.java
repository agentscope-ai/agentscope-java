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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for Agent performance.
 * Tests various aspects of Agent functionality including creation, execution, and memory management.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class AgentBenchmark extends BaseBenchmark {

    private Msg testMessage;

    @Setup(Level.Iteration)
    public void iterationSetup() {
        testMessage = TestUtils.createUserMessage("User", "Benchmark test message");
    }

    /**
     * Benchmark for basic agent call operation
     */
    @Benchmark
    public void benchmarkAgentCall(Blackhole blackhole) {
        Msg response = agent.call(testMessage).block();
        blackhole.consume(response);
    }

    /**
     * Benchmark for agent creation
     */
    @Benchmark
    public ReActAgent benchmarkAgentCreation() {
        return ReActAgent.builder()
                .name("BenchmarkAgent-" + System.nanoTime())
                .sysPrompt("You are a benchmark agent.")
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    /**
     * Benchmark for agent with memory operations
     */
    @Benchmark
    public void benchmarkAgentWithMemory(Blackhole blackhole) {
        // Add some messages to memory
        for (int i = 0; i < 10; i++) {
            Msg msg = TestUtils.createUserMessage("User", "Message " + i);
            agent.getMemory().addMessage(msg);
        }

        Msg response = agent.call(testMessage).block();
        blackhole.consume(response);
    }
}
