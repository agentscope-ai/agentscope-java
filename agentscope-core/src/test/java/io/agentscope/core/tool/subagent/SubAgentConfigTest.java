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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for SubAgentConfig. */
@DisplayName("SubAgentConfig Tests")
class SubAgentConfigTest {

    @Nested
    @DisplayName("defaults() method")
    class DefaultsTests {

        @Test
        @DisplayName("Should create config with default values")
        void testDefaults() {
            SubAgentConfig config = SubAgentConfig.defaults();

            assertNotNull(config);
            assertNull(config.getToolName());
            assertNull(config.getDescription());
            assertTrue(config.isForwardEvents());
            assertNull(config.getStreamOptions());
            assertNotNull(config.getSession());
            assertInstanceOf(InMemorySession.class, config.getSession());
        }

        @Test
        @DisplayName("Should have forwardEvents true by default")
        void testForwardEventsDefaultTrue() {
            SubAgentConfig config = SubAgentConfig.defaults();
            assertTrue(config.isForwardEvents());
        }

        @Test
        @DisplayName("Should use InMemorySession by default")
        void testDefaultSession() {
            SubAgentConfig config = SubAgentConfig.defaults();
            assertInstanceOf(InMemorySession.class, config.getSession());
        }
    }

    @Nested
    @DisplayName("Builder pattern")
    class BuilderTests {

        @Test
        @DisplayName("Should build config with custom tool name")
        void testCustomToolName() {
            SubAgentConfig config = SubAgentConfig.builder().toolName("custom_tool").build();

            assertEquals("custom_tool", config.getToolName());
        }

        @Test
        @DisplayName("Should build config with custom description")
        void testCustomDescription() {
            SubAgentConfig config =
                    SubAgentConfig.builder().description("Custom description").build();

            assertEquals("Custom description", config.getDescription());
        }

        @Test
        @DisplayName("Should build config with forwardEvents disabled")
        void testForwardEventsDisabled() {
            SubAgentConfig config = SubAgentConfig.builder().forwardEvents(false).build();

            assertEquals(false, config.isForwardEvents());
        }

        @Test
        @DisplayName("Should build config with custom StreamOptions")
        void testCustomStreamOptions() {
            StreamOptions streamOptions =
                    StreamOptions.builder()
                            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                            .incremental(true)
                            .build();

            SubAgentConfig config = SubAgentConfig.builder().streamOptions(streamOptions).build();

            assertNotNull(config.getStreamOptions());
            assertEquals(streamOptions, config.getStreamOptions());
        }

        @Test
        @DisplayName("Should build config with custom Session")
        void testCustomSession() {
            Session customSession = new InMemorySession();

            SubAgentConfig config = SubAgentConfig.builder().session(customSession).build();

            assertNotNull(config.getSession());
            assertEquals(customSession, config.getSession());
        }

        @Test
        @DisplayName("Should build config with all custom values")
        void testAllCustomValues() {
            StreamOptions streamOptions = StreamOptions.builder().incremental(false).build();
            Session customSession = new InMemorySession();

            SubAgentConfig config =
                    SubAgentConfig.builder()
                            .toolName("expert_agent")
                            .description("Ask the expert")
                            .forwardEvents(true)
                            .streamOptions(streamOptions)
                            .session(customSession)
                            .build();

            assertEquals("expert_agent", config.getToolName());
            assertEquals("Ask the expert", config.getDescription());
            assertTrue(config.isForwardEvents());
            assertEquals(streamOptions, config.getStreamOptions());
            assertEquals(customSession, config.getSession());
        }

        @Test
        @DisplayName("Should use InMemorySession when session not specified")
        void testDefaultSessionWhenNotSpecified() {
            SubAgentConfig config =
                    SubAgentConfig.builder()
                            .toolName("test_tool")
                            .description("Test description")
                            .build();

            assertNotNull(config.getSession());
            assertInstanceOf(InMemorySession.class, config.getSession());
        }

        @Test
        @DisplayName("Builder should be chainable")
        void testBuilderChaining() {
            SubAgentConfig.Builder builder = SubAgentConfig.builder();

            // All methods should return the builder for chaining
            SubAgentConfig config =
                    builder.toolName("tool")
                            .description("desc")
                            .forwardEvents(true)
                            .streamOptions(null)
                            .session(new InMemorySession())
                            .build();

            assertNotNull(config);
        }
    }

    @Nested
    @DisplayName("Getters")
    class GetterTests {

        @Test
        @DisplayName("getToolName() should return null when not set")
        void testGetToolNameNull() {
            SubAgentConfig config = SubAgentConfig.builder().build();
            assertNull(config.getToolName());
        }

        @Test
        @DisplayName("getDescription() should return null when not set")
        void testGetDescriptionNull() {
            SubAgentConfig config = SubAgentConfig.builder().build();
            assertNull(config.getDescription());
        }

        @Test
        @DisplayName("getStreamOptions() should return null when not set")
        void testGetStreamOptionsNull() {
            SubAgentConfig config = SubAgentConfig.builder().build();
            assertNull(config.getStreamOptions());
        }

        @Test
        @DisplayName("getSession() should never return null")
        void testGetSessionNeverNull() {
            SubAgentConfig config = SubAgentConfig.builder().build();
            assertNotNull(config.getSession());

            SubAgentConfig configWithNullSession = SubAgentConfig.builder().session(null).build();
            assertNotNull(configWithNullSession.getSession());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty tool name")
        void testEmptyToolName() {
            SubAgentConfig config = SubAgentConfig.builder().toolName("").build();
            assertEquals("", config.getToolName());
        }

        @Test
        @DisplayName("Should handle empty description")
        void testEmptyDescription() {
            SubAgentConfig config = SubAgentConfig.builder().description("").build();
            assertEquals("", config.getDescription());
        }

        @Test
        @DisplayName("Multiple builds from same builder should create independent configs")
        void testMultipleBuilds() {
            SubAgentConfig.Builder builder = SubAgentConfig.builder().toolName("tool1");

            SubAgentConfig config1 = builder.build();
            builder.toolName("tool2");
            SubAgentConfig config2 = builder.build();

            assertEquals("tool1", config1.getToolName());
            assertEquals("tool2", config2.getToolName());
        }
    }
}
