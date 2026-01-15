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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * DashScope Realtime TTS Model with WebSocket streaming input support.
 *
 * <p>This model uses DashScope's WebSocket-based TTS API with native OkHttp WebSocket,
 * enabling true streaming input where text can be pushed incrementally while
 * maintaining context continuity for natural prosody and intonation.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Native WebSocket-based streaming with OkHttp</li>
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String modelName;
    private final String voice;
    private final int sampleRate;
    private final String format;
    private final SessionMode mode;
    private final String languageType;

    // WebSocket client and state
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private Sinks.Many<AudioBlock> audioSink;

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
        this.httpClient =
                new OkHttpClient.Builder()
                        .pingInterval(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();
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
                Request request =
                        new Request.Builder()
                                .url(WEBSOCKET_URL + "?model=" + modelName)
                                .header("Authorization", "Bearer " + apiKey)
                                .build();

                webSocket = httpClient.newWebSocket(request, new TtsWebSocketListener(audioSink));

                // Wait a bit for connection to establish
                Thread.sleep(100);

                // Configure the session
                updateSession();

                log.debug("TTS WebSocket session started");
            } catch (InterruptedException e) {
                sessionActive.set(false);
                Thread.currentThread().interrupt();
                log.error("Failed to start TTS session: {}", e.getMessage());
                throw new TTSException("Failed to start TTS session", e);
            }
        }
    }

    /**
     * Updates the session configuration.
     */
    private void updateSession() {
        ObjectNode sessionConfig = objectMapper.createObjectNode();
        sessionConfig.put("mode", mode.getValue());
        sessionConfig.put("voice", voice);
        sessionConfig.put("language_type", languageType);
        sessionConfig.put("response_format", format);
        sessionConfig.put("sample_rate", sampleRate);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "session.update");
        event.put("event_id", generateEventId());
        event.set("session", sessionConfig);

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
    private void sendEvent(ObjectNode event) {
        if (webSocket == null) {
            throw new TTSException("WebSocket not connected");
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            log.debug(
                    "Sending event: type={}, event_id={}",
                    event.get("type"),
                    event.get("event_id"));
            webSocket.send(json);
        } catch (JsonProcessingException e) {
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
        if (!sessionActive.get()) {
            startSession();
        }

        if (text == null || text.isEmpty()) {
            return Flux.empty();
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
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "input_text_buffer.append");
        event.put("event_id", generateEventId());
        event.put("text", text);
        sendEvent(event);
    }

    /**
     * Commits the text buffer to trigger processing.
     *
     * <p>Only needed in {@link SessionMode#COMMIT} mode.
     * In {@link SessionMode#SERVER_COMMIT} mode, the server commits automatically.
     */
    public void commitTextBuffer() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "input_text_buffer.commit");
        event.put("event_id", generateEventId());
        sendEvent(event);
        log.debug("Text buffer committed");
    }

    /**
     * Clears the text buffer.
     */
    public void clearTextBuffer() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "input_text_buffer.clear");
        event.put("event_id", generateEventId());
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
        if (!sessionActive.get() || webSocket == null) {
            return Flux.empty();
        }

        // Send session finish event
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "session.finish");
        event.put("event_id", generateEventId());
        sendEvent(event);

        sessionActive.set(false);
        log.debug("TTS session finish requested");

        // Don't complete the sink here - let the WebSocket listener complete it
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
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Client closing");
            } catch (Exception e) {
                log.warn("Error closing WebSocket: {}", e.getMessage());
            }
            webSocket = null;
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
        // Use Sinks.Many to collect audio data from WebSocket callbacks
        Sinks.Many<AudioBlock> streamSink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<WebSocket> clientRef = new AtomicReference<>();

        Request request =
                new Request.Builder()
                        .url(WEBSOCKET_URL + "?model=" + modelName)
                        .header("Authorization", "Bearer " + apiKey)
                        .build();

        WebSocketListener listener =
                new WebSocketListener() {
                    @Override
                    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                        log.debug("TTS WebSocket connection opened");

                        // Configure the session
                        ObjectNode sessionConfig = objectMapper.createObjectNode();
                        sessionConfig.put("mode", mode.getValue());
                        sessionConfig.put("voice", voice);
                        sessionConfig.put("language_type", languageType);
                        sessionConfig.put("response_format", format);
                        sessionConfig.put("sample_rate", sampleRate);

                        ObjectNode event = objectMapper.createObjectNode();
                        event.put("type", "session.update");
                        event.put("event_id", generateEventId());
                        event.set("session", sessionConfig);

                        try {
                            webSocket.send(objectMapper.writeValueAsString(event));
                        } catch (JsonProcessingException e) {
                            log.error("Failed to send session update: {}", e.getMessage());
                            streamSink.tryEmitError(
                                    new TTSException("Failed to send session update", e));
                        }
                    }

                    @Override
                    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text1) {
                        try {
                            JsonNode event = objectMapper.readTree(text1);
                            String eventType =
                                    event.has("type") ? event.get("type").asText() : "unknown";

                            if (!"response.audio.delta".equals(eventType)) {
                                log.debug("Received event: {}", eventType);
                            }

                            switch (eventType) {
                                case "error":
                                    String errMsg =
                                            event.has("error")
                                                    ? event.get("error").toString()
                                                    : "Unknown error";
                                    log.error("TTS API error: {}", errMsg);
                                    streamSink.tryEmitError(
                                            new TTSException("TTS API error: " + errMsg));
                                    break;

                                case "session.created":
                                    log.debug(
                                            "Session created: {}",
                                            event.path("session").path("id").asText());
                                    break;

                                case "session.updated":
                                    log.debug("Session updated, sending text");
                                    // Send the text after session is configured
                                    ObjectNode appendEvent = objectMapper.createObjectNode();
                                    appendEvent.put("type", "input_text_buffer.append");
                                    appendEvent.put("event_id", generateEventId());
                                    appendEvent.put("text", text);
                                    webSocket.send(objectMapper.writeValueAsString(appendEvent));

                                    // For commit mode, need to manually commit
                                    if (mode == SessionMode.COMMIT) {
                                        ObjectNode commitEvent = objectMapper.createObjectNode();
                                        commitEvent.put("type", "input_text_buffer.commit");
                                        commitEvent.put("event_id", generateEventId());
                                        webSocket.send(
                                                objectMapper.writeValueAsString(commitEvent));
                                    }

                                    // Send session finish to signal end of input
                                    ObjectNode finishEvent = objectMapper.createObjectNode();
                                    finishEvent.put("type", "session.finish");
                                    finishEvent.put("event_id", generateEventId());
                                    webSocket.send(objectMapper.writeValueAsString(finishEvent));
                                    break;

                                case "response.audio.delta":
                                    if (event.has("delta") && !event.get("delta").isNull()) {
                                        String base64Audio = event.get("delta").asText();
                                        if (base64Audio != null && !base64Audio.isEmpty()) {
                                            AudioBlock audioBlock =
                                                    AudioBlock.builder()
                                                            .source(
                                                                    Base64Source.builder()
                                                                            .mediaType(
                                                                                    "audio/"
                                                                                            + format)
                                                                            .data(base64Audio)
                                                                            .build())
                                                            .build();
                                            Sinks.EmitResult result =
                                                    streamSink.tryEmitNext(audioBlock);
                                            if (result.isFailure()) {
                                                log.debug(
                                                        "Failed to emit audio (sink may be "
                                                                + "cancelled): {}",
                                                        result);
                                            }
                                        }
                                    }
                                    break;

                                case "response.audio.done":
                                    log.debug("Audio generation completed");
                                    break;

                                case "response.done":
                                    log.debug("Response completed");
                                    break;

                                case "session.finished":
                                    log.debug("Session finished, completing stream");
                                    streamSink.tryEmitComplete();
                                    // Close WebSocket after session is finished
                                    webSocket.close(1000, "Session finished");
                                    break;
                            }
                        } catch (Exception e) {
                            log.error("Error processing message: {}", e.getMessage());
                            streamSink.tryEmitError(
                                    new TTSException(
                                            "Error processing message: " + e.getMessage(), e));
                        }
                    }

                    @Override
                    public void onClosed(
                            @NotNull WebSocket webSocket, int code, @NotNull String reason) {
                        log.debug("WebSocket closed: {} - {}", code, reason);
                        streamSink.tryEmitComplete();
                    }

                    @Override
                    public void onFailure(
                            @NotNull WebSocket webSocket,
                            @NotNull Throwable t,
                            @Nullable Response response) {
                        log.error("WebSocket failure: {}", t.getMessage());
                        streamSink.tryEmitError(
                                new TTSException("WebSocket failure: " + t.getMessage(), t));
                    }
                };

        WebSocket client = httpClient.newWebSocket(request, listener);
        clientRef.set(client);

        // Return the flux and handle cleanup on cancel/complete
        return streamSink
                .asFlux()
                .doOnCancel(
                        () -> {
                            log.debug("Stream cancelled, closing WebSocket");
                            WebSocket ws = clientRef.get();
                            if (ws != null) {
                                ws.close(1000, "Stream cancelled");
                            }
                        })
                .doOnTerminate(
                        () -> {
                            log.debug("Stream terminated");
                        });
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
     * WebSocket listener for receiving TTS audio from the server.
     */
    private class TtsWebSocketListener extends WebSocketListener {

        private final Sinks.Many<AudioBlock> listenerSink;

        TtsWebSocketListener(Sinks.Many<AudioBlock> sink) {
            this.listenerSink = sink;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.debug("TTS WebSocket connection opened");
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            try {
                JsonNode event = objectMapper.readTree(text);
                String eventType = event.has("type") ? event.get("type").asText() : "unknown";

                if (!"response.audio.delta".equals(eventType)) {
                    log.debug("Received event: {}", eventType);
                }

                switch (eventType) {
                    case "error":
                        String errMsg =
                                event.has("error")
                                        ? event.get("error").toString()
                                        : "Unknown error";
                        log.error("TTS API error: {}", errMsg);
                        break;

                    case "session.created":
                        log.debug("Session created: {}", event.path("session").path("id").asText());
                        break;

                    case "session.updated":
                        log.debug("Session updated");
                        break;

                    case "input_text_buffer.committed":
                        log.debug(
                                "Text buffer committed, item_id: {}",
                                event.path("item_id").asText());
                        break;

                    case "input_text_buffer.cleared":
                        log.debug("Text buffer cleared");
                        break;

                    case "response.created":
                        currentResponseId = event.path("response").path("id").asText();
                        isResponding.set(true);
                        responseDoneFuture = new CompletableFuture<>();
                        log.debug("Response created: {}", currentResponseId);
                        break;

                    case "response.output_item.added":
                        currentItemId = event.path("item").path("id").asText();
                        log.debug("Output item added: {}", currentItemId);
                        break;

                    case "response.audio.delta":
                        if (event.has("delta") && !event.get("delta").isNull()) {
                            String base64Audio = event.get("delta").asText();
                            if (base64Audio != null && !base64Audio.isEmpty()) {
                                AudioBlock audioBlock =
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("audio/" + format)
                                                                .data(base64Audio)
                                                                .build())
                                                .build();
                                if (listenerSink != null) {
                                    Sinks.EmitResult result = listenerSink.tryEmitNext(audioBlock);
                                    if (result.isFailure()) {
                                        // Use debug level - this can happen if subscriber cancelled
                                        log.debug(
                                                "Failed to emit audio (sink may be cancelled): {}",
                                                result);
                                    }
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
                        if (listenerSink != null) {
                            listenerSink.tryEmitComplete();
                        }
                        break;
                }
            } catch (Exception e) {
                log.error("Error processing WebSocket message: {}", e.getMessage());
            }
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.debug("TTS WebSocket closed: {} - {}", code, reason);
            if (listenerSink != null) {
                listenerSink.tryEmitComplete();
            }
        }

        @Override
        public void onFailure(
                @NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
            log.error("TTS WebSocket failure: {}", t.getMessage());
            if (listenerSink != null) {
                listenerSink.tryEmitError(new TTSException("WebSocket connection failed", t));
            }
        }
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
