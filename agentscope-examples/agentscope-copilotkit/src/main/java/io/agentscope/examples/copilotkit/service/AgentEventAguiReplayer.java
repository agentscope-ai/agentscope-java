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
import java.util.List;
import java.util.Objects;
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
        return List.copyOf(projected);
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
