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
package io.agentscope.core.studio;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bridges agent streaming events to AgentScope Studio.
 *
 * <p>This component subscribes to a {@link reactor.core.publisher.Flux} of
 * {@link io.agentscope.core.agent.Event} instances produced by a
 * {@code StreamableAgent} and forwards them to Studio using both the HTTP
 * {@link StudioClient} and the WebSocket-based {@link StudioWebSocketClient}.
 *
 * <p>The bridge forwards REASONING, TOOL_RESULT, and SUMMARY events directly to the
 * Studio frontend via {@link StudioWebSocketClient#sendStreamEvent(Event)} so that
 * intermediate and final reasoning/tool outputs are visible in real time. Non-terminal
 * {@link EventType#AGENT_RESULT} events are also streamed as incremental chunks.
 *
 * <p>The final {@link EventType#AGENT_RESULT} event with {@code isLast == true} is treated as
 * a control signal: its {@link Msg} payload is cached for persistence and it triggers
 * {@link StudioWebSocketClient#sendStreamCompleted()} but is not forwarded again as a
 * streaming chunk. After the stream completes, the selected final {@link Msg} (terminal
 * AGENT_RESULT if present, otherwise the last non-terminal AGENT_RESULT) is persisted once
 * via {@link StudioClient#pushMessage(Msg)}.
 */
public class StudioStreamingBridge {

    private static final Logger logger = LoggerFactory.getLogger(StudioStreamingBridge.class);

    private final StudioClient studioClient;
    private final StudioWebSocketClient webSocketClient;

    /**
     * Creates a new streaming bridge for forwarding agent events to Studio.
     *
     * @param studioClient HTTP client used to persist the final agent message to Studio
     * @param webSocketClient WebSocket client used to stream incremental events and
     *     completion signals to the Studio frontend
     */
    public StudioStreamingBridge(StudioClient studioClient, StudioWebSocketClient webSocketClient) {
        this.studioClient = Objects.requireNonNull(studioClient, "studioClient must not be null");
        this.webSocketClient =
                Objects.requireNonNull(webSocketClient, "webSocketClient must not be null");
    }

    /**
     * Forwards a stream of agent events to Studio for real-time visualization and
     * final message persistence.
     *
     * <ol>
     *   <li>All REASONING, TOOL_RESULT, and SUMMARY events are forwarded to the frontend via
     *       {@link StudioWebSocketClient#sendStreamEvent(Event)} regardless of the {@code isLast}
     *       flag so that final reasoning/tool outputs remain visible even when emitted as
     *       terminal events.</li>
     *   <li>Non-terminal AGENT_RESULT events ({@code isLast == false}) are also forwarded as
     *       streaming chunks.</li>
     *   <li>The terminal AGENT_RESULT event with {@code isLast == true} is used to:
     *       <ul>
     *         <li>cache the final {@link Msg} for persistence, and</li>
     *         <li>trigger {@link StudioWebSocketClient#sendStreamCompleted()}.</li>
     *       </ul>
     *       It is not sent again as a streaming chunk.</li>
     *   <li>If a final {@link Msg} exists, it is persisted once via
     *       {@link StudioClient#pushMessage(Msg)} after the stream completes.</li>
     * </ol>
     *
     * <p>Note: {@code isLast} is treated purely as a control flag for AGENT_RESULT and does not
     * generate additional visible content.</p>
     */
    public Mono<Void> forwardToStudio(Flux<Event> eventFlux) {
        return Mono.defer(
                () -> {
                    FinalMsgHolder holder = new FinalMsgHolder();

                    return eventFlux
                            .doOnNext(event -> handleEvent(event, holder))
                            .doOnError(
                                    ex ->
                                            logger.error(
                                                    "Error occurred during streaming to Studio",
                                                    ex))
                            .doFinally(
                                    signalType -> {
                                        if (!holder.completedSignalSent) {
                                            try {
                                                webSocketClient.sendStreamCompleted();
                                            } catch (Exception e) {
                                                logger.error(
                                                        "Failed to send streamCompleted in"
                                                                + " doFinally",
                                                        e);
                                            }
                                            holder.completedSignalSent = true;
                                        }
                                    })
                            .then(
                                    Mono.defer(
                                            () -> {
                                                Msg finalMsg = holder.getEffectiveFinalMsg();
                                                if (finalMsg == null) {
                                                    logger.debug(
                                                            "No final Msg determined from stream;"
                                                                    + " skip pushMessage");
                                                    return Mono.empty();
                                                }
                                                return studioClient.pushMessage(finalMsg);
                                            }));
                });
    }

    /**
     * Handles individual events from the stream.
     */
    private void handleEvent(Event event, FinalMsgHolder holder) {
        if (event == null) {
            return;
        }

        EventType type = event.getType();
        Msg msg = event.getMessage();
        boolean isLast = event.isLast();

        if (type == EventType.AGENT_RESULT && msg != null) {
            holder.lastAgentResultMsg = msg;
        }

        if (type == EventType.AGENT_RESULT && isLast) {
            if (msg != null) {
                holder.terminalAgentResultMsg = msg;
            }
            if (!holder.completedSignalSent) {
                webSocketClient.sendStreamCompleted();
                holder.completedSignalSent = true;
            }
            return;
        }

        if (type == EventType.AGENT_RESULT && !isLast) {
            webSocketClient.sendStreamEvent(event);
            return;
        }

        if (type == EventType.REASONING
                || type == EventType.TOOL_RESULT
                || type == EventType.SUMMARY) {
            webSocketClient.sendStreamEvent(event);
        }
    }

    private static class FinalMsgHolder {
        private Msg terminalAgentResultMsg;
        private Msg lastAgentResultMsg;
        private boolean completedSignalSent;

        Msg getEffectiveFinalMsg() {
            if (terminalAgentResultMsg != null) {
                return terminalAgentResultMsg;
            }
            if (lastAgentResultMsg != null) {
                return lastAgentResultMsg;
            }
            return null;
        }
    }
}
