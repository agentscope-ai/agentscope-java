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
package io.agentscope.core.tool.subagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SubAgentHook basic functionality tests
 *
 * <p>Coverage includes:
 * <ul>
 *   <li>Basic functionalities: context return, priority, result injection</li>
 *   <li>Null handling: null tool use, null input, null metadata</li>
 *   <li>Boundary conditions: empty string tool ID</li>
 *   <li>State consistency: multiple executions, metadata preservation, interleaved operations</li>
 *   <li>Exception handling: empty pending result list</li>
 *   <li>Integration: SubAgentContext state management</li>
 * </ul>
 *
 */
@DisplayName("SubAgentHook Basic Tests")
class SubAgentHookTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private SubAgentContext context;
    private SubAgentHook hook;

    @BeforeEach
    void setUp() {
        context = new SubAgentContext();
        hook = new SubAgentHook(context);
    }

    @Nested
    @DisplayName("Basic Functionality Tests")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Should return correct context")
        void testGetContext() {
            assertSame(context, hook.getContext());
        }

        @Test
        @DisplayName("Should have correct priority")
        void testPriority() {
            assertEquals(10, hook.priority());
        }

        @Test
        @DisplayName("Should inject pending result into tool use")
        void testInjectPendingResult() {
            // Sub-agent tool ID (the tool that invokes the sub-agent)
            String subAgentToolId = "tool-123";
            String sessionId = "session-abc";

            // Internal tool ID used by the sub-agent when it calls tools
            String internalToolId = "internal-tool-456";
            ToolResultBlock pendingResult = createToolResultBlock(internalToolId, "Pending result");
            context.setSessionId(subAgentToolId, sessionId);
            context.submitSubAgentResult(subAgentToolId, pendingResult);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);

            assertNotNull(result);
            ToolUseBlock modifiedToolUse = result.getToolUse();
            assertNotNull(modifiedToolUse);

            assertTrue(
                    modifiedToolUse.getMetadata().containsKey(SubAgentHook.PREVIOUS_TOOL_RESULT));
            assertEquals(sessionId, modifiedToolUse.getInput().get("session_id"));
        }

        @Test
        @DisplayName("Should not modify tool use when no pending result")
        void testNoPendingResult() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id("tool-456")
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);

            assertNotNull(result);
            ToolUseBlock resultToolUse = result.getToolUse();
            assertEquals(toolUse.getId(), resultToolUse.getId());
            assertFalse(resultToolUse.getMetadata().containsKey(SubAgentHook.PREVIOUS_TOOL_RESULT));
        }

        @Test
        @DisplayName("Should inject multiple pending results")
        void testInjectMultiplePendingResults() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-multi";
            String sessionId = "session-multi";

            // Internal tool IDs used by the sub-agent
            String internalToolId1 = "internal-tool-1";
            String internalToolId2 = "internal-tool-2";
            ToolResultBlock result1 = createToolResultBlock(internalToolId1, "Result 1");
            ToolResultBlock result2 = createToolResultBlock(internalToolId2, "Result 2");

            context.setSessionId(subAgentToolId, sessionId);
            context.submitSubAgentResults(subAgentToolId, List.of(result1, result2));

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);

            assertNotNull(result);
            ToolUseBlock modifiedToolUse = result.getToolUse();

            @SuppressWarnings("unchecked")
            List<ToolResultBlock> injectedResults =
                    (List<ToolResultBlock>)
                            modifiedToolUse.getMetadata().get(SubAgentHook.PREVIOUS_TOOL_RESULT);
            assertNotNull(injectedResults);
            assertEquals(2, injectedResults.size());
        }

        @Test
        @DisplayName("Should consume pending results after injection")
        void testConsumePendingResultsAfterInjection() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-consume";
            String sessionId = "session-consume";

            // Internal tool ID used by the sub-agent
            String internalToolId = "internal-tool-consume";
            ToolResultBlock pendingResult = createToolResultBlock(internalToolId, "Pending result");
            context.setSessionId(subAgentToolId, sessionId);
            context.submitSubAgentResult(subAgentToolId, pendingResult);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            hook.onEvent(event).block(TEST_TIMEOUT);

            // Verify pending result was consumed
            assertFalse(context.hasPendingResult(subAgentToolId));
        }
    }

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null tool use")
        void testNullToolUse() {
            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, null);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle null input in tool use")
        void testNullInput() {
            ToolUseBlock toolUse =
                    ToolUseBlock.builder().id("tool-789").name("test_tool").input(null).build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle null metadata in tool use")
        void testNullMetadata() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-null-meta";
            // Internal tool ID used by the sub-agent
            String internalToolId = "internal-tool-null";
            ToolResultBlock pendingResult = createToolResultBlock(internalToolId, "Result");
            context.setSessionId(subAgentToolId, "session-null");
            context.submitSubAgentResult(subAgentToolId, pendingResult);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);
            assertNotNull(result);
            ToolUseBlock modifiedToolUse = result.getToolUse();
            assertNotNull(modifiedToolUse.getMetadata());
        }
    }

    @Nested
    @DisplayName("Boundary Condition Tests")
    class BoundaryConditionTests {
        @Test
        @DisplayName("Should handle empty string tool ID")
        void testEmptyStringToolId() {
            // Sub-agent tool ID (empty string for boundary test)
            String subAgentToolId = "";
            // Internal tool ID used by the sub-agent
            String internalToolId = "internal-tool-empty";
            ToolResultBlock pendingResult = createToolResultBlock(internalToolId, "Pending result");
            context.setSessionId(subAgentToolId, "session-empty");
            context.submitSubAgentResult(subAgentToolId, pendingResult);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("State Consistency Tests")
    class StateConsistencyTests {

        @Test
        @DisplayName("Should maintain state after multiple hook executions")
        void testStateAfterMultipleExecutions() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-repeat";

            // First execution - internal tool ID used by the sub-agent
            String internalToolId1 = "internal-tool-repeat-1";
            ToolResultBlock result1 = createToolResultBlock(internalToolId1, "Result 1");
            context.setSessionId(subAgentToolId, "session-1");
            context.submitSubAgentResult(subAgentToolId, result1);

            ToolUseBlock toolUse1 =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event1 = new PreActingEvent(mockAgent, mockToolkit, toolUse1);
            hook.onEvent(event1).block(TEST_TIMEOUT);

            assertFalse(context.hasPendingResult(subAgentToolId));

            // Second execution with new result - different internal tool ID
            String internalToolId2 = "internal-tool-repeat-2";
            ToolResultBlock result2 = createToolResultBlock(internalToolId2, "Result 2");
            context.setSessionId(subAgentToolId, "session-2");
            context.submitSubAgentResult(subAgentToolId, result2);

            ToolUseBlock toolUse2 =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            PreActingEvent event2 = new PreActingEvent(mockAgent, mockToolkit, toolUse2);
            PreActingEvent result2Event = hook.onEvent(event2).block(TEST_TIMEOUT);

            assertNotNull(result2Event);
            assertEquals("session-2", result2Event.getToolUse().getInput().get("session_id"));
        }

        @Test
        @DisplayName("Should preserve metadata through hook execution")
        void testMetadataPreservation() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-meta";
            String sessionId = "session-meta";

            Map<String, Object> originalMetadata = new HashMap<>();
            originalMetadata.put("custom_key", "custom_value");

            // Internal tool ID used by the sub-agent
            String internalToolId = "internal-tool-meta";
            ToolResultBlock pendingResult = createToolResultBlock(internalToolId, "Pending result");
            context.setSessionId(subAgentToolId, sessionId);
            context.submitSubAgentResult(subAgentToolId, pendingResult);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .metadata(originalMetadata)
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);

            assertNotNull(result);
            ToolUseBlock modifiedToolUse = result.getToolUse();
            // Original metadata should be preserved
            assertEquals("custom_value", modifiedToolUse.getMetadata().get("custom_key"));
            // New metadata key should be added
            assertTrue(
                    modifiedToolUse.getMetadata().containsKey(SubAgentHook.PREVIOUS_TOOL_RESULT));
        }

        @Test
        @DisplayName("Should handle interleaved operations correctly")
        void testInterleavedOperations() {
            // Sub-agent tool IDs
            String subAgentToolId1 = "tool-1";
            String subAgentToolId2 = "tool-2";

            // Add result for tool 1 - internal tool ID used by sub-agent 1
            String internalToolId1 = "internal-tool-1";
            ToolResultBlock result1 = createToolResultBlock(internalToolId1, "Result 1");
            context.setSessionId(subAgentToolId1, "session-1");
            context.submitSubAgentResult(subAgentToolId1, result1);

            // Execute hook for tool 1
            ToolUseBlock toolUse1 =
                    ToolUseBlock.builder()
                            .id(subAgentToolId1)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event1 = new PreActingEvent(mockAgent, mockToolkit, toolUse1);
            hook.onEvent(event1).block(TEST_TIMEOUT);

            // Add result for tool 2 - internal tool ID used by sub-agent 2
            String internalToolId2 = "internal-tool-2";
            ToolResultBlock result2 = createToolResultBlock(internalToolId2, "Result 2");
            context.setSessionId(subAgentToolId2, "session-2");
            context.submitSubAgentResult(subAgentToolId2, result2);

            // Execute hook for tool 2
            ToolUseBlock toolUse2 =
                    ToolUseBlock.builder()
                            .id(subAgentToolId2)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            PreActingEvent event2 = new PreActingEvent(mockAgent, mockToolkit, toolUse2);
            PreActingEvent result2Event = hook.onEvent(event2).block(TEST_TIMEOUT);

            assertNotNull(result2Event);
            assertEquals("session-2", result2Event.getToolUse().getInput().get("session_id"));

            // Verify final state
            assertFalse(context.hasPendingResult(subAgentToolId1));
            assertFalse(context.hasPendingResult(subAgentToolId2));
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("Should handle empty pending result list")
        void testEmptyPendingResultList() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-empty-list";

            // Store session ID but no pending results
            context.setSessionId(subAgentToolId, "session-empty");

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            PreActingEvent result = hook.onEvent(event).block(TEST_TIMEOUT);

            assertNotNull(result);
            assertTrue(
                    result.getToolUse()
                            .getMetadata()
                            .containsKey(SubAgentHook.PREVIOUS_TOOL_RESULT));
        }
    }

    @Nested
    @DisplayName("Integration with SubAgentContext Tests")
    class SubAgentContextIntegrationTests {

        @Test
        @DisplayName("Should work with SubAgentContext state management")
        void testContextStateManagement() {
            // Sub-agent tool ID
            String subAgentToolId = "tool-state";
            String sessionId = "session-state";

            // Internal tool ID used by the sub-agent
            String internalToolId = "internal-tool-state";
            ToolResultBlock result = createToolResultBlock(internalToolId, "State result");
            context.setSessionId(subAgentToolId, sessionId);
            context.submitSubAgentResult(subAgentToolId, result);

            ToolUseBlock toolUse =
                    ToolUseBlock.builder()
                            .id(subAgentToolId)
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            Toolkit mockToolkit = mock(Toolkit.class);

            PreActingEvent event = new PreActingEvent(mockAgent, mockToolkit, toolUse);

            hook.onEvent(event).block(TEST_TIMEOUT);

            // Verify context state after hook execution
            assertFalse(context.hasPendingResult(subAgentToolId));
            assertFalse(context.getSessionId(subAgentToolId).isPresent());
        }
    }

    private ToolResultBlock createToolResultBlock(String id, String content) {
        return new ToolResultBlock(
                id,
                "test-tool",
                List.of(TextBlock.builder().text(content).build()),
                new HashMap<>());
    }
}
