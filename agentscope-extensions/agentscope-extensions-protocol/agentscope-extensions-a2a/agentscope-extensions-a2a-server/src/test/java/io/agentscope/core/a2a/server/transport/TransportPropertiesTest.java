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

package io.agentscope.core.a2a.server.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TransportProperties.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>TransportProperties Builder pattern functionality</li>
 *   <li>TransportProperties creation with various configurations</li>
 *   <li>TransportProperties Builder method chaining</li>
 *   <li>Extra properties handling</li>
 * </ul>
 */
@DisplayName("TransportProperties Tests")
class TransportPropertiesTest {

    @Nested
    @DisplayName("TransportProperties Building Tests")
    class TransportPropertiesBuildingTests {

        @Test
        @DisplayName("Should build TransportProperties with all properties")
        void testBuildWithAllProperties() {
            String transportType = "jsonrpc";
            String host = "localhost";
            Integer port = 8080;
            String path = "/api/v1";
            boolean supportTls = true;
            Map<String, Object> extraProps = new HashMap<>();
            extraProps.put("timeout", 5000);
            extraProps.put("retries", 3);

            TransportProperties transportProperties =
                    TransportProperties.builder(transportType)
                            .host(host)
                            .port(port)
                            .path(path)
                            .supportTls(supportTls)
                            .extra(extraProps)
                            .build();

            assertNotNull(transportProperties);
            assertEquals(transportType, transportProperties.transportType());
            assertEquals(host, transportProperties.host());
            assertEquals(port, transportProperties.port());
            assertEquals(path, transportProperties.path());
            assertEquals(supportTls, transportProperties.supportTls());
            assertEquals(extraProps, transportProperties.extra());
        }

        @Test
        @DisplayName("Should build TransportProperties with default values")
        void testBuildWithDefaultValues() {
            String transportType = "jsonrpc";

            TransportProperties transportProperties =
                    TransportProperties.builder(transportType).build();

            assertNotNull(transportProperties);
            assertEquals(transportType, transportProperties.transportType());
            assertNull(transportProperties.host());
            assertNull(transportProperties.port());
            assertNull(transportProperties.path());
            assertFalse(transportProperties.supportTls());
            assertNotNull(transportProperties.extra());
            assertTrue(transportProperties.extra().isEmpty());
        }

        @Test
        @DisplayName("Should support builder method chaining")
        void testBuilderMethodChaining() {
            String transportType = "jsonrpc";
            String host = "localhost";
            Integer port = 8080;
            String path = "/api/v1";
            boolean supportTls = true;
            String key = "timeout";
            Object value = 5000;

            TransportProperties.Builder builder = TransportProperties.builder(transportType);
            TransportProperties.Builder result =
                    builder.host(host)
                            .port(port)
                            .path(path)
                            .supportTls(supportTls)
                            .extra(key, value);

            assertNotNull(result);
            assertSame(builder, result);

            TransportProperties transportProperties = builder.build();

            assertNotNull(transportProperties);
            assertEquals(transportType, transportProperties.transportType());
            assertEquals(host, transportProperties.host());
            assertEquals(port, transportProperties.port());
            assertEquals(path, transportProperties.path());
            assertEquals(supportTls, transportProperties.supportTls());
            assertEquals(value, transportProperties.extra().get(key));
        }
    }
}
