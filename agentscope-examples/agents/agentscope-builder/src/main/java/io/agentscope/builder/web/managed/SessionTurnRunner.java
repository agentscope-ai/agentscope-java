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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
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
    private final ConcurrentHashMap<String, Disposable> activeTurns = new ConcurrentHashMap<>();

    public SessionTurnRunner(
            AgentCatalogService catalogService,
            @Lazy ManagedSessionService sessionService,
            SessionEventLog eventLog) {
        this.catalogService = catalogService;
        this.sessionService = sessionService;
        this.eventLog = eventLog;
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

    /** Cancels an in-flight turn for the given managed session, if any. */
    public void interrupt(String sessionId) {
        Disposable disposable = activeTurns.remove(sessionId);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void runTurn(ManagedSessionDto session, String userMessage) {
        HarnessAgent agent =
                catalogService.getOrInstantiateRunningAgent(session.ownerId(), session.agentId());
        if (agent == null) {
            throw new IllegalStateException("Agent not available: " + session.agentId());
        }

        RuntimeContext rc =
                RuntimeContext.builder().userId(session.ownerId()).sessionId(session.id()).build();

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(userMessage).build();
        Flux<AgentEvent> stream = agent.streamEvents(List.of(userMsg), rc);

        Disposable subscription =
                stream.subscribe(
                        event -> handleAgentEvent(session.id(), event),
                        error -> {
                            activeTurns.remove(session.id());
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
                            sessionService.updateStatus(
                                    session.ownerId(),
                                    session.id(),
                                    ManagedSessionService.STATUS_IDLE,
                                    null);
                        });

        activeTurns.put(session.id(), subscription);
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
        if (event instanceof ToolResultEndEvent toolResult) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolCallId", toolResult.getToolCallId());
            payload.put("toolName", toolResult.getToolCallName());
            if (toolResult.getState() != null) {
                payload.put("state", toolResult.getState().name());
            }
            eventLog.append(sessionId, "agent.tool_result", payload);
        }
    }
}
