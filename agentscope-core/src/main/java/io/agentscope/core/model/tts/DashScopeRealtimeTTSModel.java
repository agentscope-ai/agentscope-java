/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.tts;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.model.transport.WebSocketTransport;
import io.agentscope.core.model.transport.websocket.JdkWebSocketTransport;
import io.agentscope.core.model.transport.websocket.WebSocketConnection;
import io.agentscope.core.model.transport.websocket.WebSocketRequest;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * DashScope Realtime TTS Model with WebSocket streaming input support.
 *
 * <p>This model uses DashScope's WebSocket-based TTS API via WebSocketTransport,
 * enabling true streaming input where text can be pushed incrementally while
 * maintaining context continuity for natural prosody and intonation.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>WebSocket-based streaming with WebSocketTransport</li>
 *   <li>Supports both {@code server_commit} and {@code commit} modes</li>
 *   <li>{@code push(text)} - Push text incrementally, TTS maintains context</li>
 *   <li>{@code finish()} - Signal end of input, get remaining audio</li>
 *   <li>Solves prosody/intonation issues that occur with independent HTTP requests</li>
 *   <li>No 600-character limit per request (streaming input)</li>
 * </ul>
 *
 * <p><b>Session Modes:</b>
 * <ul>
 *   <li>{@code server_commit} - Server automatically commits text buffer for synthesis</li>
 *   <li>{@code commit} - Client must manually commit text buffer</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * DashScopeRealtimeTTSModel tts = DashScopeRealtimeTTSModel.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen3-tts-flash-realtime")
 *     .voice("Cherry")
 *     .mode(SessionMode.SERVER_COMMIT)
 *     .build();
 *
 * // Start a streaming session
 * tts.startSession();
 *
 * // Push text chunks as LLM generates them (context is maintained!)
 * tts.push("Hello, ").subscribe(audio -> player.play(audio));
 * tts.push("welcome to ").subscribe(audio -> player.play(audio));
 * tts.push("AgentScope.").subscribe(audio -> player.play(audio));
 *
 * // Finish and get remaining audio
 * tts.finish().subscribe(audio -> player.play(audio));
 * }</pre>
 */
