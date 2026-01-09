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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.transport.CloseInfo;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.live.transport.WebSocketConnection;
import io.agentscope.core.live.transport.WebSocketRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@DisplayName("DoubaoLiveModel Tests")
class DoubaoLiveModelTest {

    private MockWebSocketClient mockClient;

    @BeforeEach
    void setUp() {
        mockClient = new MockWebSocketClient();
    }

    @Test
    @DisplayName("Should build model with required parameters")
    void shouldBuildModelWithRequiredParameters() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("doubao", model.getProviderName());
        assertTrue(model.supportsNativeRecovery());
    }

    @Test
    @DisplayName("Should use default model name")
    void shouldUseDefaultModelName() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .build();

        assertEquals("doubao-realtime", model.getModelName());
    }

    @Test
    @DisplayName("Should throw when appId is missing")
    void shouldThrowWhenAppIdIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DoubaoLiveModel.builder()
                                .accessKey("test-access-key")
                                .webSocketClient(mockClient)
                                .build());
    }

    @Test
    @DisplayName("Should throw when accessKey is missing")
    void shouldThrowWhenAccessKeyIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DoubaoLiveModel.builder()
                                .appId("test-app-id")
                                .webSocketClient(mockClient)
                                .build());
    }

    @Test
    @DisplayName("Should throw when webSocketClient is missing")
    void shouldThrowWhenWebSocketClientIsMissing() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DoubaoLiveModel.builder()
                                .appId("test-app-id")
                                .accessKey("test-access-key")
                                .build());
    }

    @Test
    @DisplayName("Should use default base URL")
    void shouldUseDefaultBaseUrl() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals(DoubaoLiveModel.DEFAULT_BASE_URL, request.getUrl());
    }

    @Test
    @DisplayName("Should use custom base URL")
    void shouldUseCustomBaseUrl() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .baseUrl("wss://custom.example.com/api/v3/realtime/dialogue")
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("wss://custom.example.com/api/v3/realtime/dialogue", request.getUrl());
    }

    @Test
    @DisplayName("Should set authentication headers")
    void shouldSetAuthenticationHeaders() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("my-app-id")
                        .accessKey("my-access-key")
                        .resourceId("my-resource-id")
                        .appKey("my-app-key")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("my-app-id", request.getHeaders().get("X-Api-App-ID"));
        assertEquals("my-access-key", request.getHeaders().get("X-Api-Access-Key"));
        assertEquals("my-resource-id", request.getHeaders().get("X-Api-Resource-Id"));
        assertEquals("my-app-key", request.getHeaders().get("X-Api-App-Key"));
    }

    @Test
    @DisplayName("Should set connect ID header when provided")
    void shouldSetConnectIdHeader() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .connectId("my-connect-id")
                        .webSocketClient(mockClient)
                        .build();

        model.connect(LiveConfig.defaults()).subscribe();

        WebSocketRequest request = mockClient.getLastRequest();
        assertNotNull(request);
        assertEquals("my-connect-id", request.getHeaders().get("X-Api-Connect-Id"));
    }

    @Test
    @DisplayName("Should configure model version")
    void shouldConfigureModelVersion() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .modelVersion("1.2.1.0")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure bot name")
    void shouldConfigureBotName() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .botName("TestBot")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure voice type")
    void shouldConfigureVoiceType() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .voiceType("zh_female_shuangkuaisisi_moon_bigtts")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure web search")
    void shouldConfigureWebSearch() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .enableWebSearch(true)
                        .webSearchType("web_summary")
                        .webSearchApiKey("search-api-key")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure dialog id for session resumption")
    void shouldConfigureDialogId() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .dialogId("previous-dialog-id")
                        .build();

        assertNotNull(model);
        assertTrue(model.supportsNativeRecovery());
    }

    @Test
    @DisplayName("Should configure music capability")
    void shouldConfigureMusicCapability() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .modelVersion("1.2.1.0")
                        .enableMusic(true)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure VAD smooth window")
    void shouldConfigureVadSmoothWindow() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .endSmoothWindowMs(2000)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure output audio format")
    void shouldConfigureOutputAudioFormat() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .outputAudioFormat("pcm")
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should configure strict audit")
    void shouldConfigureStrictAudit() {
        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .strictAudit(false)
                        .build();

        assertNotNull(model);
    }

    @Test
    @DisplayName("Should connect and complete two-phase handshake")
    void shouldConnectAndCompleteTwoPhaseHandshake() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .build();

        StepVerifier.create(model.connect(LiveConfig.defaults()))
                .then(
                        () -> {
                            // Simulate ConnectionStarted (event_id=50)
                            connection.simulateMessage(buildDoubaoFrame(50, "{}"));
                            // Simulate SessionStarted (event_id=150)
                            connection.simulateMessage(
                                    buildDoubaoFrame(150, "{\"dialog_id\":\"dlg-123\"}"));
                        })
                .expectNextMatches(
                        session -> {
                            assertEquals("doubao", session.getProviderName());
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should fail when connection fails during handshake")
    void shouldFailWhenConnectionFails() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Simulate ConnectionFailed (event_id=51)
                                            connection.simulateMessage(
                                                    buildDoubaoFrame(
                                                            51,
                                                            "{\"error_message\":\"Auth failed\"}"));
                                            return session.receive();
                                        }))
                .expectError(DoubaoLiveModel.DoubaoConnectionException.class)
                .verify();
    }

    @Test
    @DisplayName("Should propagate error through event stream when session fails during handshake")
    void shouldFailWhenSessionFails() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
                        .webSocketClient(mockClient)
                        .build();

        // With the new design, connect() returns immediately and errors are propagated
        // through the event stream (session.receive())
        StepVerifier.create(
                        model.connect(LiveConfig.defaults())
                                .flatMapMany(
                                        session -> {
                                            // Simulate ConnectionStarted (event_id=50)
                                            connection.simulateMessage(buildDoubaoFrame(50, "{}"));
                                            // Simulate SessionFailed (event_id=153)
                                            connection.simulateMessage(
                                                    buildDoubaoFrame(
                                                            153,
                                                            "{\"error_message\":\"Session init"
                                                                    + " failed\"}"));
                                            return session.receive();
                                        }))
                .expectError(DoubaoLiveModel.DoubaoSessionException.class)
                .verify();
    }

    @Test
    @DisplayName("Should complete event stream when connection closes during handshake")
    void shouldFailWhenConnectionClosesDuringHandshake() {
        MockWebSocketConnection connection = new MockWebSocketConnection();
        mockClient.setNextConnection(connection);

        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
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

        DoubaoLiveModel model =
                DoubaoLiveModel.builder()
                        .appId("test-app-id")
                        .accessKey("test-access-key")
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

    /**
     * Build a Doubao binary frame.
     *
     * @param eventId Event ID
     * @param jsonPayload JSON payload string
     * @return Binary frame bytes
     */
    private byte[] buildDoubaoFrame(int eventId, String jsonPayload) {
        byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Header (4 bytes)
        buffer.put((byte) 0x11); // Protocol version
        buffer.put((byte) 0x10); // Header size
        buffer.put((byte) 0x01); // Message type
        buffer.put((byte) 0x00); // Reserved

        // Event ID (4 bytes)
        buffer.putInt(eventId);

        // Payload
        buffer.put(payload);

        return buffer.array();
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

    static class MockWebSocketConnection implements WebSocketConnection<byte[]> {
        private boolean open = true;
        private boolean closed = false;
        private final List<byte[]> sentMessages = new ArrayList<>();
        private final Sinks.Many<byte[]> messageSink =
                Sinks.many().multicast().onBackpressureBuffer();
        private CloseInfo closeInfo;

        void simulateMessage(byte[] data) {
            messageSink.tryEmitNext(data);
        }

        void simulateError(Throwable error) {
            messageSink.tryEmitError(error);
        }

        void complete() {
            messageSink.tryEmitComplete();
        }

        List<byte[]> getSentMessages() {
            return sentMessages;
        }

        @Override
        public Mono<Void> send(byte[] data) {
            return Mono.fromRunnable(() -> sentMessages.add(data));
        }

        @Override
        public Flux<byte[]> receive() {
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
