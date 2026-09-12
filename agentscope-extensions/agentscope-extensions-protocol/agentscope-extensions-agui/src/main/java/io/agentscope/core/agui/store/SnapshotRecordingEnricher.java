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
package io.agentscope.core.agui.store;

import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Records outbound AG-UI events into an {@link AguiSnapshotStore}.
 *
 * <p>Appended <em>last</em> in the enricher chain so it observes fully enriched frames. Feeds every
 * frame to a per-{@code threadId:runId} {@link AguiSnapshotAccumulator}; on a terminal
 * {@link AguiEvent.RunFinished} / {@link AguiEvent.RunError} it materializes and persists the
 * snapshot, then drops the accumulator. Because the enricher runs on both the
 * {@code AgentEventConverterRegistry.convert()} path and the framework
 * {@code enrich(null, finishPendingEvents, ctx)} path, it sees frames from all converters.
 *
 * <p>{@link #flush(String, String)} is a safety net for streams that terminate without emitting a
 * terminal frame (e.g. {@code RUN_ERROR} produced directly by the adapter, which bypasses the
 * enricher).
 */
public final class SnapshotRecordingEnricher implements AguiEventEnricher {

    private final AguiSnapshotStore store;
    private final ConcurrentMap<String, AguiSnapshotAccumulator> accumulators =
            new ConcurrentHashMap<>();

    /** Create a recording enricher backed by the given store. */
    public SnapshotRecordingEnricher(AguiSnapshotStore store) {
        this.store = store;
    }

    /** The store this enricher records into. */
    public AguiSnapshotStore getStore() {
        return store;
    }

    @Override
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        if (store == null || context == null || events == null || events.isEmpty()) {
            return events;
        }
        String threadId = context.getThreadId();
        String runId = context.getRunId();
        if (threadId == null || runId == null) {
            return events;
        }
        String key = accumulatorKey(threadId, runId);
        AguiSnapshotAccumulator accumulator =
                accumulators.computeIfAbsent(
                        key,
                        ignored ->
                                new AguiSnapshotAccumulator(
                                        threadId, runId, store.find(threadId).orElse(null)));
        for (AguiEvent event : events) {
            accumulator.consume(event);
        }
        if (isTerminal(events)) {
            store.save(accumulator.materialize());
            accumulators.remove(key);
        }
        return events;
    }

    /**
     * Flush and persist the accumulator for a run, if one is still in flight.
     *
     * <p>Called as a safety net when a stream terminates without a terminal frame reaching the
     * enricher (e.g. an adapter-produced {@code RUN_ERROR}). Safe to call after a normal
     * terminal frame: the accumulator will already have been removed and this is a no-op.
     *
     * @param threadId the thread id
     * @param runId the run id
     */
    public void flush(String threadId, String runId) {
        if (store == null || threadId == null || runId == null) {
            return;
        }
        AguiSnapshotAccumulator accumulator = accumulators.remove(accumulatorKey(threadId, runId));
        if (accumulator != null) {
            store.save(accumulator.materialize());
        }
    }

    private static boolean isTerminal(List<AguiEvent> events) {
        for (AguiEvent event : events) {
            if (event instanceof AguiEvent.RunFinished || event instanceof AguiEvent.RunError) {
                return true;
            }
        }
        return false;
    }

    private static String accumulatorKey(String threadId, String runId) {
        return threadId + ":" + runId;
    }
}