public class DashScopeRealtimeTTSModel implements RealtimeTTSModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRealtimeTTSModel.class);

    /** WebSocket URL for DashScope realtime TTS API. */
    private static final String WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";

    private final String apiKey;
    private final String modelName;
    private final String voice;
    private final int sampleRate;
    private final String format;
    private final SessionMode mode;
    private final String languageType;

    // WebSocket client and state
    private final WebSocketTransport webSocketTransport;
    private WebSocketConnection<String> connection;
    private final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private Sinks.Many<AudioBlock> audioSink;
    private Mono<Void> messageReceiver;

    // Response tracking
    private final AtomicBoolean isResponding = new AtomicBoolean(false);
    private CompletableFuture<Void> responseDoneFuture;
    private String currentResponseId;
    private String currentItemId;

    /**
     * Session mode for TTS.
     */
    public enum SessionMode {
        /** Server automatically commits text buffer for synthesis. */
        SERVER_COMMIT("server_commit"),
        /** Client must manually commit text buffer. */
        COMMIT("commit");

        private final String value;

        SessionMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private DashScopeRealtimeTTSModel(Builder builder) {
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.voice = builder.voice;
        this.sampleRate = builder.sampleRate;
        this.format = builder.format;
        this.mode = builder.mode;
        this.languageType = builder.languageType;
        this.webSocketTransport = JdkWebSocketTransport.create();
    }

    /**
     * Returns true if this model supports streaming input (push/finish pattern).
     *
     * @return always true for this implementation
     */
    public boolean supportsStreamingInput() {
        return true;
    }

    /**
     * Starts a new TTS session with WebSocket connection.
     *
     * <p>This establishes a WebSocket connection to DashScope's TTS service.
     * Call this before using push()/finish() pattern.
     */
    @Override
    public void startSession() {
        if (sessionActive.compareAndSet(false, true)) {
            audioSink = Sinks.many().multicast().onBackpressureBuffer();
            responseDoneFuture = new CompletableFuture<>();

            try {
                WebSocketRequest request =
                        WebSocketRequest.builder(WEBSOCKET_URL + "?model=" + modelName)
                                .header("Authorization", "Bearer " + apiKey)
                                .connectTimeout(Duration.ofSeconds(30))
                                .build();

                connection =
                        webSocketTransport
                                .connect(request, String.class)
                                .doOnSuccess(conn -> log.debug("TTS WebSocket connection opened"))
                                .doOnError(
                                        error ->
                                                log.error(
                                                        "Failed to connect to TTS WebSocket: {}",
                                                        error.getMessage()))
                                .block();

                if (connection == null) {
                    sessionActive.set(false);
                    throw new TTSException("Failed to establish WebSocket connection");
                }

                // Start message receiver in background
                startMessageReceiver();

                // Configure the session
                updateSession();

                log.debug("TTS WebSocket session started");
            } catch (Exception e) {
                sessionActive.set(false);
                log.error("Failed to start TTS session: {}", e.getMessage());
                throw new TTSException("Failed to start TTS session", e);
            }
        }
    }

    /**
     * Starts the message receiver to process incoming WebSocket messages.
     */
    private void startMessageReceiver() {
        if (connection == null) {
            return;
        }

        messageReceiver =
                connection
                        .receive()
                        .doOnNext(this::processMessage)
                        .doOnError(
                                error ->
                                        log.error(
                                                "Error receiving WebSocket message: {}",
                                                error.getMessage()))
                        .doOnComplete(() -> log.debug("WebSocket message receiver completed"))
                        .then();

        // Subscribe in background
        messageReceiver.subscribe();
    }

    /**
     * Processes a received WebSocket message.
     *
     * @param message the JSON message from server
     */
    private void processMessage(String message) {
        try {
            RealtimeTTSResponseEvent event =
                    JsonUtils.getJsonCodec().fromJson(message, RealtimeTTSResponseEvent.class);
            String eventType = event.getType();

            if (!"response.audio.delta".equals(eventType)) {
                log.debug("Received event: {}", eventType);
            }

            switch (eventType) {
                case "error":
                    RealtimeTTSResponseEvent.ErrorEvent errorEvent =
                            (RealtimeTTSResponseEvent.ErrorEvent) event;
                    String errMsg =
                            errorEvent.getError() != null
                                    ? errorEvent.getError().toString()
                                    : "Unknown error";
                    log.error("TTS API error: {}", errMsg);
                    if (audioSink != null) {
                        audioSink.tryEmitError(new TTSException("TTS API error: " + errMsg));
                    }
                    break;

                case "session.created":
                    RealtimeTTSResponseEvent.SessionCreatedEvent sessionCreatedEvent =
                            (RealtimeTTSResponseEvent.SessionCreatedEvent) event;
                    String sessionId =
                            sessionCreatedEvent.getSession() != null
                                    ? sessionCreatedEvent.getSession().getId()
                                    : null;
                    log.debug("Session created: {}", sessionId);
                    break;

                case "session.updated":
                    log.debug("Session updated");
                    break;

                case "input_text_buffer.committed":
                    RealtimeTTSResponseEvent.InputTextBufferCommittedEvent committedEvent =
                            (RealtimeTTSResponseEvent.InputTextBufferCommittedEvent) event;
                    log.debug("Text buffer committed, item_id: {}", committedEvent.getItemId());
                    break;

                case "input_text_buffer.cleared":
                    log.debug("Text buffer cleared");
                    break;

                case "response.created":
                    RealtimeTTSResponseEvent.ResponseCreatedEvent responseCreatedEvent =
                            (RealtimeTTSResponseEvent.ResponseCreatedEvent) event;
                    currentResponseId =
                            responseCreatedEvent.getResponse() != null
                                    ? responseCreatedEvent.getResponse().getId()
                                    : null;
                    isResponding.set(true);
                    responseDoneFuture = new CompletableFuture<>();
                    log.debug("Response created: {}", currentResponseId);
                    break;

                case "response.output_item.added":
                    RealtimeTTSResponseEvent.ResponseOutputItemAddedEvent itemAddedEvent =
                            (RealtimeTTSResponseEvent.ResponseOutputItemAddedEvent) event;
                    currentItemId =
                            itemAddedEvent.getItem() != null
                                    ? itemAddedEvent.getItem().getId()
                                    : null;
                    log.debug("Output item added: {}", currentItemId);
                    break;

                case "response.output_item.done":
                    log.debug("Output item done");
                    break;

                case "response.content_part.added":
                    RealtimeTTSResponseEvent.ResponseContentPartAddedEvent contentPartEvent =
                            (RealtimeTTSResponseEvent.ResponseContentPartAddedEvent) event;
                    String contentPartId =
                            contentPartEvent.getContentPart() != null
                                    ? contentPartEvent.getContentPart().getId()
                                    : null;
                    log.debug("Content part added: {}", contentPartId);
                    break;

                case "response.content_part.done":
                    log.debug("Content part done");
                    break;

                case "response.audio.delta":
                    RealtimeTTSResponseEvent.ResponseAudioDeltaEvent audioDeltaEvent =
                            (RealtimeTTSResponseEvent.ResponseAudioDeltaEvent) event;
                    String base64Audio = audioDeltaEvent.getDelta();
                    if (base64Audio != null && !base64Audio.isEmpty()) {
                        AudioBlock audioBlock =
                                AudioBlock.builder()
                                        .source(
                                                Base64Source.builder()
                                                        .mediaType("audio/" + format)
                                                        .data(base64Audio)
                                                        .build())
                                        .build();
                        if (audioSink != null) {
                            Sinks.EmitResult result = audioSink.tryEmitNext(audioBlock);
                            if (result.isFailure()) {
                                // Use debug level - this can happen if subscriber cancelled
                                log.debug(
                                        "Failed to emit audio (sink may be cancelled): {}", result);
                            }
                        }
                    }
                    break;

                case "response.audio.done":
                    log.debug("Audio generation completed");
                    break;

                case "response.done":
                    isResponding.set(false);
                    currentResponseId = null;
                    currentItemId = null;
                    if (responseDoneFuture != null && !responseDoneFuture.isDone()) {
                        responseDoneFuture.complete(null);
                    }
                    log.debug("Response completed");
                    break;

                case "session.finished":
                    log.debug("Session finished");
                    if (audioSink != null) {
                        audioSink.tryEmitComplete();
                    }
                    break;

                default:
                    // Handle unknown event types gracefully
                    if (event instanceof RealtimeTTSResponseEvent.UnknownEvent) {
                        log.warn("Received unknown event type: {}", eventType);
                    } else {
                        log.warn("Unhandled event type: {}", eventType);
                    }
                    break;
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message: {}", e.getMessage());
            if (audioSink != null) {
                audioSink.tryEmitError(
                        new TTSException("Error processing message: " + e.getMessage(), e));
            }
        }
    }

    /**
     * Updates the session configuration.
     */
    private void updateSession() {
        SessionConfig sessionConfig =
                SessionConfig.builder()
                        .mode(mode.getValue())
                        .voice(voice)
                        .languageType(languageType)
                        .responseFormat(format)
                        .sampleRate(sampleRate)
                        .build();

        TTSEvent.SessionUpdateEvent event =
                new TTSEvent.SessionUpdateEvent(generateEventId(), sessionConfig);

        sendEvent(event);
        log.debug("Session update sent: {}", sessionConfig);
    }

    /**
     * Generates a unique event ID.
     */
    private String generateEventId() {
        return "event_" + System.currentTimeMillis();
    }

    /**
     * Sends an event to the WebSocket.
     */
    private void sendEvent(TTSEvent event) {
        if (connection == null || !connection.isOpen()) {
            throw new TTSException("WebSocket not connected");
        }
        try {
            String json = JsonUtils.getJsonCodec().toJson(event);
            log.debug("Sending event: type={}, event_id={}", event.getType(), event.getEventId());
            connection.send(json).subscribe();
        } catch (JsonException e) {
            throw new TTSException("Failed to serialize event", e);
        }
    }

    /**
     * Pushes a text chunk for synthesis.
     *
     * <p>Unlike HTTP-based TTS, this maintains context continuity across
     * multiple push() calls, resulting in natural prosody and intonation.
     *
     * <p>Note: This method returns empty Flux. Audio is delivered through
     * {@link #getAudioStream()} to avoid duplicate subscriptions causing
     * repeated audio playback.
     *
     * @param text the text chunk to synthesize
     * @return empty Flux (audio delivered via getAudioStream)
     */
    @Override
    public Flux<AudioBlock> push(String text) {
        // Check for null/empty text first to avoid starting session unnecessarily
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }

        if (!sessionActive.get()) {
            startSession();
        }

        // Append text to the WebSocket session
        appendText(text);

        // Return empty - audio is delivered through getAudioStream()
        return Flux.empty();
    }

    /**
     * Appends text to the input buffer.
     *
     * @param text the text to append
     */
    private void appendText(String text) {
        TTSEvent.AppendTextEvent event = new TTSEvent.AppendTextEvent(generateEventId(), text);
        sendEvent(event);
    }

    /**
     * Commits the text buffer to trigger processing.
     *
     * <p>Only needed in {@link SessionMode#COMMIT} mode.
     * In {@link SessionMode#SERVER_COMMIT} mode, the server commits automatically.
     */
    public void commitTextBuffer() {
        TTSEvent.CommitEvent event = new TTSEvent.CommitEvent(generateEventId());
        sendEvent(event);
        log.debug("Text buffer committed");
    }

    /**
     * Clears the text buffer.
     */
    public void clearTextBuffer() {
        TTSEvent.ClearEvent event = new TTSEvent.ClearEvent(generateEventId());
        sendEvent(event);
        log.debug("Text buffer cleared");
    }

    /**
     * Finishes the streaming session and retrieves remaining audio.
     *
     * <p>This signals the end of input and waits for all audio to be generated.
     * The sink will be completed when the server sends the "session.finished" event.
     *
     * @return Flux of remaining AudioBlock chunks
     */
    @Override
    public Flux<AudioBlock> finish() {
        if (!sessionActive.get() || connection == null) {
            return Flux.empty();
        }

        // Send session finish event
        TTSEvent.FinishEvent event = new TTSEvent.FinishEvent(generateEventId());
        sendEvent(event);

        sessionActive.set(false);
        log.debug("TTS session finish requested");

        // Don't complete the sink here - let the message receiver complete it
        // when it receives the "session.finished" event from the server.
        // This ensures all audio data is received before completing.

        return audioSink != null ? audioSink.asFlux() : Flux.empty();
    }

    /**
     * Waits for the current response to complete.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return true if the response completed within the timeout
     */
    public boolean waitForResponseDone(long timeout, TimeUnit unit) {
        if (responseDoneFuture == null) {
            return true;
        }
        try {
            responseDoneFuture.get(timeout, unit);
            return true;
        } catch (Exception e) {
            log.warn("Timeout waiting for response done: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Closes the WebSocket connection and releases resources.
     */
    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close().block(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("Error closing WebSocket: {}", e.getMessage());
            }
            connection = null;
        }
        sessionActive.set(false);
        if (audioSink != null) {
            audioSink.tryEmitComplete();
        }
    }

    /**
     * Gets the audio stream for listening to all audio chunks.
     *
     * @return Flux of AudioBlock that emits audio as it's synthesized
     */
    @Override
    public Flux<AudioBlock> getAudioStream() {
        return audioSink != null ? audioSink.asFlux() : Flux.empty();
    }

    /**
     * Synthesizes complete text to audio using streaming.
     *
     * <p>This is a convenience method that creates a session, pushes all text,
     * and returns the complete audio stream.
     *
     * @param text the complete text to synthesize
     * @return Flux of AudioBlock chunks as they are generated
     */
    @Override
    public Flux<AudioBlock> synthesizeStream(String text) {
        Sinks.Many<AudioBlock> streamSink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<WebSocketConnection<String>> connectionRef = new AtomicReference<>();

        WebSocketRequest request =
                WebSocketRequest.builder(WEBSOCKET_URL + "?model=" + modelName)
                        .header("Authorization", "Bearer " + apiKey)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();

        return webSocketTransport
                .connect(request, String.class)
                .flatMapMany(
                        conn -> {
                            connectionRef.set(conn);

                            // Setup message receiver
                            StreamEventProcessor eventProcessor =
                                    new StreamEventProcessor(conn, streamSink, text);
                            Mono<Void> receiver =
                                    conn.receive().doOnNext(eventProcessor::processMessage).then();
                            receiver.subscribe();

                            // Send session update
                            sendStreamSessionUpdate(conn, streamSink);

                            return streamSink.asFlux();
                        })
                .doOnCancel(
                        () -> {
                            log.debug("Stream cancelled, closing WebSocket");
                            WebSocketConnection<String> ws = connectionRef.get();
                            if (ws != null && ws.isOpen()) {
                                ws.close().subscribe();
                            }
                        })
                .doOnTerminate(() -> log.debug("Stream terminated"));
    }

    /**
     * Sends session update event for streaming synthesis.
     *
     * @param conn the WebSocket connection
     * @param streamSink the sink for emitting errors
     */
    private void sendStreamSessionUpdate(
            WebSocketConnection<String> conn, Sinks.Many<AudioBlock> streamSink) {
        SessionConfig sessionConfig =
                SessionConfig.builder()
                        .mode(mode.getValue())
                        .voice(voice)
                        .languageType(languageType)
                        .responseFormat(format)
                        .sampleRate(sampleRate)
                        .build();

        TTSEvent.SessionUpdateEvent event =
                new TTSEvent.SessionUpdateEvent(generateEventId(), sessionConfig);

        try {
            conn.send(JsonUtils.getJsonCodec().toJson(event)).subscribe();
        } catch (JsonException e) {
            log.error("Failed to send session update: {}", e.getMessage());
            streamSink.tryEmitError(new TTSException("Failed to send session update", e));
        }
    }

    /**
     * Sends an event to the WebSocket connection.
     *
     * @param conn the WebSocket connection
     * @param event the event to send
     */
    private void sendStreamEvent(WebSocketConnection<String> conn, TTSEvent event) {
        conn.send(JsonUtils.getJsonCodec().toJson(event)).subscribe();
    }

    /**
     * Event processor for streaming synthesis WebSocket messages.
     */
    private class StreamEventProcessor {
        private final WebSocketConnection<String> connection;
        private final Sinks.Many<AudioBlock> sink;
        private final String textToSynthesize;

        StreamEventProcessor(
                WebSocketConnection<String> connection,
                Sinks.Many<AudioBlock> sink,
                String textToSynthesize) {
            this.connection = connection;
            this.sink = sink;
            this.textToSynthesize = textToSynthesize;
        }

        /**
         * Processes a WebSocket message event.
         *
         * @param message the JSON message from server
         */
        void processMessage(String message) {
            try {
                RealtimeTTSResponseEvent event =
                        JsonUtils.getJsonCodec().fromJson(message, RealtimeTTSResponseEvent.class);
                String eventType = event.getType();

                if (!"response.audio.delta".equals(eventType)) {
                    log.debug("Received event: {}", eventType);
                }

                switch (eventType) {
                    case "error":
                        handleError((RealtimeTTSResponseEvent.ErrorEvent) event);
                        break;
                    case "session.created":
                        handleSessionCreated((RealtimeTTSResponseEvent.SessionCreatedEvent) event);
                        break;
                    case "session.updated":
                        handleSessionUpdated();
                        break;
                    case "response.output_item.done":
                        // Output item done - no special handling needed, just log
                        log.debug("Output item done event received");
                        break;
                    case "response.content_part.added":
                        // Content part added - no special handling needed, just log
                        log.debug("Content part added event received");
                        break;
                    case "response.content_part.done":
                        // Content part done - no special handling needed, just log
                        log.debug("Content part done event received");
                        break;
                    case "response.audio.delta":
                        handleAudioDelta((RealtimeTTSResponseEvent.ResponseAudioDeltaEvent) event);
                        break;
                    case "response.audio.done":
                        log.debug("Audio generation completed");
                        break;
                    case "response.done":
                        log.debug("Response completed");
                        break;
                    case "session.finished":
                        handleSessionFinished();
                        break;
                    default:
                        // Handle unknown event types gracefully
                        if (event instanceof RealtimeTTSResponseEvent.UnknownEvent) {
                            log.debug("Received unknown event type: {}", eventType);
                        } else {
                            log.debug("Unhandled event type: {}", eventType);
                        }
                        break;
                }
            } catch (Exception e) {
                log.error("Error processing message: {}", e.getMessage());
                sink.tryEmitError(
                        new TTSException("Error processing message: " + e.getMessage(), e));
            }
        }

        private void handleError(RealtimeTTSResponseEvent.ErrorEvent event) {
            String errMsg =
                    event.getError() != null ? event.getError().toString() : "Unknown error";
            log.error("TTS API error: {}", errMsg);
            sink.tryEmitError(new TTSException("TTS API error: " + errMsg));
        }

        private void handleSessionCreated(RealtimeTTSResponseEvent.SessionCreatedEvent event) {
            String sessionId = event.getSession() != null ? event.getSession().getId() : null;
            log.debug("Session created: {}", sessionId);
        }

        private void handleSessionUpdated() {
            log.debug("Session updated, sending text");
            sendAppendTextEvent();
            if (mode == SessionMode.COMMIT) {
                sendCommitEvent();
            }
            sendFinishEvent();
        }

        private void sendAppendTextEvent() {
            TTSEvent.AppendTextEvent event =
                    new TTSEvent.AppendTextEvent(generateEventId(), textToSynthesize);
            sendStreamEvent(connection, event);
        }

        private void sendCommitEvent() {
            TTSEvent.CommitEvent event = new TTSEvent.CommitEvent(generateEventId());
            sendStreamEvent(connection, event);
        }

        private void sendFinishEvent() {
            TTSEvent.FinishEvent event = new TTSEvent.FinishEvent(generateEventId());
            sendStreamEvent(connection, event);
        }

        private void handleAudioDelta(RealtimeTTSResponseEvent.ResponseAudioDeltaEvent event) {
            String base64Audio = event.getDelta();
            if (base64Audio == null || base64Audio.isEmpty()) {
                return;
            }

            AudioBlock audioBlock =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/" + format)
                                            .data(base64Audio)
                                            .build())
                            .build();

            Sinks.EmitResult result = sink.tryEmitNext(audioBlock);
            if (result.isFailure()) {
                log.debug("Failed to emit audio (sink may be cancelled): {}", result);
            }
        }

        private void handleSessionFinished() {
            log.debug("Session finished, completing stream");
            sink.tryEmitComplete();
            connection.close().subscribe();
        }
    }

    /**
     * Synthesizes text to audio (blocking).
     *
     * @param text the text to synthesize
     * @param options optional TTS options (may be null)
     * @return Mono containing the TTS response with audio data
     */
    @Override
    public Mono<TTSResponse> synthesize(String text, TTSOptions options) {
        return Mono.fromCallable(
                () -> {
                    ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

                    synthesizeStream(text)
                            .doOnNext(
                                    audioBlock -> {
                                        if (audioBlock.getSource() instanceof Base64Source src) {
                                            if (src.getData() != null) {
                                                byte[] data =
                                                        Base64.getDecoder().decode(src.getData());
                                                audioBuffer.write(data, 0, data.length);
                                            }
                                        }
                                    })
                            .blockLast();

                    return TTSResponse.builder()
                            .audioData(audioBuffer.toByteArray())
                            .format(format)
                            .sampleRate(sampleRate)
                            .build();
                });
    }

    /**
     * Gets the model name.
     *
     * @return the model name
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Creates a new builder for DashScopeRealtimeTTSModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing DashScopeRealtimeTTSModel instances.
     */
    public static class Builder {
        private String apiKey;
        // Use flash model which supports system voices (Cherry, Serena, etc.)
        private String modelName = "qwen3-tts-flash-realtime";
        private String voice = "Cherry";
        private int sampleRate = 24000;
        private String format = "pcm";
        private SessionMode mode = SessionMode.SERVER_COMMIT;
        private String languageType = "Auto";

        /**
         * Sets the API key for DashScope authentication.
         *
         * @param apiKey the API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the TTS model name.
         *
         * <p>Supported models:
         * <ul>
         *   <li>qwen3-tts-flash-realtime - supports system voices</li>
         *   <li>qwen3-tts-vd-realtime - supports voice design via text description</li>
         *   <li>qwen3-tts-vc-realtime - supports voice cloning</li>
         *   <li>qwen-tts-realtime - legacy model</li>
         * </ul>
         *
         * @param modelName the model name
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets the voice for synthesis.
         *
         * <p>Available voices include: Cherry, Serena, Ethan, Chelsie, etc.
         *
         * @param voice the voice name
         * @return this builder
         */
        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        /**
         * Sets the audio sample rate.
         *
         * @param sampleRate sample rate in Hz (default: 24000)
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the audio format.
         *
         * <p>Supported formats: pcm, mp3, opus
         *
         * @param format audio format
         * @return this builder
         */
        public Builder format(String format) {
            this.format = format;
            return this;
        }

        /**
         * Sets the session mode.
         *
         * <p>In {@link SessionMode#SERVER_COMMIT} mode, the server automatically
         * commits text for synthesis. In {@link SessionMode#COMMIT} mode, the client
         * must manually call {@link #commitTextBuffer()}.
         *
         * @param mode the session mode (default: SERVER_COMMIT)
         * @return this builder
         */
        public Builder mode(SessionMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Sets the language type for synthesis.
         *
         * <p>Supported values: Chinese, English, German, Italian, Portuguese,
         * Spanish, Japanese, Korean, French, Russian, Auto
         *
         * @param languageType the language type (default: Auto)
         * @return this builder
         */
        public Builder languageType(String languageType) {
            this.languageType = languageType;
            return this;
        }

        /**
         * Builds the DashScopeRealtimeTTSModel instance.
         *
         * @return a configured DashScopeRealtimeTTSModel
         * @throws IllegalArgumentException if apiKey is not set
         */
        public DashScopeRealtimeTTSModel build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new DashScopeRealtimeTTSModel(this);
        }
    }
}
