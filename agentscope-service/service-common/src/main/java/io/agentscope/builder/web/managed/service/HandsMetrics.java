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
package io.agentscope.builder.web.managed.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Per-session hands-lease counters: acquires, releases, and acquire timeouts. Phase D
 * observability for {@code HandsLeaseService} — read by {@code DeploymentService} / the
 * deployment status endpoint to surface self_hosted hands health without wiring a full metrics
 * backend.
 */
@Component
public class HandsMetrics {

    /** Immutable snapshot of one session's counters. */
    public record Snapshot(long acquires, long releases, long timeouts) {}

    private final ConcurrentHashMap<String, LongAdder> acquires = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> releases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> timeouts = new ConcurrentHashMap<>();

    public void recordAcquire(String sessionId) {
        adder(acquires, sessionId).increment();
    }

    public void recordRelease(String sessionId) {
        adder(releases, sessionId).increment();
    }

    public void recordTimeout(String sessionId) {
        adder(timeouts, sessionId).increment();
    }

    /** Returns the current counters for one session (all zero if never recorded). */
    public Snapshot snapshot(String sessionId) {
        return new Snapshot(
                count(acquires, sessionId), count(releases, sessionId), count(timeouts, sessionId));
    }

    /** Returns every session with at least one recorded event, keyed by sessionId. */
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> result = new ConcurrentHashMap<>();
        for (String sessionId : acquires.keySet()) {
            result.put(sessionId, snapshot(sessionId));
        }
        for (String sessionId : releases.keySet()) {
            result.putIfAbsent(sessionId, snapshot(sessionId));
        }
        for (String sessionId : timeouts.keySet()) {
            result.putIfAbsent(sessionId, snapshot(sessionId));
        }
        return result;
    }

    private static LongAdder adder(ConcurrentHashMap<String, LongAdder> map, String sessionId) {
        return map.computeIfAbsent(sessionId, k -> new LongAdder());
    }

    private static long count(ConcurrentHashMap<String, LongAdder> map, String sessionId) {
        LongAdder adder = map.get(sessionId);
        return adder == null ? 0L : adder.sum();
    }
}
