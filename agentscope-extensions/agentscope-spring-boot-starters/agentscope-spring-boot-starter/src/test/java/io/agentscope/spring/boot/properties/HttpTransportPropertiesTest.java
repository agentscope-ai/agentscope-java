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

import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.spring.boot.transport.TransportProviderType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpTransportProperties}.
 */
@Tag("unit")
class HttpTransportPropertiesTest {

    @Test
    @DisplayName("Should create a HttpTransportProperties instance with default value")
    void testCreateDefault() {
        HttpTransportProperties properties = new HttpTransportProperties();
        assertNotNull(properties);
        assertTrue(properties.isEnabled());
        assertSame(TransportProviderType.HttpType.JDK, properties.getType());
        assertEquals(Duration.ofSeconds(30), properties.getConnectTimeout());
        assertEquals(Duration.ofMinutes(5), properties.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), properties.getWriteTimeout());
        assertEquals(5, properties.getMaxIdleConnections());
        assertEquals(Duration.ofMinutes(5), properties.getKeepAliveDuration());
        assertEquals(500, properties.getMaxConnections());
        assertEquals(Duration.ofSeconds(45), properties.getMaxIdleTime());
        assertEquals(Duration.ofSeconds(30), properties.getEvictInBackground());
        assertFalse(properties.isIgnoreSsl());
        assertNull(properties.getProxy());
    }

    @Test
    @DisplayName("Should get and set correct value")
    void testGetterSetter() {
        Duration connectTimeout = Duration.ofSeconds(60);
        Duration readTimeout = Duration.ofMinutes(10);
        Duration writeTimeout = Duration.ofMinutes(10);
        Duration keepAlive = Duration.ofMinutes(3);
        Duration maxIdleTime = Duration.ofMinutes(1);
        Duration evictInBackground = Duration.ofMinutes(1);
        ProxyProperties proxy = new ProxyProperties();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8080);
        proxy.setUsername("username");
        proxy.setPassword("password");

        HttpTransportProperties properties = new HttpTransportProperties();
        assertNotNull(properties);
        properties.setEnabled(false);
        properties.setType(TransportProviderType.HttpType.OKHTTP);
        properties.setConnectTimeout(connectTimeout);
        properties.setReadTimeout(readTimeout);
        properties.setWriteTimeout(writeTimeout);
        properties.setMaxIdleConnections(10);
        properties.setKeepAliveDuration(keepAlive);
        properties.setMaxConnections(1000);
        properties.setMaxIdleTime(maxIdleTime);
        properties.setEvictInBackground(evictInBackground);
        properties.setIgnoreSsl(true);
        properties.setProxy(proxy);

        assertFalse(properties.isEnabled());
        assertSame(TransportProviderType.HttpType.OKHTTP, properties.getType());
        assertSame(connectTimeout, properties.getConnectTimeout());
        assertSame(readTimeout, properties.getReadTimeout());
        assertSame(writeTimeout, properties.getWriteTimeout());
        assertEquals(10, properties.getMaxIdleConnections());
        assertSame(keepAlive, properties.getKeepAliveDuration());
        assertEquals(1000, properties.getMaxConnections());
        assertSame(maxIdleTime, properties.getMaxIdleTime());
        assertSame(evictInBackground, properties.getEvictInBackground());
        assertTrue(properties.isIgnoreSsl());
        assertNotNull(properties.getProxy());
        assertSame(ProxyType.HTTP, properties.getProxy().getType());
        assertEquals("proxy.example.com", properties.getProxy().getHost());
        assertEquals(8080, properties.getProxy().getPort());
        assertEquals("username", properties.getProxy().getUsername());
        assertEquals("password", properties.getProxy().getPassword());
    }

    @Test
    @DisplayName("Should convert to TransportConfig with default value")
    void testToTransportConfigWithDefaultValue() {
        HttpTransportProperties properties = new HttpTransportProperties();

        HttpTransportConfig config = properties.toTransportConfig();

        assertNotNull(config);
        assertEquals(HttpTransportConfig.DEFAULT_CONNECT_TIMEOUT, config.getConnectTimeout());
        assertEquals(HttpTransportConfig.DEFAULT_READ_TIMEOUT, config.getReadTimeout());
        assertEquals(HttpTransportConfig.DEFAULT_WRITE_TIMEOUT, config.getWriteTimeout());
        assertEquals(5, config.getMaxIdleConnections());
        assertEquals(Duration.ofMinutes(5), config.getKeepAliveDuration());
        assertEquals(500, config.getMaxConnections());
        assertEquals(Duration.ofSeconds(45), config.getMaxIdleTime());
        assertEquals(Duration.ofSeconds(30), config.getEvictInBackground());
        assertFalse(config.isIgnoreSsl());
        assertNull(config.getProxyConfig());
    }

    @Test
    @DisplayName("Should convert to HttpTransportConfig with custom value")
    void testToTransportConfigWithCustomValue() {
        Set<String> nonProxyHosts = Set.of("nonproxy1.com", "nonproxy2.com");
        ProxyProperties proxy = new ProxyProperties();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8080);
        proxy.setUsername("username");
        proxy.setPassword("password");
        proxy.setType(ProxyType.SOCKS5);
        proxy.setNonProxyHosts(nonProxyHosts);

        HttpTransportProperties properties = new HttpTransportProperties();
        properties.setConnectTimeout(Duration.ofSeconds(60));
        properties.setReadTimeout(Duration.ofMinutes(10));
        properties.setWriteTimeout(Duration.ofSeconds(45));
        properties.setMaxIdleConnections(10);
        properties.setKeepAliveDuration(Duration.ofMinutes(3));
        properties.setMaxConnections(1000);
        properties.setMaxIdleTime(Duration.ofMinutes(1));
        properties.setEvictInBackground(Duration.ofMinutes(1));
        properties.setIgnoreSsl(true);
        properties.setProxy(proxy);

        HttpTransportConfig config = properties.toTransportConfig();

        assertNotNull(config);
        assertEquals(Duration.ofSeconds(60), config.getConnectTimeout());
        assertEquals(Duration.ofMinutes(10), config.getReadTimeout());
        assertEquals(Duration.ofSeconds(45), config.getWriteTimeout());
        assertEquals(10, config.getMaxIdleConnections());
        assertEquals(Duration.ofMinutes(3), config.getKeepAliveDuration());
        assertEquals(1000, config.getMaxConnections());
        assertEquals(Duration.ofMinutes(1), config.getMaxIdleTime());
        assertEquals(Duration.ofMinutes(1), config.getEvictInBackground());
        assertTrue(config.isIgnoreSsl());
        assertNotNull(config.getProxyConfig());
        assertSame(ProxyType.SOCKS5, config.getProxyConfig().getType());
        assertEquals("proxy.example.com", config.getProxyConfig().getHost());
        assertEquals(8080, config.getProxyConfig().getPort());
        assertEquals("username", properties.getProxy().getUsername());
        assertEquals("password", properties.getProxy().getPassword());
        assertTrue(config.getProxyConfig().getNonProxyHosts().contains("nonproxy1.com"));
        assertTrue(config.getProxyConfig().getNonProxyHosts().contains("nonproxy2.com"));
    }
}
