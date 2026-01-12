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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebSocketTransportException Tests")
class WebSocketTransportExceptionTest {

    @Test
    @DisplayName("Should create exception with basic info")
    void shouldCreateExceptionWithBasicInfo() {
        IOException cause = new IOException("Connection refused");
        WebSocketTransportException exception =
                new WebSocketTransportException(
                        "Failed to connect", cause, "wss://example.com", "CONNECTING");

        assertEquals("wss://example.com", exception.getUrl());
        assertEquals("CONNECTING", exception.getConnectionState());
        assertSame(cause, exception.getCause());
        assertTrue(exception.getHeaders().isEmpty());
    }

    @Test
    @DisplayName("Should create exception with headers")
    void shouldCreateExceptionWithHeaders() {
        Map<String, String> headers = Map.of("Authorization", "Bearer token");
        WebSocketTransportException exception =
                new WebSocketTransportException(
                        "Auth failed", null, "wss://example.com", "CONNECTING", headers);

        assertEquals("Bearer token", exception.getHeaders().get("Authorization"));
    }

    @Test
    @DisplayName("Should format message with context")
    void shouldFormatMessageWithContext() {
        WebSocketTransportException exception =
                new WebSocketTransportException(
                        "Send failed", null, "wss://api.example.com/ws", "OPEN");

        String message = exception.getMessage();
        assertTrue(message.contains("Send failed"));
        assertTrue(message.contains("wss://api.example.com/ws"));
        assertTrue(message.contains("OPEN"));
    }

    @Test
    @DisplayName("Headers should be immutable")
    void headersShouldBeImmutable() {
        Map<String, String> headers = Map.of("Key", "Value");
        WebSocketTransportException exception =
                new WebSocketTransportException(
                        "Error", null, "wss://example.com", "CLOSED", headers);

        // Headers returned should be immutable
        Map<String, String> returnedHeaders = exception.getHeaders();
        assertEquals(1, returnedHeaders.size());
    }

    @Test
    @DisplayName("Should handle null headers")
    void shouldHandleNullHeaders() {
        WebSocketTransportException exception =
                new WebSocketTransportException("Error", null, "wss://example.com", "CLOSED", null);

        assertTrue(exception.getHeaders().isEmpty());
    }
}
