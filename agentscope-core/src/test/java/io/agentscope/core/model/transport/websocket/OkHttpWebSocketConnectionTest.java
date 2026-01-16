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
package io.agentscope.core.model.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("OkHttpWebSocketConnection Tests")
class OkHttpWebSocketConnectionTest {

    private static final String TEST_URL = "wss://example.com/ws";

    @Nested
    @DisplayName("Text Protocol Tests")
    class TextProtocolTests {

        private OkHttpWebSocketConnection<String> connection;
        private WebSocket mockWebSocket;

        @BeforeEach
        void setUp() {
            connection = new OkHttpWebSocketConnection<>(TEST_URL, String.class);
            mockWebSocket = mock(WebSocket.class);
        }

        @Test
        @DisplayName("Should not be open before WebSocket is set")
        void shouldNotBeOpenBeforeWebSocketSet() {
            assertFalse(connection.isOpen());
            assertFalse(connection.isInitialized());
        }

        @Test
        @DisplayName("Should be open after WebSocket is set")
        void shouldBeOpenAfterWebSocketSet() {
            connection.setWebSocket(mockWebSocket);

            assertTrue(connection.isOpen());
            assertTrue(connection.isInitialized());
        }

        @Test
        @DisplayName("Should send text message successfully")
        void shouldSendTextMessageSuccessfully() {
            connection.setWebSocket(mockWebSocket);
            when(mockWebSocket.send(any(String.class))).thenReturn(true);

            StepVerifier.create(connection.send("Hello")).verifyComplete();

            verify(mockWebSocket).send("Hello");
        }

        @Test
        @DisplayName("Should fail to send when connection is not open")
        void shouldFailToSendWhenNotOpen() {
            StepVerifier.create(connection.send("Hello"))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should fail to send when WebSocket returns false")
        void shouldFailToSendWhenWebSocketReturnsFalse() {
            connection.setWebSocket(mockWebSocket);
            when(mockWebSocket.send(any(String.class))).thenReturn(false);

            StepVerifier.create(connection.send("Hello"))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should receive text messages")
        void shouldReceiveTextMessages() {
            connection.setWebSocket(mockWebSocket);

            List<String> received = new ArrayList<>();
            connection.receive().subscribe(received::add);

            connection.onMessage("Hello".getBytes(StandardCharsets.UTF_8));
            connection.onMessage("World".getBytes(StandardCharsets.UTF_8));

            assertEquals(2, received.size());
            assertEquals("Hello", received.get(0));
            assertEquals("World", received.get(1));
        }

        @Test
        @DisplayName("Should handle close")
        void shouldHandleClose() {
            connection.setWebSocket(mockWebSocket);

            connection.onClosed(CloseInfo.NORMAL_CLOSURE, "Normal closure");

            assertFalse(connection.isOpen());
            assertNotNull(connection.getCloseInfo());
            assertEquals(CloseInfo.NORMAL_CLOSURE, connection.getCloseInfo().code());
            assertEquals("Normal closure", connection.getCloseInfo().reason());
        }

        @Test
        @DisplayName("Should handle error")
        void shouldHandleError() {
            connection.setWebSocket(mockWebSocket);

            connection.onError(new RuntimeException("Test error"));

            assertFalse(connection.isOpen());
            assertNotNull(connection.getCloseInfo());
            assertEquals(CloseInfo.ABNORMAL_CLOSURE, connection.getCloseInfo().code());
        }

        @Test
        @DisplayName("Should close connection")
        void shouldCloseConnection() {
            connection.setWebSocket(mockWebSocket);

            StepVerifier.create(connection.close()).verifyComplete();

            assertFalse(connection.isOpen());
            assertNotNull(connection.getCloseInfo());
            assertEquals(CloseInfo.NORMAL_CLOSURE, connection.getCloseInfo().code());
            verify(mockWebSocket).close(CloseInfo.NORMAL_CLOSURE, "");
        }

        @Test
        @DisplayName("Should return null close info before close")
        void shouldReturnNullCloseInfoBeforeClose() {
            assertNull(connection.getCloseInfo());
        }
    }

    @Nested
    @DisplayName("Binary Protocol Tests")
    class BinaryProtocolTests {

        private OkHttpWebSocketConnection<byte[]> connection;
        private WebSocket mockWebSocket;

        @BeforeEach
        void setUp() {
            connection = new OkHttpWebSocketConnection<>(TEST_URL, byte[].class);
            mockWebSocket = mock(WebSocket.class);
        }

        @Test
        @DisplayName("Should send binary message successfully")
        void shouldSendBinaryMessageSuccessfully() {
            connection.setWebSocket(mockWebSocket);
            when(mockWebSocket.send(any(ByteString.class))).thenReturn(true);

            byte[] data = new byte[] {0x01, 0x02, 0x03};
            StepVerifier.create(connection.send(data)).verifyComplete();

            verify(mockWebSocket).send(ByteString.of(data));
        }

        @Test
        @DisplayName("Should receive binary messages")
        void shouldReceiveBinaryMessages() {
            connection.setWebSocket(mockWebSocket);

            List<byte[]> received = new ArrayList<>();
            connection.receive().subscribe(received::add);

            byte[] data1 = new byte[] {0x01, 0x02};
            byte[] data2 = new byte[] {0x03, 0x04};
            connection.onMessage(data1);
            connection.onMessage(data2);

            assertEquals(2, received.size());
            assertEquals(2, received.get(0).length);
            assertEquals(2, received.get(1).length);
        }
    }
}
