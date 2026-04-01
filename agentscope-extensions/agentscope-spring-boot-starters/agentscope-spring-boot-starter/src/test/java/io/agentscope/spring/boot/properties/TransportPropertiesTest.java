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

import io.agentscope.spring.boot.transport.TransportProviderType;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransportProperties}.
 */
@Tag("unit")
class TransportPropertiesTest {

    @Test
    @DisplayName("Should create a TransportProperties instance with default value")
    void testCreate() {
        TransportProperties properties = new TransportProperties();
        HttpTransportProperties httpProperties = properties.getHttp();
        WebSocketTransportProperties websocketProperties = properties.getWebsocket();
        assertNotNull(properties);
        assertNotNull(httpProperties);
        assertTrue(httpProperties.isEnabled());
        assertSame(TransportProviderType.HttpType.JDK, httpProperties.getType());
        assertEquals(Duration.ofSeconds(30), httpProperties.getConnectTimeout());
        assertEquals(Duration.ofMinutes(5), httpProperties.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), httpProperties.getWriteTimeout());
        assertEquals(5, httpProperties.getMaxIdleConnections());
        assertEquals(Duration.ofMinutes(5), httpProperties.getKeepAliveDuration());
        assertEquals(500, httpProperties.getMaxConnections());
        assertEquals(Duration.ofSeconds(45), httpProperties.getMaxIdleTime());
        assertEquals(Duration.ofSeconds(30), httpProperties.getEvictInBackground());
        assertFalse(httpProperties.isIgnoreSsl());
        assertNull(httpProperties.getProxy());
        assertNotNull(websocketProperties);
        assertTrue(websocketProperties.isEnabled());
        assertSame(TransportProviderType.WebSocketType.JDK, websocketProperties.getType());
        assertEquals(Duration.ofSeconds(30), websocketProperties.getConnectTimeout());
        assertEquals(Duration.ZERO, websocketProperties.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), websocketProperties.getWriteTimeout());
        assertEquals(Duration.ofSeconds(30), websocketProperties.getPingInterval());
        assertFalse(websocketProperties.isIgnoreSsl());
        assertNull(websocketProperties.getProxy());
    }
}
