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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OkHttpWebSocketClient Tests")
class OkHttpWebSocketClientTest {

    @Test
    @DisplayName("Should create client with default config")
    void shouldCreateClientWithDefaultConfig() {
        WebSocketClient client = OkHttpWebSocketClient.create();

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should create client with custom OkHttpClient")
    void shouldCreateClientWithCustomOkHttpClient() {
        OkHttpClient okHttpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .pingInterval(15, TimeUnit.SECONDS)
                        .build();

        WebSocketClient client = OkHttpWebSocketClient.create(okHttpClient);

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should build request with headers")
    void shouldBuildRequestWithHeaders() {
        WebSocketRequest request =
                WebSocketRequest.builder("wss://example.com")
                        .header("Authorization", "Bearer token")
                        .header("X-Custom", "value")
                        .connectTimeout(Duration.ofSeconds(60))
                        .build();

        assertEquals("wss://example.com", request.getUrl());
        assertEquals("Bearer token", request.getHeaders().get("Authorization"));
        assertEquals("value", request.getHeaders().get("X-Custom"));
        assertEquals(Duration.ofSeconds(60), request.getConnectTimeout());
    }

    @Test
    @DisplayName("Should handle shutdown gracefully")
    void shouldHandleShutdownGracefully() {
        WebSocketClient client = OkHttpWebSocketClient.create();

        // Should not throw
        client.shutdown();
    }
}
