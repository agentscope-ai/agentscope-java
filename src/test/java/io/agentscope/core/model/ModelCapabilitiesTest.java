/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelCapabilitiesTest {

    @Test
    void testDefaultConstructor() {
        ModelCapabilities capabilities = new ModelCapabilities();

        assertFalse(capabilities.supportsNativeStructuredOutput());
        assertTrue(capabilities.supportsToolCalling());
        assertFalse(capabilities.supportsVision());
        assertFalse(capabilities.supportsThinking());
    }

    @Test
    void testBuilder() {
        ModelCapabilities capabilities =
                ModelCapabilities.builder()
                        .supportsNativeStructuredOutput(true)
                        .supportsToolCalling(true)
                        .supportsVision(false)
                        .supportsThinking(true)
                        .build();

        assertTrue(capabilities.supportsNativeStructuredOutput());
        assertTrue(capabilities.supportsToolCalling());
        assertFalse(capabilities.supportsVision());
        assertTrue(capabilities.supportsThinking());
    }

    @Test
    void testBuilderPartial() {
        ModelCapabilities capabilities =
                ModelCapabilities.builder().supportsToolCalling(true).build();

        assertFalse(capabilities.supportsNativeStructuredOutput());
        assertTrue(capabilities.supportsToolCalling());
        assertFalse(capabilities.supportsVision());
        assertFalse(capabilities.supportsThinking());
    }

    @Test
    void testAllCapabilitiesEnabled() {
        ModelCapabilities capabilities =
                ModelCapabilities.builder()
                        .supportsNativeStructuredOutput(true)
                        .supportsToolCalling(true)
                        .supportsVision(true)
                        .supportsThinking(true)
                        .build();

        assertTrue(capabilities.supportsNativeStructuredOutput());
        assertTrue(capabilities.supportsToolCalling());
        assertTrue(capabilities.supportsVision());
        assertTrue(capabilities.supportsThinking());
    }

    @Test
    void testAllCapabilitiesDisabled() {
        ModelCapabilities capabilities =
                ModelCapabilities.builder()
                        .supportsNativeStructuredOutput(false)
                        .supportsToolCalling(false)
                        .supportsVision(false)
                        .supportsThinking(false)
                        .build();

        assertFalse(capabilities.supportsNativeStructuredOutput());
        assertFalse(capabilities.supportsToolCalling());
        assertFalse(capabilities.supportsVision());
        assertFalse(capabilities.supportsThinking());
    }
}
