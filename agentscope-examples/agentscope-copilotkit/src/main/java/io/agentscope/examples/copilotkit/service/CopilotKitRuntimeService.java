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

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.store.AguiSnapshotHydrator;
import io.agentscope.core.agui.store.AguiSnapshotStore;
import io.agentscope.core.agui.store.AguiThreadSnapshot;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.AgentInfo;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.InfoResponse;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.Intelligence;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadEndpoints;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * CopilotKit Runtime info and multi-route connect hydrate backed by the framework presentation
 * snapshot store.
 */
@Service
public final class CopilotKitRuntimeService {

    private static final Map<String, Boolean> DEFAULT_CAPABILITIES =
            Map.of(
                    "threads", true,
                    "sharedState", true,
                    "frontendTools", true,
                    "humanInTheLoop", true);

    private final AguiAgentRegistry aguiAgentRegistry;
    private final ObjectProvider<AguiSnapshotStore> snapshotStoreProvider;
    private final AguiSnapshotHydrator hydrator = new AguiSnapshotHydrator();
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public CopilotKitRuntimeService(
            AguiAgentRegistry aguiAgentRegistry,
            ObjectProvider<AguiSnapshotStore> snapshotStoreProvider) {
        this.aguiAgentRegistry = aguiAgentRegistry;
        this.snapshotStoreProvider = snapshotStoreProvider;
    }

    public InfoResponse info() {
        Map<String, AgentInfo> agents = new LinkedHashMap<>();
        // Known demo agents registered in AgentConfiguration.
        for (String agentId : List.of("default", "chat", "calculator", "workbench")) {
            if (!aguiAgentRegistry.hasAgent(agentId)) {
                continue;
            }
            agents.put(agentId, resolveAgentInfo(agentId));
        }
        if (agents.isEmpty()) {
            agents.put(
                    DemoThreadStore.DEFAULT_AGENT_ID,
                    resolveAgentInfo(DemoThreadStore.DEFAULT_AGENT_ID));
        }

        return new InfoResponse(
                "2.0.0",
                agents,
                true,
                "sse",
                new ThreadEndpoints(true, true, true, false),
                true,
                new Intelligence("ws://127.0.0.1:8080/agui/run/threads/ws"),
                true,
                false,
                "valid",
                true);
    }

    private AgentInfo resolveAgentInfo(String agentId) {
        AgentInfo fallback =
                new AgentInfo(
                        agentId,
                        agentId,
                        "AgentScope AG-UI agent: " + agentId,
                        DEFAULT_CAPABILITIES,
                        "AgentScopeAgent");
        return aguiAgentRegistry
                .getAgent(agentId)
                .map(
                        agent ->
                                fallback.withIdentity(
                                        agent.getName(),
                                        agent.getDescription(),
                                        agent.getClass().getSimpleName()))
                .orElse(fallback);
    }

    /**
     * AG-UI connect: rebuild the visible conversation from the framework presentation snapshot
     * store.
     *
     * <p>Read-only: it looks up the stored snapshot for the thread and delegates to
     * {@link AguiSnapshotHydrator}. When the snapshot store is disabled (or the thread has no
     * history) the hydrator returns the minimal {@code RUN_STARTED → MESSAGES_SNAPSHOT([]) →
     * RUN_FINISHED} handshake. Only the trailing unresolved interrupt is ever replayed, so a
     * resolved historical interrupt can never reappear.
     */
    public Flux<ServerSentEvent<String>> connect(RunAgentInput input) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();
        AguiSnapshotStore store = snapshotStoreProvider.getIfAvailable();
        AguiThreadSnapshot snapshot = store != null ? store.find(threadId).orElse(null) : null;
        List<AguiEvent> frames = hydrator.hydrate(snapshot, threadId, runId);
        return Flux.fromIterable(frames).map(this::sse);
    }

    private ServerSentEvent<String> sse(AguiEvent event) {
        return ServerSentEvent.<String>builder().data(encoder.encodeToJson(event).trim()).build();
    }
}
