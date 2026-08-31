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
package io.agentscope.examples.copilotkit.service;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.examples.copilotkit.service.InMemoryAgentEventStore.StoredAgentEvent;
import io.agentscope.examples.copilotkit.workbench.WorkbenchAguiEventConverter;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Replays persisted {@link io.agentscope.core.event.AgentEvent}s into AG-UI frames.
 *
 * <p>Uses the same converter / enricher set as the live adapter (minus the persisting enricher),
 * and resets workbench snapshot baselines per historical run so {@code STATE_SNAPSHOT} /
 * {@code STATE_DELTA} projection matches a fresh conversion.
 *
 */
@Component
public final class AgentEventAguiReplayer {

    private final InMemoryAgentEventStore eventStore;
    private final AguiProperties properties;
    private final List<AgentEventConverter> eventConverters;
    private final List<AguiEventEnricher> replayEnrichers;
    private final WorkbenchAguiEventConverter workbenchConverter;

    public AgentEventAguiReplayer(
            InMemoryAgentEventStore eventStore,
            AguiProperties properties,
            ObjectProvider<AgentEventConverter> eventConvertersProvider,
            ObjectProvider<AguiEventEnricher> eventEnrichersProvider,
            ObjectProvider<WorkbenchAguiEventConverter> workbenchConverterProvider) {
        this.eventStore = eventStore;
        this.properties = properties;
        this.eventConverters = eventConvertersProvider.orderedStream().toList();
        this.replayEnrichers =
                eventEnrichersProvider
                        .orderedStream()
                        .filter(enricher -> !(enricher instanceof PersistingAgentEventEnricher))
                        .toList();
        this.workbenchConverter = workbenchConverterProvider.getIfAvailable();
    }

    /**
     * Project stored AgentEvents for a thread into AG-UI events.
     *
     * @param threadId thread to replay
     * @param connectInput optional connect payload (tools / state used by converters)
     * @return AG-UI events in conversion order; empty when the thread has no history
     */
    public List<AguiEvent> replay(String threadId, RunAgentInput connectInput) {
        List<StoredAgentEvent> history = eventStore.snapshot(threadId);
        if (history.isEmpty()) {
            return List.of();
        }

        AguiAdapterConfig config = replayConfig();
        AgentEventConverterRegistry registry =
                new AgentEventConverterRegistry(
                        config.getEventConverters(), config.getEventEnrichers());

        List<AguiEvent> projected = new ArrayList<>();
        String currentRunId = null;
        AguiStreamContext context = null;

        for (StoredAgentEvent stored : history) {
            String runId = stored.runId();
            if (!Objects.equals(runId, currentRunId)) {
                if (context != null) {
                    projected.addAll(registry.enrich(null, context.finishPendingEvents(), context));
                }
                currentRunId = runId;
                if (workbenchConverter != null) {
                    workbenchConverter.forgetRun(threadId, currentRunId);
                }
                // Prefer stored run input so RUN_STARTED carries user/tool messages for reconnect.
                context =
                        new AguiStreamContext(
                                threadId,
                                currentRunId,
                                config,
                                runInputForReplay(threadId, currentRunId, connectInput));
            }
            projected.addAll(registry.convert(stored.event(), context));
        }

        if (context != null) {
            projected.addAll(registry.enrich(null, context.finishPendingEvents(), context));
        }
        return suppressResolvedInterrupts(projected);
    }

