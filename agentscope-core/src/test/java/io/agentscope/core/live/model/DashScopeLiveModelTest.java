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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@DisplayName("DashScopeLiveModel Tests")
class DashScopeLiveModelTest {

    private MockWebSocketClient mockClient;

    @BeforeEach
    void setUp() {
        mockClient = new MockWebSocketClient();
    }

    @Test
    @DisplayName("Should build model with required parameters")
    void shouldBuildModelWithRequiredParameters() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .modelName("qwen-omni-turbo-realtime")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("qwen-omni-turbo-realtime", model.getModelName());
        assertEquals("dashscope", model.getProviderName());
        assertFalse(model.supportsNativeRecovery());
    }

    @Test
    @DisplayName("Should use default model name")
    void shouldUseDefaultModelName() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("qwen-omni-turbo-realtime", model.getModelName());
    }

    @Test
    @DisplayName("Should throw when apiKey is missing")
    void shouldThrowWhenApiKeyIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DashScopeLiveModel.builder()
                                .modelName("qwen-omni-turbo-realtime")
                                .webSocketClient(mockClient)
                                .build());
    }

    @Test
    @DisplayName("Should throw when webSocketClient is missing")
    void shouldThrowWhenWebSocketClientIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DashScopeLiveModel.builder()
                                .apiKey("test-api-key")
                                .modelName("qwen-omni-turbo-realtime")
                                .build());
    }

    @Test
    @DisplayName("Should use default base URL")
    void shouldUseDefaultBaseUrl() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().startsWith("wss://dashscope.aliyuncs.com/api-ws/v1/realtime"));
    }

    @Test
    @DisplayName("Should use custom base URL")
    void shouldUseCustomBaseUrl() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .baseUrl("wss://custom.example.com/api-ws/v1/realtime")
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().startsWith("wss://custom.example.com/api-ws/v1/realtime"));
    }

    @Test
    @DisplayName("Should set Authorization header")
    void shouldSetAuthorizationHeader() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("my-secret-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("Bearer my-secret-key", request.getHeaders().get("Authorization"));
    }

    @Test
    @DisplayName("Should set X-DashScope-DataInspection header")
    void shouldSetDataInspectionHeader() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("enable", request.getHeaders().get("X-DashScope-DataInspection"));
    }

    @Test
    @DisplayName("Should include model name in URL")
    void shouldIncludeModelNameInUrl() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .modelName("qwen2.5-omni-7b-realtime")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertTrue(request.getUrl().contains("model=qwen2.5-omni-7b-realtime"));
    }

    @Test
    @DisplayName("Should configure smoothOutput")
    void shouldConfigureSmoothOutput() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .smoothOutput(true)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure VAD disabled")
    void shouldConfigureVadDisabled() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .vadDisabled()
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure repetition penalty")
    void shouldConfigureRepetitionPenalty() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .repetitionPenalty(1.2f)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure presence penalty")
    void shouldConfigurePresencePenalty() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .presencePenalty(0.5f)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure seed")
    void shouldConfigureSeed() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .seed(12345L)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure maxTokens")
    void shouldConfigureMaxTokens() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .maxTokens(1024)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure topK")
    void shouldConfigureTopK() {
        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .topK(50)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should connect and complete handshake")
    void shouldConnectAndCompleteHandshake() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        StepVerifier.create(model.connect(LiveConfig.defaults()))
                .then(
                        () -> {
                            // Simulate receiving session.created
                            connection.simulateMessage(
                                    "{\"type\":\"session.created\",\"session\":{\"id\":\"sess-123\"}}");
                            // Simulate receiving session.updated
                            connection.simulateMessage("{\"type\":\"session.updated\"}");
                        })
                .expectNextMatches(
                        session -> {
                            assertEquals("dashscope", session.getProviderName());
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should send session.update after session.created")
    void shouldSendSessionUpdateAfterSessionCreated() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        StepVerifier.create(model.connect(LiveConfig.builder().voice("Cherry").build()))
                .expectNextMatches(session -> session != null)
                .verifyComplete();

        // Simulate receiving session.created to trigger session.update
        connection.simulateMessage(
                "{\"type\":\"session.created\",\"session\":{\"id\":\"sess-123\"}}");

        // Give time for the message to be processed
        try {
            Thread.sleep(50);
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

        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
                        .apiKey("test-api-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Close connection without completing handshake
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

        DashScopeLiveModel model =
                DashScopeLiveModel.builder()
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
