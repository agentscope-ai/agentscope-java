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
package io.agentscope.core.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Sample benchmark test to verify the benchmark framework is working correctly.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SampleBenchmarkTest {

    /**
     * Sample benchmark method that measures the performance of Math.sqrt().
     */
    @Benchmark
    public void sqrtBenchmark(Blackhole blackhole) {
        double result = Math.sqrt(123456789.0);
        blackhole.consume(result);
    }

    /**
     * Sample benchmark method that measures the performance of String concatenation.
     */
    @Benchmark
    public String stringConcatenationBenchmark() {
        return "Hello" + " " + "World" + " " + System.currentTimeMillis();
    }
}
