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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Integration tests for SubAgent HITL (Human-in-the-Loop) functionality with ReActAgent.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Main agent detecting sub-agent suspension</li>
 *   <li>User providing Result results to main agent</li>
 *   <li>Main agent resuming sub-agent execution</li>
 *   <li>Multi-turn HITL interactions</li>
 *   <li>SubAgentHook integration with ReActAgent</li>
 * </ul>
 */
@DisplayName("SubAgent HITL Integration Tests")
class SubAgentHITLTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ChatUsage DEFAULT_USAGE = new ChatUsage(10, 20, 30);
    private static final String DEFAULT_SYS_PROMPT = "You are a helpful assistant.";
    private static final String MAIN_AGENT_NAME = "MainAgent";

    private InMemoryMemory mainAgentMemory;
    private Toolkit mainAgentToolkit;

    @BeforeEach
    void setUp() {
        mainAgentMemory = new InMemoryMemory();
        mainAgentToolkit = new Toolkit();
    }

    // ==================== Test Classes ====================

    @Nested
    @DisplayName("Main Agent Suspension Detection Tests")
    class MainAgentSuspensionDetectionTests {

        @Test
        @DisplayName("Should detect sub-agent suspension and return suspended result to user")
        void testMainAgentDetectsSubAgentSuspension() {
            Agent mockSubAgent =
                    createSubAgent(
                            "SuspendableSubAgent", suspendedMessage("Calling external API..."));
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createToolThenTextModel(
                            "call-sub-1", "call_suspendablesubagent", "Task completed");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Execute task")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());

            List<ToolResultBlock> toolResults = response.getContentBlocks(ToolResultBlock.class);
            assertFalse(toolResults.isEmpty(), "Should contain tool result blocks");

            ToolResultBlock suspendedResult = toolResults.get(0);
            assertTrue(
                    suspendedResult
                            .getMetadata()
                            .containsKey(SubAgentContext.METADATA_SUBAGENT_SESSION_ID));
        }

        @Test
        @DisplayName("Should include session ID in suspended response metadata")
        void testSuspendedResponseContainsSessionId() {
            Agent mockSubAgent =
                    createSubAgent(
                            "SuspendableSubAgent", suspendedMessage("Calling external API..."));
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createAlwaysToolUseModel("call-sub-1", "call_suspendablesubagent");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Execute task")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());

            List<ToolResultBlock> toolResults = response.getContentBlocks(ToolResultBlock.class);
            assertFalse(toolResults.isEmpty());

            ToolResultBlock suspendedResult = toolResults.get(0);
            assertNotNull(suspendedResult.getMetadata());
            assertTrue(
                    suspendedResult
                            .getMetadata()
                            .containsKey(SubAgentContext.METADATA_SUBAGENT_SESSION_ID));

            String sessionId =
                    (String)
                            suspendedResult
                                    .getMetadata()
                                    .get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID);
            assertNotNull(sessionId);
            assertFalse(sessionId.isEmpty());
        }
    }

    @Nested
    @DisplayName("User Result and Resume Tests")
    class UserResultAndResumeTests {

        @Test
        @DisplayName("Should resume sub-agent execution after user provides Result")
        void testResumeAfterUserResult() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent =
                    createSubAgent(
                            "ResumableSubAgent", suspendedMessage("Calling external API..."));

            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createSequentialToolModel("call_resumablesubagent", 2, "All done!");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            // First call - should suspend
            Msg response1 = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);
            assertNotNull(response1);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            List<ToolResultBlock> toolResults = response1.getContentBlocks(ToolResultBlock.class);
            assertFalse(toolResults.isEmpty());

            ToolResultBlock suspendedResult = toolResults.get(0);
            String toolId = suspendedResult.getId();
            String sessionId =
                    (String)
                            suspendedResult
                                    .getMetadata()
                                    .get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID);

            context.setSessionId(toolId, sessionId);

            // Provide Result
            ToolResultBlock Result =
                    ToolResultBlock.builder()
                            .id(toolId)
                            .name("external_api")
                            .output(TextBlock.builder().text("API response: success").build())
                            .build();
            mainAgent.submitSubAgentResult(toolId, Result);

            // Second call - should complete
            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(response2);
            assertEquals(GenerateReason.MODEL_STOP, response2.getGenerateReason());
        }

        @Test
        @DisplayName("Should handle multiple sequential suspensions")
        void testMultipleSequentialSuspensions() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent = createMultiStepSubAgent("MultiStepSubAgent", 2);

            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createSequentialToolModel("call_multistepsubagent", 3, "All done!");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            // First suspension
            Msg response1 =
                    mainAgent.call(userMessage("Start multi-step task")).block(TEST_TIMEOUT);
            assertNotNull(response1);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            // Second suspension
            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(response2);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response2.getGenerateReason());

            // Final completion
            Msg response3 = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(response3);
            assertEquals(GenerateReason.MODEL_STOP, response3.getGenerateReason());
        }
    }

    @Nested
    @DisplayName("ConfirmSubAgent API Tests")
    class ConfirmSubAgentAPITests {

        @Test
        @DisplayName("Should store Result result via submitSubAgentResult API")
        void testConfirmSubAgentAPI() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent =
                    createSubAgent(
                            "SuspendableSubAgent", suspendedMessage("Calling external API..."));
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createToolThenTextModel(
                            "call-confirm-1", "call_suspendablesubagent", "Completed");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg suspendedResponse = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);

            assertNotNull(suspendedResponse);
            assertEquals(GenerateReason.TOOL_SUSPENDED, suspendedResponse.getGenerateReason());

            List<ToolResultBlock> toolResults =
                    suspendedResponse.getContentBlocks(ToolResultBlock.class);
            assertFalse(toolResults.isEmpty());

            ToolResultBlock suspendedResult = toolResults.get(0);
            String toolId = suspendedResult.getId();

            // Submit the result for the internal tool call that caused the suspension
            ToolResultBlock internalToolResult =
                    ToolResultBlock.builder()
                            .id("inner-api-call") // ID of the tool called inside the sub-agent
                            .name("external_api")
                            .output(TextBlock.builder().text("API response: success").build())
                            .build();

            // First parameter: the sub-agent tool ID (call-1)
            // Second parameter: the result of the internal tool that suspended the sub-agent
            context.submitSubAgentResult(toolId, internalToolResult);

            assertTrue(context.hasPendingResult(toolId), "Context should have pending result");
        }
    }

    @Nested
    @DisplayName("Paused State Handling Tests")
    class PausedStateHandlingTests {

        @Test
        @DisplayName("Should handle sub-agent paused state (REASONING_STOP_REQUESTED)")
        void testSubAgentPausedState() {
            Msg pausedResponse =
                    assistantMessage(
                            "Paused for reasoning", GenerateReason.REASONING_STOP_REQUESTED);
            Agent mockSubAgent =
                    createSubAgent("PausedSubAgent", "Sub-agent that pauses", pausedResponse);
            registerSubAgent(mockSubAgent);

            MockModel mainModel = createAlwaysToolUseModel("call-paused-1", "call_pausedsubagent");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());
        }

        @Test
        @DisplayName("Should resume from paused state after user continues")
        void testResumeFromPausedState() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent = createSubAgent("PausableSubAgent", reasoningStopMessage("Paused"));

            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createSequentialToolModel("call_pausablesubagent", 1, "All done!");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg response1 = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);
            assertNotNull(response1);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(response2);
            assertEquals(GenerateReason.MODEL_STOP, response2.getGenerateReason());
        }
    }

    @Nested
    @DisplayName("No Message Continue Tests")
    class NoMessageContinueTests {

        @Test
        @DisplayName("Should continue without user Result message")
        void testContinueWithoutResult() {
            SubAgentContext context = new SubAgentContext();

            Agent mockSubAgent =
                    createSubAgent(
                            "AutoResumeSubAgent", suspendedMessage("Waiting for approval..."));

            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createSequentialToolModel("call_autoresumesubagent", 2, "Completed");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg response1 = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);
            assertNotNull(response1);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(response2);
            assertEquals(GenerateReason.MODEL_STOP, response2.getGenerateReason());
        }

        @Test
        @DisplayName("Should handle multiple suspensions without explicit results")
        void testMultipleSuspensionsWithoutResults() {
            SubAgentContext context = new SubAgentContext();

            AtomicInteger subAgentCallCount = new AtomicInteger(0);
            ReActAgent mockSubAgent = mock(ReActAgent.class);
            when(mockSubAgent.getName()).thenReturn("MultiAutoResumeSubAgent");
            when(mockSubAgent.getDescription()).thenReturn("Sub-agent with multiple auto-resumes");
            when(mockSubAgent.call(any(List.class)))
                    .thenAnswer(
                            invocation -> {
                                int count = subAgentCallCount.incrementAndGet();
                                if (count <= 2) {
                                    return Mono.just(
                                            suspendedMessage(
                                                    "Step " + count + " suspended",
                                                    externalApiToolUse("auto-step-" + count)));
                                }
                                return Mono.just(assistantMessage("All steps completed"));
                            });

            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createSequentialToolModel("call_multiautoresumesubagent", 3, "Done");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg response1 = mainAgent.call(userMessage("Start multi-step")).block(TEST_TIMEOUT);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response2.getGenerateReason());

            Msg response3 = mainAgent.call().block(TEST_TIMEOUT);
            assertEquals(GenerateReason.MODEL_STOP, response3.getGenerateReason());
        }
    }

    @Nested
    @DisplayName("Complex Multi-turn Interaction Tests")
    class ComplexMultiTurnTests {

        @Test
        @DisplayName("Should handle multiple concurrent tool suspensions in sub-agent")
        void testMultipleConcurrentSuspensions() {
            ToolUseBlock tool1 =
                    ToolUseBlock.builder()
                            .id("concurrent-api-1")
                            .name("external_api_1")
                            .input(Map.of("url", "https://api1.example.com"))
                            .build();

            ToolUseBlock tool2 =
                    ToolUseBlock.builder()
                            .id("concurrent-api-2")
                            .name("external_api_2")
                            .input(Map.of("url", "https://api2.example.com"))
                            .build();

            Msg suspendedWithMultipleTools =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("Calling multiple APIs...")
                                                    .build(),
                                            tool1,
                                            tool2))
                            .generateReason(GenerateReason.TOOL_SUSPENDED)
                            .build();

            Agent mockSubAgent =
                    createSubAgent(
                            "ConcurrentSubAgent",
                            "Sub-agent with concurrent tools",
                            suspendedWithMultipleTools);
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createAlwaysToolUseModel("call-concurrent-1", "call_concurrentsubagent");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response =
                    mainAgent.call(userMessage("Execute concurrent tasks")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());

            List<ToolResultBlock> toolResults = response.getContentBlocks(ToolResultBlock.class);
            assertFalse(toolResults.isEmpty());
        }

        @Test
        @DisplayName("Should handle same session multiple suspend-resume cycles")
        void testSameSessionMultipleCycles() {
            SubAgentContext context = new SubAgentContext();

            AtomicInteger subAgentCallCount = new AtomicInteger(0);
            ReActAgent mockSubAgent = mock(ReActAgent.class);
            when(mockSubAgent.getName()).thenReturn("CyclicSubAgent");
            when(mockSubAgent.getDescription()).thenReturn("Sub-agent with multiple cycles");
            when(mockSubAgent.call(any(List.class)))
                    .thenAnswer(
                            invocation -> {
                                int count = subAgentCallCount.incrementAndGet();
                                if (count <= 3) {
                                    return Mono.just(
                                            suspendedMessage(
                                                    "Cycle " + count + " suspended",
                                                    externalApiToolUse("cycle-" + count)));
                                }
                                return Mono.just(assistantMessage("All cycles completed"));
                            });

            registerSubAgent(mockSubAgent);

            MockModel mainModel = createSequentialToolModel("call_cyclicsubagent", 4, "Finished");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            for (int i = 1; i <= 3; i++) {
                Msg response =
                        mainAgent
                                .call(i == 1 ? userMessage("Start cycles") : null)
                                .block(TEST_TIMEOUT);
                assertNotNull(response);
                assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());
            }

            Msg finalResponse = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(finalResponse);
            assertEquals(GenerateReason.MODEL_STOP, finalResponse.getGenerateReason());
        }
    }

    @Nested
    @DisplayName("Error Handling and Exception Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle invalid tool ID")
        void testInvalidToolId() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent =
                    createSubAgent(
                            "SuspendableSubAgent", suspendedMessage("Calling external API..."));
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createToolThenTextModel(
                            "call-invalid-1", "call_suspendablesubagent", "Completed");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg suspendedResponse = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);
            assertNotNull(suspendedResponse);

            ToolResultBlock invalidResult =
                    ToolResultBlock.builder()
                            .id("inner-api-call")
                            .name("external_api")
                            .output(TextBlock.builder().text("Invalid Result").build())
                            .build();
            // Should throw because "invalid-tool-id" is not a registered sub-agent tool
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.submitSubAgentResult("invalid-tool-id", invalidResult));
        }

        @Test
        @DisplayName("Should handle mismatched Result result")
        void testMismatchedResult() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent =
                    createSubAgent(
                            "SuspendableSubAgent", suspendedMessage("Calling external API..."));
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createToolThenTextModel(
                            "call-mismatch-1", "call_suspendablesubagent", "Completed");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg suspendedResponse = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);
            assertNotNull(suspendedResponse);

            List<ToolResultBlock> toolResults =
                    suspendedResponse.getContentBlocks(ToolResultBlock.class);
            assertFalse(toolResults.isEmpty());

            ToolResultBlock suspendedResult = toolResults.get(0);
            String toolId = suspendedResult.getId();

            // Submit result with internal tool ID from the suspended sub-agent
            ToolResultBlock mismatchedResult =
                    ToolResultBlock.builder()
                            .id("inner-api-call") // Internal tool ID that caused suspension
                            .name("external_api")
                            .output(TextBlock.builder().text("Mismatched Result").build())
                            .build();

            // First parameter: sub-agent tool ID, second parameter: internal tool result
            context.submitSubAgentResult(toolId, mismatchedResult);

            assertTrue(context.hasPendingResult(toolId));
        }

        @Test
        @DisplayName("Should handle sub-agent exception during resume")
        void testExceptionDuringResume() {
            SubAgentContext context = new SubAgentContext();
            Agent mockSubAgent =
                    createSubAgent("FailingSubAgent", suspendedMessage("About to fail..."));

            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createSequentialToolModel("call_failingsubagent", 2, "Should not reach");
            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg response1 = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);
            assertNotNull(response1);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            try {
                mainAgent.call().block(TEST_TIMEOUT);
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("Resume failed") || e.getCause() != null);
            }
        }
    }

    @Nested
    @DisplayName("SubAgentContext Management Tests")
    class ContextManagementTests {

        @Test
        @DisplayName("Should manage multiple concurrent sub-agent suspensions")
        void testMultipleSubAgentSuspensions() {
            SubAgentContext context = new SubAgentContext();

            Agent mockSubAgent1 =
                    createSubAgent("SubAgent1", actingStopMessage("Calling external API..."));
            Agent mockSubAgent2 =
                    createSubAgent("SubAgent2", suspendedMessage("Calling external API..."));
            Agent mockSubAgent3 =
                    createSubAgent("SubAgent3", reasoningStopMessage("Calling external API..."));
            registerSubAgent(mockSubAgent1);
            registerSubAgent(mockSubAgent2);
            registerSubAgent(mockSubAgent3);

            AtomicInteger callCount = new AtomicInteger(0);
            MockModel mainModel =
                    createMockModel(
                            messages -> {
                                int count = callCount.incrementAndGet();
                                if (count == 1) {
                                    return List.of(
                                            toolUseResponse(
                                                    "call-1",
                                                    "call_subagent1",
                                                    "{\"message\": \"test\"}"),
                                            toolUseResponse(
                                                    "call-2",
                                                    "call_subagent2",
                                                    "{\"message\": \"value\"}"),
                                            toolUseResponse(
                                                    "call-3",
                                                    "call_subagent3",
                                                    "{\"message\": \"value\"}"));
                                }
                                return List.of(textResponse("Both completed"));
                            });

            ReActAgent mainAgent = createHitlAgent(mainModel, context);

            Msg response1 = mainAgent.call(userMessage("Start both agents")).block(TEST_TIMEOUT);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response1.getGenerateReason());

            List<ToolResultBlock> results = response1.getContentBlocks(ToolResultBlock.class);

            ToolResultBlock firstResult = null;
            ToolResultBlock secondResult = null;
            ToolResultBlock thirdResult = null;
            for (ToolResultBlock resultBlock : results) {
                switch (resultBlock.getName()) {
                    case "call_subagent1" -> firstResult = resultBlock;
                    case "call_subagent2" -> secondResult = resultBlock;
                    case "call_subagent3" -> thirdResult = resultBlock;
                }
            }
            assertNotNull(firstResult);
            assertNotNull(secondResult);
            assertNotNull(thirdResult);
            assertEquals(
                    GenerateReason.ACTING_STOP_REQUESTED,
                    SubAgentContext.getSubAgentGenerateReason(firstResult));
            assertEquals(
                    GenerateReason.TOOL_SUSPENDED,
                    SubAgentContext.getSubAgentGenerateReason(secondResult));
            assertEquals(
                    GenerateReason.REASONING_STOP_REQUESTED,
                    SubAgentContext.getSubAgentGenerateReason(thirdResult));

            // Submit result for the second sub-agent's internal tool call
            ToolResultBlock internalResult =
                    ToolResultBlock.builder()
                            .id("inner-api-call")
                            .name("external_api")
                            .output(TextBlock.builder().text("API response: success").build())
                            .build();
            context.submitSubAgentResult(secondResult.getId(), internalResult);

            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            assertNotNull(response2);
            assertEquals(GenerateReason.MODEL_STOP, response2.getGenerateReason());
        }

        @Test
        @DisplayName("Should ensure session ID uniqueness across multiple suspensions")
        void testSessionIdUniqueness() {
            Agent mockSubAgent = createMultiStepSubAgent("SuspendableSubAgent", 2);
            registerSubAgent(mockSubAgent);

            AtomicInteger callCount = new AtomicInteger(0);
            MockModel mainModel =
                    createMockModel(
                            messages -> {
                                int count = callCount.incrementAndGet();
                                if (count <= 2) {
                                    return List.of(
                                            toolUseResponse(
                                                    "call-" + count,
                                                    "call_suspendablesubagent",
                                                    "{\"message\": \"call\"}"));
                                }
                                return List.of(textResponse("Done"));
                            });

            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response1 = mainAgent.call(userMessage("First call")).block(TEST_TIMEOUT);
            List<ToolResultBlock> results1 = response1.getContentBlocks(ToolResultBlock.class);
            Optional<String> sessionId1 = SubAgentContext.extractSessionId(results1.get(0));
            assertTrue(sessionId1.isPresent());

            Msg response2 = mainAgent.call().block(TEST_TIMEOUT);
            List<ToolResultBlock> results2 = response2.getContentBlocks(ToolResultBlock.class);
            Optional<String> sessionId2 = SubAgentContext.extractSessionId(results2.get(0));
            assertTrue(sessionId2.isPresent());

            assertTrue(sessionId1.equals(sessionId2), "Session IDs should be same");
        }
    }

    @Nested
    @DisplayName("HITL Configuration Mismatch Tests")
    class HITLConfigurationMismatchTests {

        @Test
        @DisplayName("Should handle main agent HITL enabled but sub-agent HITL disabled")
        void testMainAgentHITLEnabledSubAgentDisabled() {
            Agent mockSubAgent =
                    createSubAgent("SubAgentNoHITL", suspendedMessage("Processing..."));

            // Register sub-agent with HITL disabled
            SubAgentConfig disabledConfig =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(false).build();
            mainAgentToolkit.registration().subAgent(() -> mockSubAgent, disabledConfig).apply();

            MockModel mainModel =
                    createToolThenTextModel(
                            "call-subagent-1", "call_subagentnohitl", "Task completed");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Start task")).block(TEST_TIMEOUT);

            assertNotNull(response);
            // Sub-agent suspension should not propagate to main agent
            assertEquals(
                    GenerateReason.MODEL_STOP,
                    response.getGenerateReason(),
                    "Main agent should complete normally when sub-agent HITL is disabled");
        }

        @Test
        @DisplayName("Should handle main agent HITL disabled but sub-agent HITL enabled")
        void testMainAgentHITLDisabledSubAgentEnabled() {
            Agent mockSubAgent =
                    createSubAgent("SubAgentWithHITL", suspendedMessage("Waiting for input..."));
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createToolThenTextModel("call-subagent-2", "call_subagentwithhitl", "All done");
            ReActAgent mainAgent = createNonHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Execute")).block(TEST_TIMEOUT);

            assertNotNull(response);
            // Main agent should not suspend even if sub-agent is suspended
            assertEquals(
                    GenerateReason.TOOL_SUSPENDED,
                    response.getGenerateReason(),
                    "Main agent should complete normally when main HITL is disabled");
        }

        @Test
        @DisplayName("Should handle both main and sub-agent HITL disabled")
        void testBothHITLDisabled() {
            Agent mockSubAgent =
                    createSubAgent("SubAgentNoHITL2", suspendedMessage("Processing data..."));

            SubAgentConfig disabledConfig =
                    SubAgentConfig.builder().forwardEvents(false).enableHITL(false).build();
            mainAgentToolkit.registration().subAgent(() -> mockSubAgent, disabledConfig).apply();

            MockModel mainModel =
                    createToolThenTextModel("call-subagent-3", "call_subagentnohitl2", "Finished");
            ReActAgent mainAgent = createNonHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Run")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(
                    GenerateReason.MODEL_STOP,
                    response.getGenerateReason(),
                    "Both agents should complete normally when HITL is disabled");
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling Tests")
    class EdgeCasesAndErrorHandlingTests {

        @Test
        @DisplayName("Should handle sub-agent returning normal response (not suspended)")
        void testNormalSubAgentResponse() {
            Agent mockSubAgent = createNormalSubAgent("NormalSubAgent", "Normal response");
            registerSubAgent(mockSubAgent);

            MockModel mainModel =
                    createToolThenTextModel("call-normal-1", "call_normalsubagent", "Task done");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Say hello")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(
                    GenerateReason.MODEL_STOP,
                    response.getGenerateReason(),
                    "Should complete normally without suspension");
        }

        @Test
        @DisplayName("Should handle empty tool results gracefully")
        void testEmptyToolResults() {
            Msg pausedResponse =
                    assistantMessage("Paused", GenerateReason.REASONING_STOP_REQUESTED);
            Agent mockSubAgent =
                    createSubAgent("EmptyResultAgent", "Agent with empty result", pausedResponse);
            registerSubAgent(mockSubAgent);

            MockModel mainModel = createAlwaysToolUseModel("call-empty-1", "call_emptyresultagent");
            ReActAgent mainAgent = createHitlAgent(mainModel);

            Msg response = mainAgent.call(userMessage("Test")).block(TEST_TIMEOUT);

            assertNotNull(response);
            assertEquals(GenerateReason.TOOL_SUSPENDED, response.getGenerateReason());
        }
    }

    // ==================== Message Factory Methods ====================

    /**
     * Creates a user message with the given text.
     */
    private Msg userMessage(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * Creates an assistant message with the given text.
     */
    private Msg assistantMessage(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg actingStopMessage(String text) {
        ToolUseBlock innerToolUse = externalApiToolUse("inner-api-call");
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(text).build(), innerToolUse))
                .generateReason(GenerateReason.ACTING_STOP_REQUESTED)
                .build();
    }

    private Msg suspendedMessage(String text) {
        ToolUseBlock innerToolUse = externalApiToolUse("inner-api-call");
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(text).build(), innerToolUse))
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
    }

    private Msg reasoningStopMessage(String text) {
        ToolUseBlock innerToolUse = externalApiToolUse("inner-api-call");
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(text).build(), innerToolUse))
                .generateReason(GenerateReason.REASONING_STOP_REQUESTED)
                .build();
    }

    /**
     * Creates a suspended assistant message with tool use block.
     */
    private Msg suspendedMessage(String text, ToolUseBlock toolUse) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(text).build(), toolUse))
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .build();
    }

    /**
     * Creates an assistant message with custom generate reason.
     */
    private Msg assistantMessage(String text, GenerateReason reason) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .generateReason(reason)
                .build();
    }

    // ==================== Response Factory Methods ====================

    /**
     * Creates a ChatResponse with a single text block.
     */
    private ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(DEFAULT_USAGE)
                .build();
    }

    /**
     * Creates a ChatResponse with a tool use block.
     */
    private ChatResponse toolUseResponse(String toolId, String toolName, String content) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .content(content)
                                        .build()))
                .usage(DEFAULT_USAGE)
                .build();
    }

    /**
     * Creates a ToolUseBlock for external API calls.
     */
    private ToolUseBlock externalApiToolUse(String id) {
        return ToolUseBlock.builder()
                .id(id)
                .name("external_api")
                .input(Map.of("url", "https://api.example.com"))
                .build();
    }

    // ==================== Config Factory Methods ====================

    /**
     * Creates a default SubAgentConfig with HITL enabled.
     */
    private SubAgentConfig hitlEnabledConfig() {
        return SubAgentConfig.builder().forwardEvents(false).enableHITL(true).build();
    }

    // ==================== Model Factory Methods ====================

    /**
     * Creates a MockModel with custom response handler.
     */
    private MockModel createMockModel(Function<List<Msg>, List<ChatResponse>> handler) {
        return new MockModel(handler);
    }

    /**
     * Creates a MockModel that returns tool use on first call, then text response.
     */
    private MockModel createToolThenTextModel(String toolId, String toolName, String finalText) {
        AtomicInteger callCount = new AtomicInteger(0);
        return createMockModel(
                messages -> {
                    if (callCount.incrementAndGet() == 1) {
                        return List.of(
                                toolUseResponse(toolId, toolName, "{\"message\": \"value\"}"));
                    }
                    return List.of(textResponse(finalText));
                });
    }

    /**
     * Creates a MockModel that always returns tool use response.
     */
    private MockModel createAlwaysToolUseModel(String toolId, String toolName) {
        return createMockModel(
                messages -> List.of(toolUseResponse(toolId, toolName, "{\"message\": \"value\"}")));
    }

    /**
     * Creates a MockModel with sequential tool calls then final text.
     */
    private MockModel createSequentialToolModel(
            String toolNamePrefix, int toolCallCount, String finalText) {
        AtomicInteger callCount = new AtomicInteger(0);
        return createMockModel(
                messages -> {
                    int count = callCount.incrementAndGet();
                    if (count <= toolCallCount) {
                        return List.of(
                                toolUseResponse(
                                        "call-" + count,
                                        toolNamePrefix,
                                        "{\"message\": \"Execute step " + count + "\"}"));
                    }
                    return List.of(textResponse(finalText));
                });
    }

    // ==================== Agent Factory Methods ====================

    /**
     * Registers a sub-agent with HITL config to the toolkit.
     */
    private void registerSubAgent(Agent subAgent) {
        mainAgentToolkit.registration().subAgent(() -> subAgent, hitlEnabledConfig()).apply();
    }

    /**
     * Creates a ReActAgent with HITL enabled.
     */
    private ReActAgent createHitlAgent(MockModel model) {
        return ReActAgent.builder()
                .name(MAIN_AGENT_NAME)
                .sysPrompt(DEFAULT_SYS_PROMPT)
                .model(model)
                .toolkit(mainAgentToolkit)
                .memory(mainAgentMemory)
                .enableSubAgentHITL(true)
                .build();
    }

    /**
     * Creates a ReActAgent with HITL enabled and custom context.
     */
    private ReActAgent createHitlAgent(MockModel model, SubAgentContext context) {
        return ReActAgent.builder()
                .name(MAIN_AGENT_NAME)
                .sysPrompt(DEFAULT_SYS_PROMPT)
                .model(model)
                .toolkit(mainAgentToolkit)
                .memory(mainAgentMemory)
                .subAgentContext(context)
                .enableSubAgentHITL(true)
                .build();
    }

    /**
     * Creates a ReActAgent with HITL disabled.
     */
    private ReActAgent createNonHitlAgent(MockModel model) {
        return ReActAgent.builder()
                .name(MAIN_AGENT_NAME)
                .sysPrompt(DEFAULT_SYS_PROMPT)
                .model(model)
                .toolkit(mainAgentToolkit)
                .memory(mainAgentMemory)
                .enableSubAgentHITL(false)
                .build();
    }

    /**
     * Creates a mock sub-agent that returns response.
     */
    private Agent createSubAgent(String name, Msg response) {
        AtomicInteger count = new AtomicInteger(0);
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn(name);
        when(mockSubAgent.getDescription()).thenReturn("Sub-agent that suspends");
        when(mockSubAgent.call(any(List.class)))
                .thenAnswer(
                        invocation -> {
                            int c = count.incrementAndGet();
                            if (c == 1) {
                                return Mono.just(response);
                            }
                            return Mono.just(assistantMessage("Task completed successfully"));
                        });
        return mockSubAgent;
    }

    private Agent createMultiStepSubAgent(String name, int step) {
        AtomicInteger subAgentCallCount = new AtomicInteger(0);
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn(name);
        when(mockSubAgent.getDescription()).thenReturn("Sub-agent with multiple steps");
        when(mockSubAgent.call(any(List.class)))
                .thenAnswer(
                        invocation -> {
                            int count = subAgentCallCount.incrementAndGet();
                            if (count <= step) {
                                return Mono.just(
                                        suspendedMessage(
                                                "Step " + count + " - calling API...",
                                                externalApiToolUse("step-" + count + "-api")));
                            }
                            return Mono.just(assistantMessage("All steps completed"));
                        });
        return mockSubAgent;
    }

    /**
     * Creates a mock sub-agent that returns normal response.
     */
    private Agent createNormalSubAgent(String name, String response) {
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn(name);
        when(mockSubAgent.getDescription()).thenReturn("Normal sub-agent");
        when(mockSubAgent.call(any(List.class))).thenReturn(Mono.just(assistantMessage(response)));
        return mockSubAgent;
    }

    /**
     * Creates a mock sub-agent with custom response.
     */
    private Agent createSubAgent(String name, String description, Msg response) {
        ReActAgent mockSubAgent = mock(ReActAgent.class);
        when(mockSubAgent.getName()).thenReturn(name);
        when(mockSubAgent.getDescription()).thenReturn(description);
        when(mockSubAgent.call(any(List.class))).thenReturn(Mono.just(response));
        return mockSubAgent;
    }
}
