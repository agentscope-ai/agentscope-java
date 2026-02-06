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
package io.agentscope.core.live.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("JdkWebSocketConnection Tests")
class JdkWebSocketConnectionTest {

    private JdkWebSocketConnection<String> textConnection;
    private JdkWebSocketConnection<byte[]> binaryConnection;

    @BeforeEach
    void setUp() {
        textConnection = new JdkWebSocketConnection<>("wss://example.com", String.class);
        binaryConnection = new JdkWebSocketConnection<>("wss://example.com", byte[].class);
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
    @DisplayName("Should complete close when not connected")
    void shouldCompleteCloseWhenNotConnected() {
        StepVerifier.create(textConnection.close()).verifyComplete();
        StepVerifier.create(binaryConnection.close()).verifyComplete();
    }

    @Test
    @DisplayName("Should have listener available")
    void shouldHaveListenerAvailable() {
        assertNotNull(textConnection.getListener());
        assertNotNull(binaryConnection.getListener());
    }
}
