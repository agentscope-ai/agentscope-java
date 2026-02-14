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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.ProxyConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebSocketClientConfigTest {

    @Test
    void testDefaults() {
        WebSocketClientConfig config = WebSocketClientConfig.defaults();

        assertEquals(Duration.ofSeconds(30), config.getConnectTimeout());
        assertEquals(Duration.ZERO, config.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), config.getWriteTimeout());
        assertEquals(Duration.ofSeconds(30), config.getPingInterval());
        assertNull(config.getProxyConfig());
        assertFalse(config.isIgnoreSsl());
    }

    @Test
    void testBuilderWithCustomValues() {
        WebSocketClientConfig config =
                WebSocketClientConfig.builder()
                        .connectTimeout(Duration.ofSeconds(60))
                        .readTimeout(Duration.ofSeconds(120))
                        .writeTimeout(Duration.ofSeconds(45))
                        .pingInterval(Duration.ofSeconds(15))
                        .ignoreSsl(true)
                        .build();

        assertEquals(Duration.ofSeconds(60), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(120), config.getReadTimeout());
        assertEquals(Duration.ofSeconds(45), config.getWriteTimeout());
        assertEquals(Duration.ofSeconds(15), config.getPingInterval());
        assertTrue(config.isIgnoreSsl());
    }

    @Test
    void testBuilderWithProxy() {
        ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);

        WebSocketClientConfig config = WebSocketClientConfig.builder().proxy(proxyConfig).build();

        assertNotNull(config.getProxyConfig());
        assertEquals("proxy.example.com", config.getProxyConfig().getHost());
        assertEquals(8080, config.getProxyConfig().getPort());
    }

    @Test
    void testBuilderWithProxyAndAuth() {
        ProxyConfig proxyConfig = ProxyConfig.socks5("socks.example.com", 1080, "user", "pass");

        WebSocketClientConfig config = WebSocketClientConfig.builder().proxy(proxyConfig).build();

        assertNotNull(config.getProxyConfig());
        assertEquals("socks.example.com", config.getProxyConfig().getHost());
        assertEquals(1080, config.getProxyConfig().getPort());
        assertTrue(config.getProxyConfig().hasAuthentication());
    }
}
