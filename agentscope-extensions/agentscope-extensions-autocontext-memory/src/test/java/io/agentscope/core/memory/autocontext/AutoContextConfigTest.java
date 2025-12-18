/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AutoContextConfig.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Default values for all configuration fields</li>
 *   <li>Getter and setter methods</li>
 *   <li>Builder pattern functionality</li>
 *   <li>Builder method chaining</li>
 *   <li>Configuration value assignment and retrieval</li>
 * </ul>
 */
@DisplayName("AutoContextConfig Tests")
class AutoContextConfigTest {

    @Test
    @DisplayName("Should have correct default values")
    void testDefaultValues() {
        AutoContextConfig config = new AutoContextConfig();

        assertEquals(5 * 1024, config.getLargePayloadThreshold());
        assertEquals(128 * 1024, config.getMaxToken());
        assertEquals(0.75, config.getTokenRatio());
        assertEquals(200, config.getOffloadSinglePreview());
        assertEquals(100, config.getMsgThreshold());
        assertEquals(50, config.getLastKeep());
        assertEquals(6, config.getMinConsecutiveToolMessages());
    }

    @Test
    @DisplayName("Should set and get largePayloadThreshold")
    void testLargePayloadThreshold() {
        AutoContextConfig config = new AutoContextConfig();
        config.setLargePayloadThreshold(10 * 1024);
        assertEquals(10 * 1024, config.getLargePayloadThreshold());
    }

    @Test
    @DisplayName("Should set and get maxToken")
    void testMaxToken() {
        AutoContextConfig config = new AutoContextConfig();
        config.setMaxToken(64 * 1024);
        assertEquals(64 * 1024, config.getMaxToken());
    }

    @Test
    @DisplayName("Should set and get tokenRatio")
    void testTokenRatio() {
        AutoContextConfig config = new AutoContextConfig();
        config.setTokenRatio(0.8);
        assertEquals(0.8, config.getTokenRatio());
    }

    @Test
    @DisplayName("Should set and get offloadSinglePreview")
    void testOffloadSinglePreview() {
        AutoContextConfig config = new AutoContextConfig();
        config.setOffloadSinglePreview(300);
        assertEquals(300, config.getOffloadSinglePreview());
    }

    @Test
    @DisplayName("Should set and get msgThreshold")
    void testMsgThreshold() {
        AutoContextConfig config = new AutoContextConfig();
        config.setMsgThreshold(50);
        assertEquals(50, config.getMsgThreshold());
    }

    @Test
    @DisplayName("Should set and get lastKeep")
    void testLastKeep() {
        AutoContextConfig config = new AutoContextConfig();
        config.setLastKeep(20);
        assertEquals(20, config.getLastKeep());
    }

    @Test
    @DisplayName("Should set and get minConsecutiveToolMessages")
    void testMinConsecutiveToolMessages() {
        AutoContextConfig config = new AutoContextConfig();
        config.setMinConsecutiveToolMessages(5);
        assertEquals(5, config.getMinConsecutiveToolMessages());
    }

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        AutoContextConfig.Builder builder = AutoContextConfig.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should build config with default values using builder")
    void testBuilderWithDefaults() {
        AutoContextConfig config = AutoContextConfig.builder().build();

        assertEquals(5 * 1024, config.getLargePayloadThreshold());
        assertEquals(128 * 1024, config.getMaxToken());
        assertEquals(0.75, config.getTokenRatio());
        assertEquals(200, config.getOffloadSinglePreview());
        assertEquals(100, config.getMsgThreshold());
        assertEquals(50, config.getLastKeep());
        assertEquals(6, config.getMinConsecutiveToolMessages());
    }

    @Test
    @DisplayName("Should build config with custom values using builder")
    void testBuilderWithCustomValues() {
        AutoContextConfig config =
                AutoContextConfig.builder()
                        .largePayloadThreshold(10 * 1024)
                        .maxToken(64 * 1024)
                        .tokenRatio(0.8)
                        .offloadSinglePreview(300)
                        .msgThreshold(50)
                        .lastKeep(20)
                        .minConsecutiveToolMessages(5)
                        .build();

        assertEquals(10 * 1024, config.getLargePayloadThreshold());
        assertEquals(64 * 1024, config.getMaxToken());
        assertEquals(0.8, config.getTokenRatio());
        assertEquals(300, config.getOffloadSinglePreview());
        assertEquals(50, config.getMsgThreshold());
        assertEquals(20, config.getLastKeep());
        assertEquals(5, config.getMinConsecutiveToolMessages());
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderMethodChaining() {
        AutoContextConfig.Builder builder = AutoContextConfig.builder();

        // All methods should return the builder instance for chaining
        AutoContextConfig.Builder result =
                builder.largePayloadThreshold(1000)
                        .maxToken(2000)
                        .tokenRatio(0.5)
                        .offloadSinglePreview(150)
                        .msgThreshold(30)
                        .lastKeep(10)
                        .minConsecutiveToolMessages(4);

        assertNotNull(result);
        assertEquals(builder, result);
    }

    @Test
    @DisplayName("Should build multiple independent config instances")
    void testMultipleConfigInstances() {
        AutoContextConfig config1 =
                AutoContextConfig.builder().msgThreshold(50).lastKeep(10).build();

        AutoContextConfig config2 =
                AutoContextConfig.builder().msgThreshold(100).lastKeep(20).build();

        // Configs should be independent
        assertEquals(50, config1.getMsgThreshold());
        assertEquals(10, config1.getLastKeep());
        assertEquals(100, config2.getMsgThreshold());
        assertEquals(20, config2.getLastKeep());
    }

    @Test
    @DisplayName("Should allow partial configuration using builder")
    void testPartialBuilderConfiguration() {
        AutoContextConfig config =
                AutoContextConfig.builder().msgThreshold(75).maxToken(96 * 1024).build();

        // Custom values
        assertEquals(75, config.getMsgThreshold());
        assertEquals(96 * 1024, config.getMaxToken());

        // Default values should still apply
        assertEquals(5 * 1024, config.getLargePayloadThreshold());
        assertEquals(0.75, config.getTokenRatio());
        assertEquals(200, config.getOffloadSinglePreview());
        assertEquals(50, config.getLastKeep());
        assertEquals(6, config.getMinConsecutiveToolMessages());
    }
}
