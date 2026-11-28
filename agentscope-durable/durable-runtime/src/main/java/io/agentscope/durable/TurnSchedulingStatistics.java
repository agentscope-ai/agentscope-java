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
package io.agentscope.durable;

import io.agentscope.durable.SessionBackend.ClaimOutcome;
import io.agentscope.durable.SessionBackend.ClaimStatus;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Process-local monotonic Turn scheduling metrics with fixed labels. */
public record TurnSchedulingStatistics(
        Map<Source, Scan> scans, Map<Source, Claims> claims, long activeDispatchDuplicates) {
    public enum Source {
        MQ,
        SESSION_HINT,
        DATABASE,
        REPAIR,
        MANUAL
    }

    public record Scan(long total, long empty, long failures) {
        public double emptyRatio() {
            long completed = total - failures;
            return completed == 0 ? 0 : (double) empty / completed;
        }
    }

    public record Claims(Map<ClaimStatus, Long> outcomes, long errors, Latency discovery) {}

    /** Storage-clock milliseconds from eligibility to a newly acquired lease. */
    public record Latency(long count, long totalMillis, long maxMillis, List<Long> bucketCounts) {
        public static final List<Long> UPPER_BOUNDS_MILLIS =
                List.of(1L, 5L, 10L, 25L, 50L, 100L, 250L, 500L, 1_000L, 5_000L, 30_000L);
    }

    static final class Collector {
        private final Map<Source, Counters> bySource = new EnumMap<>(Source.class);
        private long activeDispatchDuplicates;

        private static final class Counters {
            long scans;
            long emptyScans;
            long scanFailures;
            long claimErrors;
            final Map<ClaimStatus, Long> outcomes = new EnumMap<>(ClaimStatus.class);
            long latencyCount;
            long latencyTotal;
            long latencyMax;
            final long[] buckets = new long[Latency.UPPER_BOUNDS_MILLIS.size() + 1];
        }

        private Counters counters(Source source) {
            return bySource.computeIfAbsent(source, ignored -> new Counters());
        }

        synchronized void scan(Source source, boolean empty, boolean failed) {
            var counters = counters(source);
            counters.scans++;
            if (failed) counters.scanFailures++;
            else if (empty) counters.emptyScans++;
        }

        synchronized void claimError(Source source) {
            counters(source).claimErrors++;
        }

        synchronized void claim(Source source, ClaimOutcome outcome) {
            var counters = counters(source);
            counters.outcomes.merge(outcome.status(), 1L, Long::sum);
            if (outcome.discoveryDelayMillis() == null) return;
            long delay = outcome.discoveryDelayMillis();
            counters.latencyCount++;
            counters.latencyTotal += delay;
            counters.latencyMax = Math.max(counters.latencyMax, delay);
            int bucket = 0;
            while (bucket < Latency.UPPER_BOUNDS_MILLIS.size()
                    && delay > Latency.UPPER_BOUNDS_MILLIS.get(bucket)) bucket++;
            counters.buckets[bucket]++;
        }

        synchronized void duplicate() {
            activeDispatchDuplicates++;
        }

        synchronized TurnSchedulingStatistics snapshot() {
            Map<Source, Scan> scans = new EnumMap<>(Source.class);
            Map<Source, Claims> claims = new EnumMap<>(Source.class);
            bySource.forEach(
                    (source, counters) -> {
                        scans.put(
                                source,
                                new Scan(
                                        counters.scans,
                                        counters.emptyScans,
                                        counters.scanFailures));
                        var buckets = new ArrayList<Long>(counters.buckets.length);
                        for (long bucket : counters.buckets) buckets.add(bucket);
                        claims.put(
                                source,
                                new Claims(
                                        Map.copyOf(counters.outcomes),
                                        counters.claimErrors,
                                        new Latency(
                                                counters.latencyCount,
                                                counters.latencyTotal,
                                                counters.latencyMax,
                                                List.copyOf(buckets))));
                    });
            return new TurnSchedulingStatistics(
                    Map.copyOf(scans), Map.copyOf(claims), activeDispatchDuplicates);
        }
    }
}
