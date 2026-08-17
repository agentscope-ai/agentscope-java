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
package io.agentscope.spring.boot.admin.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe accumulator for token usage counters, sliced by agent name and by model name.
 *
 * <p>Uses {@link LongAdder} so high-concurrency tool/model call paths don't contend on a single
 * AtomicLong. Snapshots are read-side: every read recomputes a frozen {@link UsageStats}.
 */
public final class MetricsRecorder {

    private final ConcurrentMap<String, Bucket> byAgent = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> byModel = new ConcurrentHashMap<>();
    private final Bucket global = new Bucket();

    public void record(String agentName, String modelName, int inputTokens, int outputTokens) {
        long input = Math.max(0, inputTokens);
        long output = Math.max(0, outputTokens);
        long total = input + output;
        global.add(1L, input, output, total);
        if (agentName != null && !agentName.isBlank()) {
            byAgent.computeIfAbsent(agentName, k -> new Bucket()).add(1L, input, output, total);
        }
        if (modelName != null && !modelName.isBlank()) {
            byModel.computeIfAbsent(modelName, k -> new Bucket()).add(1L, input, output, total);
        }
    }

    public UsageStats globalSnapshot() {
        return global.snapshot("global");
    }

    public Map<String, UsageStats> snapshotByAgent() {
        return snapshotMap(byAgent);
    }

    public Map<String, UsageStats> snapshotByModel() {
        return snapshotMap(byModel);
    }

    /** Test-only — drop all counters. */
    public void reset() {
        byAgent.clear();
        byModel.clear();
        global.reset();
    }

    private static Map<String, UsageStats> snapshotMap(ConcurrentMap<String, Bucket> src) {
        Map<String, UsageStats> out = new LinkedHashMap<>();
        for (Map.Entry<String, Bucket> e : src.entrySet()) {
            out.put(e.getKey(), e.getValue().snapshot(e.getKey()));
        }
        return out;
    }

    private static final class Bucket {
        private final LongAdder calls = new LongAdder();
        private final LongAdder input = new LongAdder();
        private final LongAdder output = new LongAdder();
        private final LongAdder total = new LongAdder();

        void add(long c, long i, long o, long t) {
            calls.add(c);
            input.add(i);
            output.add(o);
            total.add(t);
        }

        UsageStats snapshot(String key) {
            return new UsageStats(key, calls.sum(), input.sum(), output.sum(), total.sum());
        }

        void reset() {
            calls.reset();
            input.reset();
            output.reset();
            total.reset();
        }
    }
}
