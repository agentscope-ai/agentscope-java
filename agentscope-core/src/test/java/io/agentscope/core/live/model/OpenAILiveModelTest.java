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
package io.agentscope.core.live.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.transport.CloseInfo;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.live.transport.WebSocketConnection;
import io.agentscope.core.live.transport.WebSocketRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@DisplayName("OpenAILiveModel Tests")
class OpenAILiveModelTest {

    private MockWebSocketClient mockClient;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        mockClient = new MockWebSocketClient();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("Should build model with required parameters")
    void shouldBuildModelWithRequiredParameters() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .modelName("gpt-4o-realtime-preview")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("gpt-4o-realtime-preview", model.getModelName());
        assertEquals("openai", model.getProviderName());
        assertFalse(model.supportsNativeRecovery());
    }

    @Test
    @DisplayName("Should use default model name")
    void shouldUseDefaultModelName() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("gpt-realtime-2025-08-28", model.getModelName());
    }

    @Test
    @DisplayName("Should throw when apiKey is missing")
    void shouldThrowWhenApiKeyIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        OpenAILiveModel.builder()
                                .modelName("gpt-realtime-2025-08-28")
                                .webSocketClient(mockClient)
                                .build());
    }

    @Test
    @DisplayName("Should throw when webSocketClient is missing")
    void shouldThrowWhenWebSocketClientIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        OpenAILiveModel.builder()
                                .apiKey("test-api-key")
                                .modelName("gpt-4o-realtime-preview")
                                .build());
    }

    @Test
    @DisplayName("Should use default base URL")
    void shouldUseDefaultBaseUrl() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().startsWith("wss://api.openai.com/v1/realtime"));
    }

    @Test
    @DisplayName("Should use custom base URL")
    void shouldUseCustomBaseUrl() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .baseUrl("wss://custom.example.com/v1/realtime")
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().startsWith("wss://custom.example.com/v1/realtime"));
    }

    @Test
    @DisplayName("Should set Authorization header")
    void shouldSetAuthorizationHeader() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("my-secret-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("Bearer my-secret-key", request.getHeaders().get("Authorization"));
    }

    @Test
    @DisplayName("Should set OpenAI-Beta header")
    void shouldSetOpenAIBetaHeader() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("realtime=v1", request.getHeaders().get("OpenAI-Beta"));
    }

    @Test
    @DisplayName("Should include model name in URL")
    void shouldIncludeModelNameInUrl() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .modelName("gpt-4o-mini-realtime-preview")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().contains("model=gpt-4o-mini-realtime-preview"));
    }

    @Test
    @DisplayName("Should configure semantic VAD")
    void shouldConfigureSemanticVad() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .semanticVad(true)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure VAD disabled")
    void shouldConfigureVadDisabled() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .vadDisabled()
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure noise reduction")
    void shouldConfigureNoiseReduction() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .noiseReduction("near_field")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure speed")
    void shouldConfigureSpeed() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .speed(1.5f)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure transcription mode")
    void shouldConfigureTranscriptionMode() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .transcriptionMode()
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure prompt ID")
    void shouldConfigurePromptId() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .promptId("prompt-123")
                        .promptVersion("v1")
                        .promptVariables(Map.of("name", "Alice"))
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure VAD threshold")
    void shouldConfigureVadThreshold() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .vadThreshold(0.7f)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure silence duration")
    void shouldConfigureSilenceDuration() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .silenceDurationMs(1000)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure prefix padding")
    void shouldConfigurePrefixPadding() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .prefixPaddingMs(500)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure idle timeout")
    void shouldConfigureIdleTimeout() {
        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .idleTimeoutMs(30000)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should connect and complete handshake")
    void shouldConnectAndCompleteHandshake() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // Schedule messages to be sent after subscription is established
        scheduler.schedule(
                () -> {
                    connection.simulateMessage(
                            "{\"type\":\"session.created\",\"session\":{\"id\":\"sess-123\"}}");
                    connection.simulateMessage("{\"type\":\"session.updated\"}");
                },
                50,
                TimeUnit.MILLISECONDS);

        StepVerifier.create(model.connect(LiveConfig.defaults()))
                .expectNextMatches(
                        session -> {
                            assertEquals("openai", session.getProviderName());
                            return true;
                        })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Should send session.update after session.created")
    void shouldSendSessionUpdateAfterSessionCreated() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        StepVerifier.create(model.connect(LiveConfig.builder().voice("alloy").build()))
                .expectNextMatches(session -> session != null)
                .verifyComplete();

        // Schedule messages to trigger session.update
        scheduler.schedule(
                () -> {
                    connection.simulateMessage(
                            "{\"type\":\"session.created\",\"session\":{\"id\":\"sess-123\"}}");
                },
                50,
                TimeUnit.MILLISECONDS);

        // Give time for the message to be processed
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify session.update was sent
        List<String> sentMessages = connection.getSentMessages();
        assertTrue(sentMessages.size() >= 1, "Expected at least one message to be sent");
        assertTrue(
                sentMessages.stream().anyMatch(msg -> msg.contains("session.update")),
                "Expected session.update message to be sent");
    }

    @Test
    @DisplayName(
            "Should propagate error through event stream when connection closes during handshake")
    void shouldFailWhenConnectionClosesDuringHandshake() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Schedule connection close
                                            scheduler.schedule(
                                                    connection::complete,
                                                    50,
                                                    TimeUnit.MILLISECONDS);
                                            return session.receive();
                                        }))
                .verifyComplete(); // Stream completes when connection closes
    }

    @Test
    @DisplayName("Should propagate error through event stream when connection error occurs")
    void shouldFailWhenConnectionErrorOccurs() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Schedule error
                                            scheduler.schedule(
                                                    () ->
                                                            connection.simulateError(
                                                                    new RuntimeException(
                                                                            "Connection lost")),
                                                    50,
                                                    TimeUnit.MILLISECONDS);
                                            return session.receive();
                                        }))
                .expectError(RuntimeException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "Should propagate error through event stream when server returns error during"
                    + " handshake")
    void shouldFailWhenServerReturnsErrorDuringHandshake() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        OpenAILiveModel model =
                OpenAILiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Schedule error message
                                            scheduler.schedule(
                                                    () ->
                                                            connection.simulateMessage(
                                                                    "{\"type\":\"error\",\"error\":{\"code\":\"invalid_api_key\",\"message\":\"Invalid"
                                                                        + " API key\"}}"),
                                                    50,
                                                    TimeUnit.MILLISECONDS);
                                            return session.receive();
                                        }))
                .expectError(OpenAILiveModel.OpenAIConnectionException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ==================== Mock Classes ====================

    static class MockWebSocketClient implements WebSocketClient {
        private WebSocketRequest lastRequest;
        private MockWebSocketConnection nextConnection;

        void setNextConnection(MockWebSocketConnection connection) {
            this.nextConnection = connection;
        }

        WebSocketRequest getLastRequest() {
            return lastRequest;
        }

        @Override
        public <T> Mono<WebSocketConnection<T>> connect(
                WebSocketRequest request, Class<T> messageType) {
            this.lastRequest = request;
            @SuppressWarnings("unchecked")
            WebSocketConnection<T> conn =
                    (WebSocketConnection<T>)
                            (nextConnection != null
                                    ? nextConnection
                                    : new MockWebSocketConnection());
            return Mono.just(conn);
        }

        @Override
        public void shutdown() {}
    }

    static class MockWebSocketConnection implements WebSocketConnection<String> {
        private boolean open = true;
        private boolean closed = false;
        private final List<String> sentMessages = new ArrayList<>();
        private final Sinks.Many<String> messageSink =
                Sinks.many().multicast().onBackpressureBuffer();
        private CloseInfo closeInfo;

        void simulateMessage(String data) {
            messageSink.tryEmitNext(data);
        }

        void simulateError(Throwable error) {
            messageSink.tryEmitError(error);
        }

        void complete() {
            messageSink.tryEmitComplete();
        }

        List<String> getSentMessages() {
            return sentMessages;
        }

        @Override
        public Mono<Void> send(String data) {
            return Mono.fromRunnable(() -> sentMessages.add(data));
        }

        @Override
        public Flux<String> receive() {
            return messageSink.asFlux();
        }

        @Override
        public Mono<Void> close() {
            return Mono.fromRunnable(
                    () -> {
                        closed = true;
                        open = false;
                        closeInfo = new CloseInfo(CloseInfo.NORMAL_CLOSURE, "Normal closure");
                    });
        }

        @Override
        public boolean isOpen() {
            return open && !closed;
        }

        @Override
        public CloseInfo getCloseInfo() {
            return closeInfo;
        }
    }
}
