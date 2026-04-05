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
import io.agentscope.core.pipeline.MsgHub;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Benchmarks for MsgHub lifecycle and single-message distribution. */
public class MsgHubBenchmark {

    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 2, time = 1)
    @Measurement(iterations = 3, time = 1)
    @State(Scope.Thread)
    public static class LifecycleState {

        @Benchmark
        public MsgHub enterAndExitLifecycle() {
            MsgHub hub = BenchmarkSupport.createEnteredHub(3);
            hub.exit().block(BenchmarkSupport.DEFAULT_TIMEOUT);
            return hub;
        }
    }

    @BenchmarkMode({Mode.SampleTime, Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 2, time = 1)
    @Measurement(iterations = 3, time = 1)
    @State(Scope.Thread)
    public static class BroadcastState {

        private MsgHub hub;
        private Msg message;

        @Setup(Level.Iteration)
        public void setUp() {
            hub = BenchmarkSupport.createEnteredHub(3);
            message = BenchmarkSupport.createInputMessage("Broadcast benchmark message.");
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            if (hub != null) {
                hub.close();
            }
        }

        @Benchmark
        public Void singleMessageBroadcast() {
            return hub.broadcast(message).block(BenchmarkSupport.DEFAULT_TIMEOUT);
        }
    }
}
