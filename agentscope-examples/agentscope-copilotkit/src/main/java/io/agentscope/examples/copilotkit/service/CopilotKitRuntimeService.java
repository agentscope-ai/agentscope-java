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
import io.agentscope.examples.copilotkit.model.CopilotKitModels.AgentInfo;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.InfoResponse;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.Intelligence;
import io.agentscope.examples.copilotkit.model.CopilotKitModels.ThreadEndpoints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * CopilotKit Runtime info and multi-route connect handshake with AgentEvent replay.
 *
 *
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
    private final AgentEventAguiReplayer eventReplayer;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public CopilotKitRuntimeService(
            AguiAgentRegistry aguiAgentRegistry, AgentEventAguiReplayer eventReplayer) {
        this.aguiAgentRegistry = aguiAgentRegistry;
        this.eventReplayer = eventReplayer;
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
     * AG-UI connect: replay persisted AgentEvents through converters, or emit an empty handshake.
     *
     * <p>History is stored as AgentScope {@code AgentEvent}s. On connect they are projected to
     * AG-UI frames with the same converter registry used by {@code /run}, so CopilotKit can
     * restore the conversation. Without history a minimal
     * {@code RUN_STARTED → MESSAGES_SNAPSHOT([]) → RUN_FINISHED} handshake is returned.
     */
    public Flux<ServerSentEvent<String>> connect(RunAgentInput input) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();
        List<AguiEvent> history = eventReplayer.replay(threadId, input);
        if (history.isEmpty()) {
            return Flux.fromIterable(emptyHandshake(threadId, runId)).map(this::sse);
        }
        return Flux.fromIterable(history).map(this::sse);
    }

    private List<AguiEvent> emptyHandshake(String threadId, String runId) {
        List<AguiEvent> events = new ArrayList<>(3);
        events.add(new AguiEvent.RunStarted(threadId, runId));
        events.add(new AguiEvent.MessagesSnapshot(threadId, runId, List.of()));
        events.add(new AguiEvent.RunFinished(threadId, runId));
        return events;
    }

    private ServerSentEvent<String> sse(AguiEvent event) {
        return ServerSentEvent.<String>builder().data(encoder.encodeToJson(event).trim()).build();
    }
}
