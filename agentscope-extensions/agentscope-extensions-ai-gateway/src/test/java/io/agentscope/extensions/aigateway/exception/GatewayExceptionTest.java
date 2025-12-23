/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.aigateway.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GatewayExceptionTest {

    @Test
    void testExceptionWithMessage() {
        GatewayException exception = new GatewayException("Test error message");

        assertEquals("Test error message", exception.getMessage());
        assertEquals("GATEWAY_ERROR", exception.getErrorCode());
        assertNull(exception.getCause());
        assertTrue(exception.getContext().isEmpty());
    }

    @Test
    void testExceptionWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("Root cause");
        GatewayException exception = new GatewayException("Test error", cause);

        assertEquals("Test error", exception.getMessage());
        assertEquals("GATEWAY_ERROR", exception.getErrorCode());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testExceptionWithCustomErrorCode() {
        GatewayException exception = new GatewayException("CUSTOM_ERROR", "Custom error message");

        assertEquals("Custom error message", exception.getMessage());
        assertEquals("CUSTOM_ERROR", exception.getErrorCode());
        assertNull(exception.getCause());
    }

    @Test
    void testExceptionWithCustomErrorCodeAndCause() {
        RuntimeException cause = new RuntimeException("Root cause");
        GatewayException exception = new GatewayException("CUSTOM_ERROR", "Custom error", cause);

        assertEquals("Custom error", exception.getMessage());
        assertEquals("CUSTOM_ERROR", exception.getErrorCode());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testBuilderWithContext() {
        RuntimeException cause = new RuntimeException("Root cause");
        GatewayException exception =
                GatewayException.builder("CONNECTION_ERROR", "Failed to connect")
                        .cause(cause)
                        .endpoint("https://api.example.com")
                        .toolName("get_weather")
                        .gatewayId("gw-123")
                        .context("extra", "value")
                        .build();

        assertEquals("Failed to connect", exception.getMessage());
        assertEquals("CONNECTION_ERROR", exception.getErrorCode());
        assertEquals(cause, exception.getCause());
        assertEquals("https://api.example.com", exception.getContext().get("endpoint"));
        assertEquals("get_weather", exception.getContext().get("toolName"));
        assertEquals("gw-123", exception.getContext().get("gatewayId"));
        assertEquals("value", exception.getContext().get("extra"));
    }

    @Test
    void testToString() {
        GatewayException exception =
                GatewayException.builder("TEST_ERROR", "Test message")
                        .endpoint("https://example.com")
                        .build();

        String str = exception.toString();
        assertTrue(str.contains("TEST_ERROR"));
        assertTrue(str.contains("Test message"));
        assertTrue(str.contains("endpoint"));
    }
}
