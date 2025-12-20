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

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.Blackhole;
import reactor.core.publisher.Flux;

/**
 * Benchmark for Model performance.
 * Tests various aspects of Model functionality including streaming and non-streaming calls.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ModelBenchmark {

    private MockModel mockModel;
    private List<Msg> testMessages;

    @Setup(Level.Trial)
    public void setup() {
        mockModel = new MockModel("Benchmark model response");
        testMessages =
                Collections.singletonList(
                        TestUtils.createUserMessage("User", "Benchmark test message"));
    }

    /**
     * Benchmark for model stream operation
     */
    @Benchmark
    public void benchmarkModelStream(Blackhole blackhole) {
        Flux<ChatResponse> response = mockModel.stream(testMessages, null, null);
        response.subscribe(blackhole::consume);
    }

    /**
     * Benchmark for model non-stream operation (if applicable)
     */
    @Benchmark
    public void benchmarkModelNonStream(Blackhole blackhole) {
        // This would depend on the specific model implementation
        // For now, we'll just consume the stream as a non-stream equivalent
        Flux<ChatResponse> response = mockModel.stream(testMessages, null, null);
        ChatResponse result = response.blockLast();
        blackhole.consume(result);
    }
}
