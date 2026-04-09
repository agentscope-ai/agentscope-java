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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebSocketRequest Tests")
class WebSocketRequestTest {

    @Test
    @DisplayName("Should build request with url")
    void shouldBuildRequestWithUrl() {
        WebSocketRequest request = WebSocketRequest.builder("wss://example.com").build();

        assertEquals("wss://example.com", request.getUrl());
        assertTrue(request.getHeaders().isEmpty());
    }

    @Test
    @DisplayName("Should build request with headers")
    void shouldBuildRequestWithHeaders() {
        WebSocketRequest request =
                WebSocketRequest.builder("wss://example.com")
                        .header("Authorization", "Bearer token")
                        .header("X-Custom", "value")
                        .build();

        assertEquals("Bearer token", request.getHeaders().get("Authorization"));
        assertEquals("value", request.getHeaders().get("X-Custom"));
    }

    @Test
    @DisplayName("Should build request with timeout")
    void shouldBuildRequestWithTimeout() {
        WebSocketRequest request =
                WebSocketRequest.builder("wss://example.com")
                        .connectTimeout(Duration.ofSeconds(60))
                        .build();

        assertEquals(Duration.ofSeconds(60), request.getConnectTimeout());
    }

    @Test
    @DisplayName("Should have default timeout")
    void shouldHaveDefaultTimeout() {
        WebSocketRequest request = WebSocketRequest.builder("wss://example.com").build();

        assertEquals(Duration.ofSeconds(30), request.getConnectTimeout());
    }

    @Test
    @DisplayName("Headers should be immutable")
    void headersShouldBeImmutable() {
        WebSocketRequest request =
                WebSocketRequest.builder("wss://example.com").header("Key", "Value").build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> request.getHeaders().put("New", "Value"));
    }

    @Test
    @DisplayName("Should throw exception when url is null")
    void shouldThrowExceptionWhenUrlIsNull() {
        assertThrows(NullPointerException.class, () -> WebSocketRequest.builder(null));
    }
}
