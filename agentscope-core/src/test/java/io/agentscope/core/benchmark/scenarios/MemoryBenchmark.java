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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark for Memory performance.
 * Tests various aspects of Memory functionality including storage, retrieval, and management.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MemoryBenchmark {

    private InMemoryMemory memory;
    private Msg testMessage;

    @Setup(Level.Trial)
    public void setup() {
        memory = new InMemoryMemory();
        testMessage = TestUtils.createUserMessage("User", "Benchmark test message");
    }

    /**
     * Benchmark for adding messages to memory
     */
    @Benchmark
    public void benchmarkMemoryAdd(Blackhole blackhole) {
        memory.addMessage(testMessage);
        blackhole.consume(memory.getMessages());
    }

    /**
     * Benchmark for retrieving messages from memory
     */
    @Benchmark
    public void benchmarkMemoryGet(Blackhole blackhole) {
        memory.addMessage(testMessage);
        blackhole.consume(memory.getMessages());
    }

    /**
     * Benchmark for memory with many messages
     */
    @Benchmark
    public void benchmarkMemoryWithManyMessages(Blackhole blackhole) {
        // Add 100 messages to memory
        for (int i = 0; i < 100; i++) {
            Msg msg = TestUtils.createUserMessage("User", "Message " + i);
            memory.addMessage(msg);
        }
        blackhole.consume(memory.getMessages());
    }

    /**
     * Benchmark for clearing memory
     */
    @Benchmark
    public InMemoryMemory benchmarkMemoryClear() {
        memory.addMessage(testMessage);
        memory.clear();
        return memory;
    }
}
