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
package io.agentscope.claw2.runtime.session;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process registry for subagent spawn / run metadata (OpenClaw {@code subagent-registry}
 * analogue). Used for observability and correlation; not durable across JVM restarts.
 */
public final class SubagentRunRegistry {

    public enum RunStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public record RunRecord(
            String runId,
            String childSessionKey,
            String requesterSessionKey,
            String agentId,
            RunStatus status,
            long createdAtMs,
            Long startedAtMs,
            Long completedAtMs,
            String resultSummary,
            String error) {}

    private final ConcurrentHashMap<String, RunRecord> runs = new ConcurrentHashMap<>();

    public void put(RunRecord record) {
        runs.put(record.runId(), record);
    }

    public RunRecord get(String runId) {
        return runs.get(runId);
    }

    public void update(String runId, RunRecord updated) {
        runs.put(runId, updated);
    }

    public RunRecord remove(String runId) {
        return runs.remove(runId);
    }
}