    /**
     * Only the interrupt outcome of the last run is preserved; interrupt outcomes of earlier runs
     * that have been continued (and typically resolved) by a later run are replaced with
     * {@code null}, which serializes as a normal successful run.
     *
     * <p>The last run is determined from the run id of the final event of any kind, not just the
     * last {@code RUN_FINISHED}: a run that is still in progress has no {@code RUN_FINISHED} yet,
     * but its presence means the interrupted run has been continued, so its interrupt is resolved
     * and must be suppressed.
     *
     * <p>For suppressed runs, tool calls that started but never received a
     * {@code TOOL_CALL_RESULT} (the suspended tool of the interrupt) are closed with a synthetic
     * {@code TOOL_CALL_RESULT} right before the run's {@code RUN_FINISHED}, so the frontend
     * renders them as completed instead of stuck in a running state.
     *
     * @param events projected AG-UI events, in conversion order
     * @return events with resolved historical interrupts suppressed
     */
    static List<AguiEvent> suppressResolvedInterrupts(List<AguiEvent> events) {
        String lastRunId = null;
        for (AguiEvent event : events) {
            if (event.getRunId() != null) {
                lastRunId = event.getRunId();
            }
        }
        if (lastRunId == null) {
            return events;
        }
        Map<String, AguiEvent.RunFinished> suppressedRunFinished = new LinkedHashMap<>();
        for (AguiEvent event : events) {
            if (event instanceof AguiEvent.RunFinished runFinished
                    && !lastRunId.equals(runFinished.runId())
                    && runFinished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome) {
                suppressedRunFinished.put(runFinished.runId(), runFinished);
            }
        }
        if (suppressedRunFinished.isEmpty()) {
            return events;
        }
        Map<String, List<String>> danglingToolCalls =
                danglingToolCalls(events, suppressedRunFinished.keySet());

        boolean changed = false;
        List<AguiEvent> result = new ArrayList<>(events.size());
        for (AguiEvent event : events) {
            if (event instanceof AguiEvent.RunFinished runFinished
                    && suppressedRunFinished.containsKey(runFinished.runId())) {
                for (String toolCallId :
                        danglingToolCalls.getOrDefault(runFinished.runId(), List.of())) {
                    result.add(
                            new AguiEvent.ToolCallResult(
                                    runFinished.threadId(),
                                    runFinished.runId(),
                                    toolCallId,
                                    null,
                                    "tool",
                                    toolCallId));
                }
                result.add(
                        new AguiEvent.RunFinished(
                                runFinished.threadId(),
                                runFinished.runId(),
                                runFinished.result(),
                                null,
                                runFinished.timestamp(),
                                runFinished.rawEvent()));
                changed = true;
            } else {
                result.add(event);
            }
        }
        return changed ? List.copyOf(result) : events;
    }

    /**
     * For each suppressed run, collect the {@code toolCallId}s that started via
     * {@code TOOL_CALL_START} but never produced a {@code TOOL_CALL_RESULT}.
     */
    private static Map<String, List<String>> danglingToolCalls(
            List<AguiEvent> events, Set<String> suppressedRunIds) {
        Map<String, List<String>> started = new HashMap<>();
        Map<String, Set<String>> resulted = new HashMap<>();
        for (AguiEvent event : events) {
            String runId = event.getRunId();
            if (runId == null || !suppressedRunIds.contains(runId)) {
                continue;
            }
            if (event instanceof AguiEvent.ToolCallStart toolCallStart) {
                started.computeIfAbsent(runId, ignored -> new ArrayList<>())
                        .add(toolCallStart.toolCallId());
            } else if (event instanceof AguiEvent.ToolCallResult toolCallResult) {
                resulted.computeIfAbsent(runId, ignored -> new HashSet<>())
                        .add(toolCallResult.toolCallId());
            }
        }
        Map<String, List<String>> dangling = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : started.entrySet()) {
            Set<String> done = resulted.getOrDefault(entry.getKey(), Set.of());
            List<String> missing =
                    entry.getValue().stream()
                            .filter(toolCallId -> !done.contains(toolCallId))
                            .toList();
            if (!missing.isEmpty()) {
                dangling.put(entry.getKey(), missing);
            }
        }
        return dangling;
    }

    private AguiAdapterConfig replayConfig() {
        return AguiAdapterConfig.builder()
                .toolMergeMode(properties.getDefaultToolMergeMode())
                .runTimeout(properties.getRunTimeout())
                .emitStateEvents(properties.isEmitStateEvents())
                .emitToolCallArgs(properties.isEmitToolCallArgs())
                .emitTokenUsage(properties.isEmitTokenUsage())
                .enableReasoning(properties.isEnableReasoning())
                .defaultAgentId(properties.getDefaultAgentId())
                .eventConverters(eventConverters)
                .eventEnrichers(replayEnrichers)
                .build();
    }

    /**
     * Build the {@link RunAgentInput} attached to replayed {@code RUN_STARTED} events.
     *
     * <p>Stored per-run messages are the authoritative user/tool turns. Tools / state from the
     * connect payload are kept so converters that read them still work.
     */
    private RunAgentInput runInputForReplay(
            String threadId, String runId, RunAgentInput connectInput) {
        List<AguiMessage> storedMessages = eventStore.getRunInputMessages(threadId, runId);
        if (storedMessages.isEmpty() && connectInput == null) {
            return null;
        }
        RunAgentInput.Builder builder = RunAgentInput.builder().threadId(threadId).runId(runId);
        if (!storedMessages.isEmpty()) {
            builder.messages(storedMessages);
        } else if (connectInput != null) {
            builder.messages(connectInput.getMessages());
        }
        if (connectInput != null) {
            builder.tools(connectInput.getTools())
                    .context(connectInput.getContext())
                    .state(connectInput.getState())
                    .forwardedProps(connectInput.getForwardedProps())
                    .resume(connectInput.getResume());
        }
        return builder.build();
    }
}
