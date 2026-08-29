/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe, process-local {@link AguiResumeStateStore}. */
public final class InMemoryAguiResumeStateStore implements AguiResumeStateStore {

    private final ConcurrentMap<String, ThreadState> states = new ConcurrentHashMap<>();

    @Override
    public Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId) {
        ThreadState state = states.get(Objects.requireNonNull(threadId, "threadId"));
        return state != null ? state.pendingInterrupts() : Map.of();
    }

    @Override
    public RunClaim claimRun(String threadId, String runId) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(runId, "runId");
        AtomicReference<RunClaim> result = new AtomicReference<>();
        states.compute(
                threadId,
                (ignored, current) -> {
                    ThreadState state = current != null ? current : ThreadState.empty();
                    if (state.activeRunId() != null) {
                        result.set(RunClaim.rejected(state.activeRunId()));
                        return state;
                    }
                    result.set(RunClaim.acquired());
                    return new ThreadState(runId, state.pendingInterrupts());
                });
        return result.get();
    }

    @Override
    public void releaseRun(String threadId, String runId) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(runId, "runId");
        states.computeIfPresent(
                threadId,
                (ignored, state) -> {
                    if (!runId.equals(state.activeRunId())) {
                        return state;
                    }
                    if (state.pendingInterrupts().isEmpty()) {
                        return null;
                    }
                    return new ThreadState(null, state.pendingInterrupts());
                });
    }

    @Override
    public boolean replacePendingInterrupts(
            String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(runId, "runId");
        Map<String, AguiEvent.Interrupt> snapshot =
                Map.copyOf(Objects.requireNonNull(pendingInterrupts, "pendingInterrupts"));
        AtomicBoolean replaced = new AtomicBoolean(false);
        states.computeIfPresent(
                threadId,
                (ignored, state) -> {
                    if (!runId.equals(state.activeRunId())) {
                        return state;
                    }
                    replaced.set(true);
                    return new ThreadState(state.activeRunId(), snapshot);
                });
        return replaced.get();
    }

    private record ThreadState(
            String activeRunId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {

        private ThreadState {
            pendingInterrupts = Map.copyOf(pendingInterrupts);
        }

        private static ThreadState empty() {
            return new ThreadState(null, Map.of());
        }
    }
}
