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

import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Executes a managed session turn against the harness agent and records session events. */
@Service
public class SessionTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionTurnRunner.class);

    private final AgentCatalogService catalogService;
    private final ManagedSessionService sessionService;
    private final SessionEventLog eventLog;
    private final EnvironmentService environmentService;
    private final MemoryMountService memoryMountService;
    private final ConcurrentHashMap<String, Disposable> activeTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HarnessAgent> activeAgents = new ConcurrentHashMap<>();

    public SessionTurnRunner(
            AgentCatalogService catalogService,
            @Lazy ManagedSessionService sessionService,
            SessionEventLog eventLog,
            EnvironmentService environmentService,
            MemoryMountService memoryMountService) {
        this.catalogService = catalogService;
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.environmentService = environmentService;
        this.memoryMountService = memoryMountService;
    }

    /** Runs a turn asynchronously so inbound HTTP handlers can return quickly. */
    public void runTurnAsync(ManagedSessionDto session, String userMessage) {
        Schedulers.boundedElastic()
                .schedule(
                        () -> {
                            try {
                                runTurn(session, userMessage);
                            } catch (Exception ex) {
                                log.warn(
                                        "Managed session turn failed: sessionId={}, error={}",
                                        session.id(),
                                        ex.getMessage());
                                Map<String, Object> stopReason = new LinkedHashMap<>();
                                stopReason.put("error", ex.getMessage());
                                sessionService.updateStatus(
                                        session.ownerId(),
                                        session.id(),
                                        ManagedSessionService.STATUS_ERRORED,
                                        stopReason);
                            }
                        });
    }

    /**
     * Cancels an in-flight turn for the given managed session: disposes the Reactor subscription
     * and invokes {@link HarnessAgent#interrupt()}.
     */
    public void interrupt(String sessionId) {
        HarnessAgent agent = activeAgents.remove(sessionId);
        if (agent != null) {
            try {
                agent.interrupt();
            } catch (Exception ex) {
                log.warn("Harness interrupt failed for {}: {}", sessionId, ex.getMessage());
            }
        }
        Disposable disposable = activeTurns.remove(sessionId);
        if (disposable != null) {
            disposable.dispose();
        }
        eventLog.append(sessionId, "session.interrupted", Map.of("status", "interrupted"));
    }

    private void runTurn(ManagedSessionDto session, String userMessage) {
        EnvironmentDto environment = null;
        if (session.environmentId() != null) {
            environment = environmentService.get(session.ownerId(), session.environmentId());
        }
        SessionAgentBuildSpec spec =
                new SessionAgentBuildSpec(
                        session.agentVersion(),
                        session.environmentId(),
                        environment,
                        session.agentOverridesJson(),
                        session.memoryStoreIds(),
                        session.vaultIds());

        HarnessAgent agent =
                catalogService.getOrInstantiateRunningAgent(
                        session.ownerId(), session.agentId(), spec);
        if (agent == null) {
            throw new IllegalStateException("Agent not available: " + session.agentId());
        }

        Path workspace =
                catalogService.resolveAgentWorkspace(
                        session.agentOwnerId() != null ? session.agentOwnerId() : session.ownerId(),
                        session.agentId());
        // Re-materialize memory mounts for this turn (agent may have been cached).
        memoryMountService.materialize(session.ownerId(), workspace, session.memoryStoreIds());

        RuntimeContext rc =
                RuntimeContext.builder().userId(session.ownerId()).sessionId(session.id()).build();

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(userMessage).build();
        Flux<AgentEvent> stream = agent.streamEvents(List.of(userMsg), rc);

        activeAgents.put(session.id(), agent);
        Disposable subscription =
                stream.subscribe(
                        event -> handleAgentEvent(session.id(), event),
                        error -> {
                            activeTurns.remove(session.id());
                            activeAgents.remove(session.id());
                            writebackMemory(session, workspace);
                            Map<String, Object> stopReason = new LinkedHashMap<>();
                            stopReason.put("error", error.getMessage());
                            sessionService.updateStatus(
                                    session.ownerId(),
                                    session.id(),
                                    ManagedSessionService.STATUS_ERRORED,
                                    stopReason);
                        },
                        () -> {
                            activeTurns.remove(session.id());
                            activeAgents.remove(session.id());
                            writebackMemory(session, workspace);
                            sessionService.updateStatus(
                                    session.ownerId(),
                                    session.id(),
                                    ManagedSessionService.STATUS_IDLE,
                                    null);
                        });

        activeTurns.put(session.id(), subscription);
    }

    private void writebackMemory(ManagedSessionDto session, Path workspace) {
        try {
            memoryMountService.writeback(session.ownerId(), workspace, session.memoryStoreIds());
        } catch (Exception ex) {
            log.warn("Memory writeback failed for session {}: {}", session.id(), ex.getMessage());
        }
    }

    private void handleAgentEvent(String sessionId, AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                eventLog.append(
                        sessionId,
                        "agent.message",
                        Map.of("delta", delta.getDelta(), "streaming", true));
            }
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            if (thinking.getDelta() != null && !thinking.getDelta().isEmpty()) {
                eventLog.append(
                        sessionId,
                        "agent.reasoning",
                        Map.of("delta", thinking.getDelta(), "streaming", true));
            }
            return;
        }
        if (event instanceof AgentResultEvent result) {
            String text =
                    result.getResult() != null && result.getResult().getTextContent() != null
                            ? result.getResult().getTextContent()
                            : "";
            eventLog.append(sessionId, "agent.message", Map.of("text", text));
            return;
        }
        if (event instanceof ToolCallStartEvent toolUse) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolCallId", toolUse.getToolCallId());
            payload.put("toolName", toolUse.getToolCallName());
            eventLog.append(sessionId, "agent.tool_use", payload);
            return;
        }
        if (event instanceof ToolCallDeltaEvent toolDelta) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolCallId", toolDelta.getToolCallId());
            if (toolDelta.getDelta() != null) {
                payload.put("delta", toolDelta.getDelta());
            }
            eventLog.append(sessionId, "agent.tool_use_delta", payload);
            return;
        }
        if (event instanceof ToolResultEndEvent toolResult) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolCallId", toolResult.getToolCallId());
            payload.put("toolName", toolResult.getToolCallName());
            if (toolResult.getState() != null) {
                payload.put("state", toolResult.getState().name());
            }
            eventLog.append(sessionId, "agent.tool_result", payload);
            return;
        }
        if (event instanceof ModelCallStartEvent) {
            eventLog.append(sessionId, "agent.model_call_start", Map.of());
            return;
        }
        if (event instanceof ModelCallEndEvent modelEnd) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (modelEnd.getUsage() != null) {
                payload.put("usage", modelEnd.getUsage());
            }
            eventLog.append(sessionId, "agent.model_call_end", payload);
            return;
        }
        if (event instanceof AgentStartEvent) {
            eventLog.append(sessionId, "session.agent_start", Map.of());
            return;
        }
        if (event instanceof AgentEndEvent) {
            eventLog.append(sessionId, "session.agent_end", Map.of());
        }
    }
}
