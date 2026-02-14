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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.formatter.GeminiLiveFormatter.SpeechSensitivity;
import io.agentscope.core.live.transport.CloseInfo;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.live.transport.WebSocketConnection;
import io.agentscope.core.live.transport.WebSocketRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@DisplayName("GeminiLiveModel Tests")
class GeminiLiveModelTest {

    private MockWebSocketClient mockClient;

    @BeforeEach
    void setUp() {
        mockClient = new MockWebSocketClient();
    }

    @Test
    @DisplayName("Should build model with required parameters")
    void shouldBuildModelWithRequiredParameters() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .modelName("gemini-2.0-flash-exp")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("gemini-2.0-flash-exp", model.getModelName());
        assertEquals("gemini", model.getProviderName());
        assertTrue(model.supportsNativeRecovery());
    }

    @Test
    @DisplayName("Should use default model name")
    void shouldUseDefaultModelName() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", model.getModelName());
    }

    @Test
    @DisplayName("Should throw when apiKey is missing")
    void shouldThrowWhenApiKeyIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        GeminiLiveModel.builder()
                                .modelName("gemini-2.0-flash-exp")
                                .webSocketClient(mockClient)
                                .build());
    }

    @Test
    @DisplayName("Should throw when webSocketClient is missing")
    void shouldThrowWhenWebSocketClientIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        GeminiLiveModel.builder()
                                .apiKey("test-api-key")
                                .modelName("gemini-2.0-flash-exp")
                                .build());
    }

    @Test
    @DisplayName("Should include API key in URL")
    void shouldIncludeApiKeyInUrl() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("my-secret-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("my-secret-key", request.getHeaders().get("X-goog-api-key"));
    }

    @Test
    @DisplayName("Should use default base URL")
    void shouldUseDefaultBaseUrl() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(
                request.getUrl()
                        .startsWith("wss://generativelanguage.googleapis.com/ws/google.ai"));
    }

    @Test
    @DisplayName("Should use custom base URL")
    void shouldUseCustomBaseUrl() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .baseUrl("wss://custom.example.com/ws")
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().startsWith("wss://custom.example.com/ws"));
    }

    @Test
    @DisplayName("Should configure proactive audio")
    void shouldConfigureProactiveAudio() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .proactiveAudio(true)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure affective dialog")
    void shouldConfigureAffectiveDialog() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .affectiveDialog(true)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure thinking")
    void shouldConfigureThinking() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .enableThinking(true)
                        .thinkingBudget(1000)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure session resumption")
    void shouldConfigureSessionResumption() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .sessionResumption(true)
                        .sessionResumptionHandle("previous-handle")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure context window compression")
    void shouldConfigureContextWindowCompression() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .contextWindowCompression(true)
                        .slidingWindowTokens(10000)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure speech sensitivity")
    void shouldConfigureSpeechSensitivity() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .startOfSpeechSensitivity(SpeechSensitivity.HIGH)
                        .endOfSpeechSensitivity(SpeechSensitivity.LOW)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure silence duration")
    void shouldConfigureSilenceDuration() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .silenceDurationMs(500)
                        .prefixPaddingMs(200)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure activity handling")
    void shouldConfigureActivityHandling() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .activityHandling("START_OF_ACTIVITY_INTERRUPTS")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure media resolution")
    void shouldConfigureMediaResolution() {
        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .mediaResolution("MEDIA_RESOLUTION_HIGH")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should connect and receive setup complete")
    void shouldConnectAndReceiveSetupComplete() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        StepVerifier.create(model.connect(LiveConfig.defaults()))
                .then(
                        () -> {
                            // Gemini returns setupComplete
                            connection.simulateMessage("{\"setupComplete\":{}}");
                        })
                .expectNextMatches(
                        session -> {
                            assertEquals("gemini", session.getProviderName());
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should send setup message on connect")
    void shouldSendSetupMessageOnConnect() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        StepVerifier.create(model.connect(LiveConfig.builder().voice("Puck").build()))
                .then(
                        () -> {
                            // Verify setup message was sent
                            List<String> sentMessages = connection.getSentMessages();
                            assertTrue(sentMessages.size() >= 1);
                            assertTrue(sentMessages.get(0).contains("setup"));

                            // Gemini returns setupComplete
                            connection.simulateMessage("{\"setupComplete\":{}}");
                        })
                .expectNextMatches(session -> session != null)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should fail when connection closes during setup")
    void shouldFailWhenConnectionClosesDuringSetup() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Close connection without completing setup
                                            connection.complete();
                                            return session.receive();
                                        }))
                .verifyComplete(); // Stream completes when connection closes
    }

    @Test
    @DisplayName("Should propagate error through event stream when connection error occurs")
    void shouldFailWhenConnectionErrorOccurs() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Simulate connection error
                                            connection.simulateError(
                                                    new RuntimeException("Connection lost"));
                                            return session.receive();
                                        }))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName(
            "Should propagate error through event stream when error event received during setup")
    void shouldFailWhenErrorEventReceivedDuringSetup() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        GeminiLiveModel model =
                GeminiLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Simulate error event
                                            connection.simulateMessage(
                                                    "{\"error\":{\"code\":\"INVALID_ARGUMENT\","
                                                            + "\"message\":\"Invalid API key\"}}");
                                            return session.receive();
                                        }))
                .expectError(GeminiLiveModel.GeminiConnectionException.class)
                .verify();
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
