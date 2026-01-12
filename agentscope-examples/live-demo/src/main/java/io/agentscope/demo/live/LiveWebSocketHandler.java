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
package io.agentscope.demo.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.LiveAgent;
import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.model.DashScopeLiveModel;
import io.agentscope.core.live.model.LiveModel;
import io.agentscope.core.live.transport.JdkWebSocketClient;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * WebSocket handler for Live Demo.
 *
 * <p>Protocol:
 *
 * <ul>
 *   <li>Client → Server: JSON with type and data fields
 *   <li>Server → Client: JSON with type and event data
 * </ul>
 *
 * <p>Client message types:
 *
 * <ul>
 *   <li>audio - Audio data (Base64 encoded PCM 16kHz mono)
 *   <li>text - Text message
 *   <li>interrupt - Interrupt current response
 * </ul>
 *
 * <p>Server message types:
 *
 * <ul>
 *   <li>SESSION_CREATED, SESSION_UPDATED - Session lifecycle events
 *   <li>TEXT_DELTA, AUDIO_DELTA - Incremental response data
 *   <li>INPUT_TRANSCRIPTION, OUTPUT_TRANSCRIPTION - Transcription events
 *   <li>TURN_COMPLETE - Response turn completed
 *   <li>ERROR - Error event
 * </ul>
 */
public class LiveWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String modelName;
    private final String apiKey;
    private final String agentName;
    private final String systemPrompt;
    private final String voice;

    public LiveWebSocketHandler(
            String modelName, String apiKey, String agentName, String systemPrompt, String voice) {
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.agentName = agentName;
        this.systemPrompt = systemPrompt;
        this.voice = voice;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket connection established: {}", session.getId());

        // Validate API key
        if (apiKey == null || apiKey.isBlank()) {
            return sendError(session, "API key not configured").then(session.close());
        }

        // Parse VAD parameter from query string
        boolean vadEnabled = true; // default to VAD enabled
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query != null && query.contains("vad=false")) {
            vadEnabled = false;
        }
        log.info("VAD enabled: {}", vadEnabled);

        // Create LiveModel and LiveAgent
        LiveModel liveModel;
        try {
            liveModel =
                    DashScopeLiveModel.builder()
                            .apiKey(apiKey)
                            .modelName(modelName)
                            .vadEnabled(vadEnabled)
                            .webSocketClient(JdkWebSocketClient.create())
                            .build();
        } catch (Exception e) {
            log.error("Failed to create LiveModel", e);
            return sendError(session, "Failed to create model: " + e.getMessage())
                    .then(session.close());
        }

        LiveAgent agent =
                LiveAgent.builder()
                        .name(agentName)
                        .systemPrompt(systemPrompt)
                        .liveModel(liveModel)
                        .build();

        // Create input sink for user messages
        Sinks.Many<Msg> inputSink = Sinks.many().multicast().onBackpressureBuffer(256);

        // Create LiveConfig
        LiveConfig config =
                LiveConfig.builder()
                        .voice(voice)
                        .enableInputTranscription(true)
                        .enableOutputTranscription(true)
                        .autoReconnect(true)
                        .build();

        // Start live session and get event stream
        Flux<LiveEvent> eventStream = agent.live(inputSink.asFlux(), config);

        // Convert events to WebSocket messages
        Flux<WebSocketMessage> outbound =
                eventStream
                        .doOnNext(event -> log.debug("Sending event to client: {}", event.type()))
                        .map(this::eventToJson)
                        .map(session::textMessage)
                        .onErrorResume(
                                error -> {
                                    log.error("Error in event stream", error);
                                    return Flux.just(
                                            session.textMessage(
                                                    createErrorJson(error.getMessage())));
                                });

        // Process incoming messages
        Flux<Void> inbound =
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .flatMap(
                                json -> {
                                    try {
                                        Msg msg = parseClientMessage(json);
                                        if (msg != null) {
                                            inputSink.tryEmitNext(msg);
                                        }
                                    } catch (Exception e) {
                                        log.warn(
                                                "Failed to parse client message: {}",
                                                e.getMessage());
                                    }
                                    return Mono.empty();
                                })
                        .doOnComplete(
                                () -> {
                                    log.info("Client disconnected: {}", session.getId());
                                    inputSink.tryEmitComplete();
                                })
                        .then()
                        .flux();

        // Send outbound messages and process inbound concurrently
        return Mono.zip(session.send(outbound), inbound.then()).then();
    }

    private Msg parseClientMessage(String json) throws JsonProcessingException {
        ClientMessage message = objectMapper.readValue(json, ClientMessage.class);
        return message.toMsg();
    }

    private String eventToJson(LiveEvent event) {
        try {
            return objectMapper.writeValueAsString(ServerEvent.from(event));
        } catch (JsonProcessingException e) {
            return createErrorJson("Failed to serialize event");
        }
    }

    private String createErrorJson(String message) {
        try {
            return objectMapper.writeValueAsString(ServerEvent.error(message));
        } catch (JsonProcessingException e) {
            return "{\"type\":\"ERROR\",\"message\":\"Serialization error\"}";
        }
    }

    private Mono<Void> sendError(WebSocketSession session, String message) {
        return session.send(Mono.just(session.textMessage(createErrorJson(message))));
    }
}
