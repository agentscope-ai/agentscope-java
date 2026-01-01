/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package io.agentscope.core.nacos.a2a.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NacosA2aRegistryTransportProperties} Builder validation. Focuses on testing invalid parameter
 * scenarios during build.
 */
class NacosA2aRegistryTransportPropertiesTest {

    @Test
    @DisplayName("Should throw IllegalArgumentException when transport is null")
    void testBuildWithNullTransport() {
        NacosA2aRegistryTransportProperties.Builder builder =
                NacosA2aRegistryTransportProperties.builder().host("localhost"); // Valid host

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("A2A Endpoint `transport` can not be empty.", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when transport is empty string")
    void testBuildWithEmptyTransport() {
        NacosA2aRegistryTransportProperties.Builder builder =
                NacosA2aRegistryTransportProperties.builder()
                        .transport("") // Empty transport
                        .host("localhost"); // Valid host

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("A2A Endpoint `transport` can not be empty.", exception.getMessage());
    }
}
