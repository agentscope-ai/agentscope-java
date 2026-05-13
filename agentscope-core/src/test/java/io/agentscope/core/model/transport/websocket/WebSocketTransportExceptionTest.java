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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WebSocketTransportException Tests")
class WebSocketTransportExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

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
        @DisplayName("Should create exception with null cause")
        void shouldCreateExceptionWithNullCause() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error occurred", null, "wss://example.com", "OPEN");

            assertNull(exception.getCause());
            assertEquals("wss://example.com", exception.getUrl());
            assertEquals("OPEN", exception.getConnectionState());
        }

        @Test
        @DisplayName("Should create exception with null headers")
        void shouldCreateExceptionWithNullHeaders() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error", null, "wss://example.com", "CLOSED", null);

            assertTrue(exception.getHeaders().isEmpty());
        }

        @Test
        @DisplayName("Should create exception with empty headers")
        void shouldCreateExceptionWithEmptyHeaders() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error", null, "wss://example.com", "CLOSED", Map.of());

            assertTrue(exception.getHeaders().isEmpty());
            assertNotNull(exception.getHeaders());
        }

        @Test
        @DisplayName("Should create exception with multiple headers")
        void shouldCreateExceptionWithMultipleHeaders() {
            Map<String, String> headers =
                    Map.of(
                            "Authorization", "Bearer token",
                            "X-Custom-Header", "value",
                            "Content-Type", "application/json");
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error", null, "wss://example.com", "OPEN", headers);

            assertEquals(3, exception.getHeaders().size());
            assertEquals("Bearer token", exception.getHeaders().get("Authorization"));
            assertEquals("value", exception.getHeaders().get("X-Custom-Header"));
            assertEquals("application/json", exception.getHeaders().get("Content-Type"));
        }
    }

    @Nested
    @DisplayName("Message Formatting Tests")
    class MessageFormattingTests {

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
        @DisplayName("Should format message with CONNECTING state")
        void shouldFormatMessageWithConnectingState() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Connection timeout", null, "wss://example.com", "CONNECTING");

            String message = exception.getMessage();
            assertTrue(message.contains("Connection timeout"));
            assertTrue(message.contains("CONNECTING"));
        }

        @Test
        @DisplayName("Should format message with CLOSED state")
        void shouldFormatMessageWithClosedState() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Already closed", null, "wss://example.com", "CLOSED");

            String message = exception.getMessage();
            assertTrue(message.contains("Already closed"));
            assertTrue(message.contains("CLOSED"));
        }

        @Test
        @DisplayName("Should format message with null URL")
        void shouldFormatMessageWithNullUrl() {
            WebSocketTransportException exception =
                    new WebSocketTransportException("Error", null, null, "OPEN");

            String message = exception.getMessage();
            assertNotNull(message);
            assertTrue(message.contains("Error"));
            assertTrue(message.contains("null"));
        }

        @Test
        @DisplayName("Should format message with null state")
        void shouldFormatMessageWithNullState() {
            WebSocketTransportException exception =
                    new WebSocketTransportException("Error", null, "wss://example.com", null);

            String message = exception.getMessage();
            assertNotNull(message);
            assertTrue(message.contains("Error"));
            assertTrue(message.contains("wss://example.com"));
        }

        @Test
        @DisplayName("Should format message with special characters in URL")
        void shouldFormatMessageWithSpecialCharactersInUrl() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error", null, "wss://example.com/path?query=value&other=123", "OPEN");

            String message = exception.getMessage();
            assertTrue(message.contains("wss://example.com/path?query=value&other=123"));
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("Headers should be immutable")
        void headersShouldBeImmutable() {
            Map<String, String> headers = Map.of("Key", "Value");
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error", null, "wss://example.com", "CLOSED", headers);

            Map<String, String> returnedHeaders = exception.getHeaders();
            assertEquals(1, returnedHeaders.size());

            // Attempt to modify should throw exception
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> returnedHeaders.put("New", "Header"));
        }

        @Test
        @DisplayName("Exception fields should be accessible after creation")
        void exceptionFieldsShouldBeAccessibleAfterCreation() {
            IOException cause = new IOException("Network error");
            Map<String, String> headers = Map.of("Auth", "Bearer xyz");
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Connection lost", cause, "wss://api.test.com", "OPEN", headers);

            // Verify all fields are accessible
            assertEquals("wss://api.test.com", exception.getUrl());
            assertEquals("OPEN", exception.getConnectionState());
            assertSame(cause, exception.getCause());
            assertEquals("Bearer xyz", exception.getHeaders().get("Auth"));
            assertTrue(exception.getMessage().contains("Connection lost"));
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Tests")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("Should be a RuntimeException")
        void shouldBeARuntimeException() {
            WebSocketTransportException exception =
                    new WebSocketTransportException("Error", null, "wss://example.com", "OPEN");

            assertTrue(exception instanceof RuntimeException);
        }

        @Test
        @DisplayName("Should preserve exception chain")
        void shouldPreserveExceptionChain() {
            IOException rootCause = new IOException("Network unreachable");
            RuntimeException intermediateCause =
                    new RuntimeException("Connection failed", rootCause);
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Transport error",
                            intermediateCause,
                            "wss://example.com",
                            "CONNECTING");

            assertSame(intermediateCause, exception.getCause());
            assertSame(rootCause, exception.getCause().getCause());
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty message")
        void shouldHandleEmptyMessage() {
            WebSocketTransportException exception =
                    new WebSocketTransportException("", null, "wss://example.com", "OPEN");

            assertNotNull(exception.getMessage());
            assertTrue(exception.getMessage().contains("wss://example.com"));
        }

        @Test
        @DisplayName("Should handle very long URL")
        void shouldHandleVeryLongUrl() {
            String longUrl = "wss://example.com/" + "a".repeat(1000);
            WebSocketTransportException exception =
                    new WebSocketTransportException("Error", null, longUrl, "OPEN");

            assertTrue(exception.getUrl().length() > 1000);
            assertTrue(exception.getMessage().contains(longUrl));
        }

        @Test
        @DisplayName("Should handle custom connection state")
        void shouldHandleCustomConnectionState() {
            WebSocketTransportException exception =
                    new WebSocketTransportException(
                            "Error", null, "wss://example.com", "CUSTOM_STATE");

            assertEquals("CUSTOM_STATE", exception.getConnectionState());
            assertTrue(exception.getMessage().contains("CUSTOM_STATE"));
        }
    }
}
