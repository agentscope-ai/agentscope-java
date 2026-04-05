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

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/** Launcher for running the core JMH benchmark suite from Maven test classpath. */
public final class BenchmarkLauncher {

    private BenchmarkLauncher() {}

    public static void main(String[] args) throws RunnerException {
        String includePattern = System.getProperty("benchmark.include", ".*Benchmark.*");
        int warmupIterations = Integer.getInteger("benchmark.warmupIterations", 2);
        int measurementIterations = Integer.getInteger("benchmark.measurementIterations", 3);
        int forks = Integer.getInteger("benchmark.forks", 1);

        Options options =
                new OptionsBuilder()
                        .include(includePattern)
                        .warmupIterations(warmupIterations)
                        .measurementIterations(measurementIterations)
                        .forks(forks)
                        .resultFormat(ResultFormatType.JSON)
                        .result("target/jmh-result.json")
                        .build();

        new Runner(options).run();
    }
}
