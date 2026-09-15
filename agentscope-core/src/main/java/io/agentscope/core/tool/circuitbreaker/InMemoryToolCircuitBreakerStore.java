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
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final Map<String, ToolCircuitSnapshot> states = new ConcurrentHashMap<>();

    @Override
    public ToolCircuitSnapshot snapshot(String toolName) {
        return states.getOrDefault(toolName, ToolCircuitSnapshot.CLOSED);
    }

    @Override
    public boolean compareAndSet(
            String toolName, ToolCircuitSnapshot expected, ToolCircuitSnapshot update) {
        AtomicBoolean committed = new AtomicBoolean();
        states.compute(
                toolName,
                (name, current) -> {
                    ToolCircuitSnapshot actual =
                            current == null ? ToolCircuitSnapshot.CLOSED : current;
                    if (!actual.equals(expected)) {
                        return current;
                    }
                    committed.set(true);
                    return ToolCircuitSnapshot.CLOSED.equals(update) ? null : update;
                });
        return committed.get();
    }

    @Override
    public void reset(String toolName) {
        states.remove(toolName);
    }
}
