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
package io.agentscope.builder.web.managed;

import io.agentscope.core.agent.AgentRun;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Local execution ownership. Cross-replica routing belongs to the turn coordinator. */
@Component
public final class AgentRunRegistry {
    private record Entry(String ownerId, String sessionId, AgentRun<?> run) {}

    private final ConcurrentHashMap<String, Entry> runs = new ConcurrentHashMap<>();

    /** Register before subscribing; all terminal outcomes remove the exact entry automatically. */
    public void register(String ownerId, String sessionId, AgentRun<?> run) {
        Entry entry =
                new Entry(
                        Objects.requireNonNull(ownerId),
                        Objects.requireNonNull(sessionId),
                        Objects.requireNonNull(run));
        if (runs.putIfAbsent(run.runId(), entry) != null) {
            throw new IllegalStateException("Run already registered: " + run.runId());
        }
        run.termination().subscribe(status -> runs.remove(run.runId(), entry));
    }

    public Optional<AgentRun<?>> find(String ownerId, String sessionId, String runId) {
        Entry entry = runs.get(runId);
        return entry != null
                        && entry.ownerId().equals(ownerId)
                        && entry.sessionId().equals(sessionId)
                ? Optional.of(entry.run())
                : Optional.empty();
    }

    public boolean cancel(String ownerId, String sessionId, String runId) {
        return find(ownerId, sessionId, runId).map(AgentRun::cancel).orElse(false);
    }
}
