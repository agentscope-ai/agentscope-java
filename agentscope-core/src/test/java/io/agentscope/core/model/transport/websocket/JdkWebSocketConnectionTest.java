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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("JdkWebSocketConnection Tests")
class JdkWebSocketConnectionTest {

    private static final String TEST_URL = "wss://example.com";

    @Nested
    @DisplayName("Basic State Tests")
    class BasicStateTests {

        private JdkWebSocketConnection<String> textConnection;
        private JdkWebSocketConnection<byte[]> binaryConnection;

        @BeforeEach
        void setUp() {
            textConnection = new JdkWebSocketConnection<>(TEST_URL, String.class);
            binaryConnection = new JdkWebSocketConnection<>(TEST_URL, byte[].class);
        }

        @Test
        @DisplayName("Should not be open before WebSocket is set")
        void shouldNotBeOpenBeforeWebSocketIsSet() {
            assertFalse(textConnection.isOpen());
            assertFalse(binaryConnection.isOpen());
        }

        @Test
        @DisplayName("Should return null close info before close")
        void shouldReturnNullCloseInfoBeforeClose() {
            assertNull(textConnection.getCloseInfo());
            assertNull(binaryConnection.getCloseInfo());
        }

        @Test
        @DisplayName("Should be open after WebSocket is set")
        void shouldBeOpenAfterWebSocketIsSet() {
            WebSocket mockWebSocket = mock(WebSocket.class);
            textConnection.setWebSocket(mockWebSocket);

            assertTrue(textConnection.isOpen());
        }

        @Test
        @DisplayName("Should not be open after close")
        void shouldNotBeOpenAfterClose() {
            WebSocket mockWebSocket = mock(WebSocket.class);
            when(mockWebSocket.sendClose(eq(CloseInfo.NORMAL_CLOSURE), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));
            textConnection.setWebSocket(mockWebSocket);

            textConnection.close().block();

            assertFalse(textConnection.isOpen());
        }

        @Test
        @DisplayName("Should have listener available")
        void shouldHaveListenerAvailable() {
            assertNotNull(textConnection.getListener());
            assertNotNull(binaryConnection.getListener());
        }
    }

    @Nested
    @DisplayName("Listener Callback Tests")
    class ListenerCallbackTests {

        private JdkWebSocketConnection<String> textConnection;
        private JdkWebSocketConnection<byte[]> binaryConnection;
        private WebSocket mockWebSocket;

        @BeforeEach
        void setUp() {
            textConnection = new JdkWebSocketConnection<>(TEST_URL, String.class);
            binaryConnection = new JdkWebSocketConnection<>(TEST_URL, byte[].class);
            mockWebSocket = mock(WebSocket.class);
        }

        @Test
        @DisplayName("Should request more data on open")
        void shouldRequestMoreDataOnOpen() {
            WebSocket.Listener listener = textConnection.getListener();

            listener.onOpen(mockWebSocket);

            verify(mockWebSocket).request(1);
        }

        @Test
        @DisplayName("Should handle single fragment text message")
        void shouldHandleSingleFragmentTextMessage() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            // Subscribe to messages
            List<String> received = new ArrayList<>();
            textConnection.receive().subscribe(received::add);

            // Simulate single fragment message
            listener.onText(mockWebSocket, "Hello World", true);

            assertEquals(1, received.size());
            assertEquals("Hello World", received.get(0));
            verify(mockWebSocket).request(1);
        }

        @Test
        @DisplayName("Should handle multiple fragment text message")
        void shouldHandleMultipleFragmentTextMessage() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            List<String> received = new ArrayList<>();
            textConnection.receive().subscribe(received::add);

            // Simulate fragmented message
            listener.onText(mockWebSocket, "Hello ", false);
            listener.onText(mockWebSocket, "World", true);

