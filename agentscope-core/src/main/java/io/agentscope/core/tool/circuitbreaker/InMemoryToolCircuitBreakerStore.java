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
package io.agentscope.core.tool.circuitbreaker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default in-process {@link ToolCircuitBreakerStore}, backed by a {@link ConcurrentHashMap}.
 *
 * <p>Suitable for single-replica deployments and for tests. State is per JVM: it is lost on restart
 * and not shared between replicas, so in a multi-replica deployment every replica trips its own
 * circuit independently. Use a distributed store when that matters.
 *
 * <p>Entries are created lazily on first write and removed once a tool is fully healthy again, so
 * tools that never fail cost nothing.
 */
public class InMemoryToolCircuitBreakerStore implements ToolCircuitBreakerStore {

    private final Map<String, AtomicLong> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, ToolCircuitSnapshot> circuits = new ConcurrentHashMap<>();

    @Override
    public long recordFailure(String toolName) {
        return failureCounts.computeIfAbsent(toolName, name -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void resetFailures(String toolName) {
        failureCounts.remove(toolName);
    }

    @Override
    public long failureCount(String toolName) {
        AtomicLong counter = failureCounts.get(toolName);
        return counter == null ? 0L : counter.get();
    }

    @Override
    public long open(String toolName, long openedAtEpochMilli) {
        // compute() holds the bin lock, so the generation increment and the timestamp stamp are
        // applied as one atomic step even when several failing calls trip the same tool at once.
        return circuits.compute(
                        toolName,
                        (name, current) ->
                                new ToolCircuitSnapshot(
                                        (current == null ? 0L : current.generation()) + 1L,
                                        openedAtEpochMilli))
                .generation();
    }

    @Override
    public void close(String toolName) {
        circuits.remove(toolName);
    }

    @Override
    public ToolCircuitSnapshot snapshot(String toolName) {
        return circuits.getOrDefault(toolName, ToolCircuitSnapshot.CLOSED);
    }
}
