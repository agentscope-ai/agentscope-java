/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.websocket.WebSocketTransportConfig;
import io.agentscope.spring.boot.transport.TransportProviderType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketTransportProperties}.
 */
@Tag("unit")
class WebSocketTransportPropertiesTest {

    @Test
    @DisplayName("Should create a WebSocketTransportProperties instance with default value")
    void testCreateDefault() {
        WebSocketTransportProperties properties = new WebSocketTransportProperties();

        assertNotNull(properties);
        assertTrue(properties.isEnabled());
        assertSame(TransportProviderType.WebSocketType.JDK, properties.getType());
        assertEquals(Duration.ofSeconds(30), properties.getConnectTimeout());
        assertEquals(Duration.ZERO, properties.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), properties.getWriteTimeout());
        assertEquals(Duration.ofSeconds(30), properties.getPingInterval());
        assertFalse(properties.isIgnoreSsl());
        assertNull(properties.getProxy());
    }

    @Test
    @DisplayName("Should get and set correct value")
    void testGetterSetter() {
        Duration connectTimeout = Duration.ofSeconds(60);
        Duration readTimeout = Duration.ofMinutes(10);
        Duration writeTimeout = Duration.ofMinutes(10);
        Duration pingInterval = Duration.ofSeconds(45);

        ProxyProperties proxy = new ProxyProperties();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8080);
        proxy.setUsername("username");
        proxy.setPassword("password");

        WebSocketTransportProperties properties = new WebSocketTransportProperties();
        properties.setEnabled(false);
        properties.setType(TransportProviderType.WebSocketType.OKHTTP);
        properties.setConnectTimeout(connectTimeout);
        properties.setReadTimeout(readTimeout);
        properties.setWriteTimeout(writeTimeout);
        properties.setPingInterval(pingInterval);
        properties.setIgnoreSsl(true);
        properties.setProxy(proxy);

        assertNotNull(properties);
        assertFalse(properties.isEnabled());
        assertSame(TransportProviderType.WebSocketType.OKHTTP, properties.getType());
        assertSame(connectTimeout, properties.getConnectTimeout());
        assertSame(readTimeout, properties.getReadTimeout());
        assertSame(writeTimeout, properties.getWriteTimeout());
        assertSame(pingInterval, properties.getPingInterval());
        assertTrue(properties.isIgnoreSsl());
        assertNotNull(properties.getProxy());
        assertEquals("proxy.example.com", properties.getProxy().getHost());
        assertEquals(8080, properties.getProxy().getPort());
        assertEquals("username", properties.getProxy().getUsername());
        assertEquals("password", properties.getProxy().getPassword());
    }

    @Test
    @DisplayName("Should convert to WebSocketTransportConfig with default value")
    void testToTransportConfigWithDefaultValue() {
        WebSocketTransportProperties properties = new WebSocketTransportProperties();

        WebSocketTransportConfig config = properties.toTransportConfig();

        assertNotNull(config);
        assertEquals(WebSocketTransportConfig.DEFAULT_CONNECT_TIMEOUT, config.getConnectTimeout());
        assertEquals(WebSocketTransportConfig.DEFAULT_READ_TIMEOUT, config.getReadTimeout());
        assertEquals(WebSocketTransportConfig.DEFAULT_WRITE_TIMEOUT, config.getWriteTimeout());
        assertEquals(WebSocketTransportConfig.DEFAULT_PING_INTERVAL, config.getPingInterval());
        assertFalse(config.isIgnoreSsl());
        assertNull(config.getProxyConfig());
    }

    @Test
    @DisplayName("Should convert to WebSocketTransportConfig with custom value")
    void testToTransportConfigWithCustomValue() {
        Set<String> nonProxyHosts = Set.of("nonproxy1.com", "nonproxy2.com");
        ProxyProperties proxy = new ProxyProperties();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8080);
        proxy.setUsername("username");
        proxy.setPassword("password");
        proxy.setType(ProxyType.SOCKS5);
        proxy.setNonProxyHosts(nonProxyHosts);

        WebSocketTransportProperties properties = new WebSocketTransportProperties();
        properties.setConnectTimeout(Duration.ofSeconds(60));
        properties.setReadTimeout(Duration.ofMinutes(10));
        properties.setWriteTimeout(Duration.ofSeconds(45));
        properties.setPingInterval(Duration.ofMinutes(2));
        properties.setIgnoreSsl(true);
        properties.setProxy(proxy);

        WebSocketTransportConfig config = properties.toTransportConfig();

        assertNotNull(config);
        assertEquals(Duration.ofSeconds(60), config.getConnectTimeout());
        assertEquals(Duration.ofMinutes(10), config.getReadTimeout());
        assertEquals(Duration.ofSeconds(45), config.getWriteTimeout());
        assertEquals(Duration.ofMinutes(2), config.getPingInterval());
        assertTrue(config.isIgnoreSsl());

        assertNotNull(config.getProxyConfig());
        assertEquals("proxy.example.com", config.getProxyConfig().getHost());
        assertEquals(8080, config.getProxyConfig().getPort());
        assertEquals("username", properties.getProxy().getUsername());
        assertEquals("password", properties.getProxy().getPassword());
        assertTrue(config.getProxyConfig().getNonProxyHosts().contains("nonproxy1.com"));
        assertTrue(config.getProxyConfig().getNonProxyHosts().contains("nonproxy2.com"));
    }
}