            assertEquals(1, received.size());
            assertEquals("Hello World", received.get(0));
            verify(mockWebSocket, times(2)).request(1);
        }

        @Test
        @DisplayName("Should handle single fragment binary message")
        void shouldHandleSingleFragmentBinaryMessage() {
            binaryConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = binaryConnection.getListener();

            List<byte[]> received = new ArrayList<>();
            binaryConnection.receive().subscribe(received::add);

            byte[] data = {0x01, 0x02, 0x03};
            ByteBuffer buffer = ByteBuffer.wrap(data);
            listener.onBinary(mockWebSocket, buffer, true);

            assertEquals(1, received.size());
            assertArrayEquals(data, received.get(0));
            verify(mockWebSocket).request(1);
        }

        @Test
        @DisplayName("Should handle multiple fragment binary message")
        void shouldHandleMultipleFragmentBinaryMessage() {
            binaryConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = binaryConnection.getListener();

            List<byte[]> received = new ArrayList<>();
            binaryConnection.receive().subscribe(received::add);

            // Simulate fragmented binary message
            ByteBuffer buffer1 = ByteBuffer.wrap(new byte[] {0x01, 0x02});
            ByteBuffer buffer2 = ByteBuffer.wrap(new byte[] {0x03, 0x04});
            listener.onBinary(mockWebSocket, buffer1, false);
            listener.onBinary(mockWebSocket, buffer2, true);

            assertEquals(1, received.size());
            assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04}, received.get(0));
            verify(mockWebSocket, times(2)).request(1);
        }

        @Test
        @DisplayName("Should handle binary buffer expansion")
        void shouldHandleBinaryBufferExpansion() {
            binaryConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = binaryConnection.getListener();

            List<byte[]> received = new ArrayList<>();
            binaryConnection.receive().subscribe(received::add);

            // First fragment - small
            byte[] small = new byte[10];
            for (int i = 0; i < 10; i++) small[i] = (byte) i;

            // Second fragment - larger (requires buffer expansion)
            byte[] large = new byte[100];
            for (int i = 0; i < 100; i++) large[i] = (byte) (i + 10);

            listener.onBinary(mockWebSocket, ByteBuffer.wrap(small), false);
            listener.onBinary(mockWebSocket, ByteBuffer.wrap(large), true);

            assertEquals(1, received.size());
            assertEquals(110, received.get(0).length);
        }

        @Test
        @DisplayName("Should handle onClose")
        void shouldHandleOnClose() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            listener.onClose(mockWebSocket, CloseInfo.NORMAL_CLOSURE, "Normal closure");

            assertFalse(textConnection.isOpen());
            assertNotNull(textConnection.getCloseInfo());
            assertEquals(CloseInfo.NORMAL_CLOSURE, textConnection.getCloseInfo().code());
            assertEquals("Normal closure", textConnection.getCloseInfo().reason());
        }

        @Test
        @DisplayName("Should complete receive flux on close")
        void shouldCompleteReceiveFluxOnClose() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            StepVerifier.create(textConnection.receive())
                    .then(() -> listener.onClose(mockWebSocket, CloseInfo.NORMAL_CLOSURE, "Bye"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle onError")
        void shouldHandleOnError() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            listener.onError(mockWebSocket, new RuntimeException("Test error"));

            assertFalse(textConnection.isOpen());
            assertNotNull(textConnection.getCloseInfo());
            assertEquals(CloseInfo.ABNORMAL_CLOSURE, textConnection.getCloseInfo().code());
        }

        @Test
        @DisplayName("Should emit error on receive when onError is called")
        void shouldEmitErrorOnReceiveWhenOnErrorIsCalled() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            StepVerifier.create(textConnection.receive())
                    .then(
                            () ->
                                    listener.onError(
                                            mockWebSocket, new RuntimeException("Connection lost")))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Message Type Conversion Tests")
    class MessageTypeConversionTests {

        @Test
        @DisplayName("Should receive text as String when messageType is String")
        void shouldReceiveTextAsStringWhenMessageTypeIsString() {
            JdkWebSocketConnection<String> connection =
                    new JdkWebSocketConnection<>(TEST_URL, String.class);
            WebSocket mockWebSocket = mock(WebSocket.class);
            connection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = connection.getListener();

            List<String> received = new ArrayList<>();
            connection.receive().subscribe(received::add);

            listener.onText(mockWebSocket, "Hello", true);

            assertEquals(1, received.size());
            assertEquals("Hello", received.get(0));
        }

        @Test
        @DisplayName("Should receive text as byte[] when messageType is byte[]")
        void shouldReceiveTextAsByteArrayWhenMessageTypeIsByteArray() {
            JdkWebSocketConnection<byte[]> connection =
                    new JdkWebSocketConnection<>(TEST_URL, byte[].class);
            WebSocket mockWebSocket = mock(WebSocket.class);
            connection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = connection.getListener();

            List<byte[]> received = new ArrayList<>();
            connection.receive().subscribe(received::add);

            listener.onText(mockWebSocket, "Hello", true);

            assertEquals(1, received.size());
            assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), received.get(0));
        }

        @Test
        @DisplayName("Should receive binary as byte[] when messageType is byte[]")
        void shouldReceiveBinaryAsByteArrayWhenMessageTypeIsByteArray() {
            JdkWebSocketConnection<byte[]> connection =
                    new JdkWebSocketConnection<>(TEST_URL, byte[].class);
            WebSocket mockWebSocket = mock(WebSocket.class);
            connection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = connection.getListener();

            List<byte[]> received = new ArrayList<>();
            connection.receive().subscribe(received::add);

            byte[] data = {0x01, 0x02, 0x03};
            listener.onBinary(mockWebSocket, ByteBuffer.wrap(data), true);

            assertEquals(1, received.size());
            assertArrayEquals(data, received.get(0));
        }

        @Test
        @DisplayName("Should receive binary as String when messageType is String")
        void shouldReceiveBinaryAsStringWhenMessageTypeIsString() {
            JdkWebSocketConnection<String> connection =
                    new JdkWebSocketConnection<>(TEST_URL, String.class);
            WebSocket mockWebSocket = mock(WebSocket.class);
            connection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = connection.getListener();

            List<String> received = new ArrayList<>();
            connection.receive().subscribe(received::add);

            byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
            listener.onBinary(mockWebSocket, ByteBuffer.wrap(data), true);

            assertEquals(1, received.size());
            assertEquals("Hello", received.get(0));
        }
    }

    @Nested
    @DisplayName("Send Message Tests")
    class SendMessageTests {

        private JdkWebSocketConnection<String> textConnection;
        private JdkWebSocketConnection<byte[]> binaryConnection;
        private WebSocket mockWebSocket;

        @BeforeEach
        void setUp() {
            textConnection = new JdkWebSocketConnection<>(TEST_URL, String.class);
            binaryConnection = new JdkWebSocketConnection<>(TEST_URL, byte[].class);
            mockWebSocket = mock(WebSocket.class);
        }

        @Test
        @DisplayName("Should fail send when not connected - text")
        void shouldFailSendWhenNotConnectedText() {
            StepVerifier.create(textConnection.send("hello"))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should fail send when not connected - binary")
        void shouldFailSendWhenNotConnectedBinary() {
            StepVerifier.create(binaryConnection.send("hello".getBytes(StandardCharsets.UTF_8)))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should send text message successfully")
        void shouldSendTextMessageSuccessfully() {
            textConnection.setWebSocket(mockWebSocket);
            when(mockWebSocket.sendText(anyString(), anyBoolean()))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));

            StepVerifier.create(textConnection.send("Hello")).verifyComplete();

            verify(mockWebSocket).sendText("Hello", true);
        }

        @Test
        @DisplayName("Should send binary message successfully")
        void shouldSendBinaryMessageSuccessfully() {
            binaryConnection.setWebSocket(mockWebSocket);
            when(mockWebSocket.sendBinary(
                            org.mockito.ArgumentMatchers.any(ByteBuffer.class), eq(true)))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));

            byte[] data = {0x01, 0x02, 0x03};
            StepVerifier.create(binaryConnection.send(data)).verifyComplete();

            verify(mockWebSocket)
                    .sendBinary(org.mockito.ArgumentMatchers.any(ByteBuffer.class), eq(true));
        }

        @Test
        @DisplayName("Should fail send when WebSocket sendText fails")
        void shouldFailSendWhenWebSocketSendTextFails() {
            textConnection.setWebSocket(mockWebSocket);
            CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Send failed"));
            when(mockWebSocket.sendText(anyString(), anyBoolean())).thenReturn(failedFuture);

            StepVerifier.create(textConnection.send("Hello"))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should fail send when connection is closed")
        void shouldFailSendWhenConnectionIsClosed() {
            textConnection.setWebSocket(mockWebSocket);
            when(mockWebSocket.sendClose(eq(CloseInfo.NORMAL_CLOSURE), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));

            textConnection.close().block();

            StepVerifier.create(textConnection.send("Hello"))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should handle concurrent sends safely")
        void shouldHandleConcurrentSendsSafely() {
            textConnection.setWebSocket(mockWebSocket);
            when(mockWebSocket.sendText(anyString(), anyBoolean()))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));

            List<Mono<Void>> sends =
                    IntStream.range(0, 100)
                            .mapToObj(i -> textConnection.send("msg-" + i))
                            .collect(Collectors.toList());

            Flux.merge(sends).blockLast(Duration.ofSeconds(10));

            verify(mockWebSocket, times(100)).sendText(anyString(), eq(true));
        }
    }

    @Nested
    @DisplayName("Receive Message Tests")
    class ReceiveMessageTests {

        private JdkWebSocketConnection<String> textConnection;
        private WebSocket mockWebSocket;

        @BeforeEach
        void setUp() {
            textConnection = new JdkWebSocketConnection<>(TEST_URL, String.class);
            mockWebSocket = mock(WebSocket.class);
        }

        @Test
        @DisplayName("Should receive multiple messages as Flux")
        void shouldReceiveMultipleMessagesAsFlux() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            StepVerifier.create(textConnection.receive().take(3))
                    .then(() -> listener.onText(mockWebSocket, "Message 1", true))
                    .expectNext("Message 1")
                    .then(() -> listener.onText(mockWebSocket, "Message 2", true))
                    .expectNext("Message 2")
                    .then(() -> listener.onText(mockWebSocket, "Message 3", true))
                    .expectNext("Message 3")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should map non-WebSocketTransportException to WebSocketTransportException")
        void shouldMapNonWebSocketTransportExceptionToWebSocketTransportException() {
            textConnection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = textConnection.getListener();

            StepVerifier.create(textConnection.receive())
                    .then(() -> listener.onError(mockWebSocket, new RuntimeException("Test error")))
                    .expectError(WebSocketTransportException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Close Connection Tests")
    class CloseConnectionTests {

        private JdkWebSocketConnection<String> textConnection;
        private WebSocket mockWebSocket;

        @BeforeEach
        void setUp() {
            textConnection = new JdkWebSocketConnection<>(TEST_URL, String.class);
            mockWebSocket = mock(WebSocket.class);
        }

        @Test
        @DisplayName("Should complete close when not connected")
        void shouldCompleteCloseWhenNotConnected() {
            StepVerifier.create(textConnection.close()).verifyComplete();
        }

        @Test
        @DisplayName("Should close connection successfully")
        void shouldCloseConnectionSuccessfully() {
            textConnection.setWebSocket(mockWebSocket);
            when(mockWebSocket.sendClose(eq(CloseInfo.NORMAL_CLOSURE), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));

            StepVerifier.create(textConnection.close()).verifyComplete();

            assertFalse(textConnection.isOpen());
            assertNotNull(textConnection.getCloseInfo());
            assertEquals(CloseInfo.NORMAL_CLOSURE, textConnection.getCloseInfo().code());
            verify(mockWebSocket).sendClose(CloseInfo.NORMAL_CLOSURE, "");
        }

        @Test
        @DisplayName("Should handle close failure")
        void shouldHandleCloseFailure() {
            textConnection.setWebSocket(mockWebSocket);
            CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Close failed"));
            when(mockWebSocket.sendClose(eq(CloseInfo.NORMAL_CLOSURE), anyString()))
                    .thenReturn(failedFuture);

            StepVerifier.create(textConnection.close())
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should be idempotent when closing multiple times")
        void shouldBeIdempotentWhenClosingMultipleTimes() {
            textConnection.setWebSocket(mockWebSocket);
            when(mockWebSocket.sendClose(eq(CloseInfo.NORMAL_CLOSURE), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(mockWebSocket));

            // First close
            StepVerifier.create(textConnection.close()).verifyComplete();

            // Second close should also complete (without calling sendClose again)
            StepVerifier.create(textConnection.close()).verifyComplete();

            // sendClose should only be called once
            verify(mockWebSocket, times(1)).sendClose(CloseInfo.NORMAL_CLOSURE, "");
        }
    }

    @Nested
    @DisplayName("CloseInfo Tests")
    class CloseInfoTests {

        @Test
        @DisplayName("Should set close info on normal close")
        void shouldSetCloseInfoOnNormalClose() {
            JdkWebSocketConnection<String> connection =
                    new JdkWebSocketConnection<>(TEST_URL, String.class);
            WebSocket mockWebSocket = mock(WebSocket.class);
            connection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = connection.getListener();

            listener.onClose(mockWebSocket, 1000, "Normal closure");

            CloseInfo closeInfo = connection.getCloseInfo();
            assertNotNull(closeInfo);
            assertEquals(1000, closeInfo.code());
            assertEquals("Normal closure", closeInfo.reason());
            assertTrue(closeInfo.isNormal());
        }

        @Test
        @DisplayName("Should set close info on abnormal close")
        void shouldSetCloseInfoOnAbnormalClose() {
            JdkWebSocketConnection<String> connection =
                    new JdkWebSocketConnection<>(TEST_URL, String.class);
            WebSocket mockWebSocket = mock(WebSocket.class);
            connection.setWebSocket(mockWebSocket);
            WebSocket.Listener listener = connection.getListener();

            listener.onError(mockWebSocket, new RuntimeException("Connection lost"));

            CloseInfo closeInfo = connection.getCloseInfo();
            assertNotNull(closeInfo);
            assertEquals(CloseInfo.ABNORMAL_CLOSURE, closeInfo.code());
            assertFalse(closeInfo.isNormal());
        }
    }
}
