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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.ToolMergeMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiAdapterConfig.
 */
class AguiAdapterConfigTest {

    @Test
    void testDefaultConfig() {
        AguiAdapterConfig config = AguiAdapterConfig.defaultConfig();

        assertNotNull(config);
        assertEquals(ToolMergeMode.MERGE_FRONTEND_PRIORITY, config.getToolMergeMode());
        assertTrue(config.isEmitStateEvents());
        assertTrue(config.isEmitToolCallArgs());
        assertEquals(Duration.ofMinutes(10), config.getRunTimeout());
        assertNull(config.getDefaultAgentId());
    }

    @Test
    void testBuilderWithDefaults() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().build();

        assertNotNull(config);
        assertEquals(ToolMergeMode.MERGE_FRONTEND_PRIORITY, config.getToolMergeMode());
        assertTrue(config.isEmitStateEvents());
        assertTrue(config.isEmitToolCallArgs());
        assertEquals(Duration.ofMinutes(10), config.getRunTimeout());
    }

    @Test
    void testBuilderToolMergeMode() {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder().toolMergeMode(ToolMergeMode.FRONTEND_ONLY).build();

        assertEquals(ToolMergeMode.FRONTEND_ONLY, config.getToolMergeMode());
    }

    @Test
    void testBuilderAgentOnlyMode() {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder().toolMergeMode(ToolMergeMode.AGENT_ONLY).build();

        assertEquals(ToolMergeMode.AGENT_ONLY, config.getToolMergeMode());
    }

    @Test
    void testBuilderEmitStateEvents() {
        AguiAdapterConfig configDisabled =
                AguiAdapterConfig.builder().emitStateEvents(false).build();

        assertFalse(configDisabled.isEmitStateEvents());

        AguiAdapterConfig configEnabled = AguiAdapterConfig.builder().emitStateEvents(true).build();

        assertTrue(configEnabled.isEmitStateEvents());
    }

    @Test
    void testBuilderEmitToolCallArgs() {
        AguiAdapterConfig configDisabled =
                AguiAdapterConfig.builder().emitToolCallArgs(false).build();

        assertFalse(configDisabled.isEmitToolCallArgs());

        AguiAdapterConfig configEnabled =
                AguiAdapterConfig.builder().emitToolCallArgs(true).build();

        assertTrue(configEnabled.isEmitToolCallArgs());
    }

    @Test
    void testBuilderRunTimeout() {
        Duration customTimeout = Duration.ofMinutes(30);
        AguiAdapterConfig config = AguiAdapterConfig.builder().runTimeout(customTimeout).build();

        assertEquals(customTimeout, config.getRunTimeout());
    }

    @Test
    void testBuilderShortTimeout() {
        Duration shortTimeout = Duration.ofSeconds(30);
        AguiAdapterConfig config = AguiAdapterConfig.builder().runTimeout(shortTimeout).build();

        assertEquals(Duration.ofSeconds(30), config.getRunTimeout());
    }

    @Test
    void testBuilderDefaultAgentId() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().defaultAgentId("agent-123").build();

        assertEquals("agent-123", config.getDefaultAgentId());
    }

    @Test
    void testBuilderNullDefaultAgentId() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().defaultAgentId(null).build();

        assertNull(config.getDefaultAgentId());
    }

    @Test
    void testBuilderFullConfiguration() {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder()
                        .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                        .emitStateEvents(false)
                        .emitToolCallArgs(false)
                        .runTimeout(Duration.ofHours(1))
                        .defaultAgentId("my-agent")
                        .build();

        assertEquals(ToolMergeMode.AGENT_ONLY, config.getToolMergeMode());
        assertFalse(config.isEmitStateEvents());
        assertFalse(config.isEmitToolCallArgs());
        assertEquals(Duration.ofHours(1), config.getRunTimeout());
        assertEquals("my-agent", config.getDefaultAgentId());
    }

    @Test
    void testBuilderMethod() {
        AguiAdapterConfig.Builder builder = AguiAdapterConfig.builder();

        assertNotNull(builder);
    }

    @Test
    void testBuilderChaining() {
        // Verify builder method chaining returns the same builder
        AguiAdapterConfig.Builder builder = AguiAdapterConfig.builder();

        AguiAdapterConfig.Builder result =
                builder.toolMergeMode(ToolMergeMode.FRONTEND_ONLY)
                        .emitStateEvents(true)
                        .emitToolCallArgs(true)
                        .runTimeout(Duration.ofMinutes(5))
                        .defaultAgentId("agent");

        assertNotNull(result);

        AguiAdapterConfig config = result.build();
        assertNotNull(config);
    }

    @Test
    void testMultipleBuilds() {
        // Builder can be used to create multiple configs
        AguiAdapterConfig.Builder builder =
                AguiAdapterConfig.builder().toolMergeMode(ToolMergeMode.FRONTEND_ONLY);

        AguiAdapterConfig config1 = builder.build();
        AguiAdapterConfig config2 = builder.emitStateEvents(false).build();

        assertEquals(ToolMergeMode.FRONTEND_ONLY, config1.getToolMergeMode());
        assertEquals(ToolMergeMode.FRONTEND_ONLY, config2.getToolMergeMode());
        assertTrue(config1.isEmitStateEvents());
        assertFalse(config2.isEmitStateEvents());
    }

    @Test
    void testDifferentTimeoutUnits() {
        AguiAdapterConfig secondsConfig =
                AguiAdapterConfig.builder().runTimeout(Duration.ofSeconds(90)).build();

        assertEquals(90, secondsConfig.getRunTimeout().getSeconds());

        AguiAdapterConfig millisConfig =
                AguiAdapterConfig.builder().runTimeout(Duration.ofMillis(5000)).build();

        assertEquals(5000, millisConfig.getRunTimeout().toMillis());
    }

    @Test
    void testZeroTimeout() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().runTimeout(Duration.ZERO).build();

        assertEquals(Duration.ZERO, config.getRunTimeout());
    }

    @Test
    void testEmptyDefaultAgentId() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().defaultAgentId("").build();

        assertEquals("", config.getDefaultAgentId());
    }

    @Test
    void testAllToolMergeModes() {
        for (ToolMergeMode mode : ToolMergeMode.values()) {
            AguiAdapterConfig config = AguiAdapterConfig.builder().toolMergeMode(mode).build();
            assertEquals(mode, config.getToolMergeMode());
        }
    }
}
