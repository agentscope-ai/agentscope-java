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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for SubAgentContext functionality.
 *
 * <p>Test coverage includes:
 * <ul>
 *   <li>Basic functionality: storing and retrieving pending results, session IDs</li>
 *   <li>State consistency: correctness of multiple operations</li>
 *   <li>SessionId-first constraint: enforcement of lifecycle management</li>
 *   <li>Metadata extraction: session ID and GenerateReason from ToolResultBlock</li>
 *   <li>Sub-agent result detection: identifying sub-agent results via metadata</li>
 *   <li>State persistence: save and load context state to/from Session</li>
 *   <li>Null handling: robust behavior with null inputs</li>
 *   <li>Clear operations: state cleanup and reset functionality</li>
 * </ul>
 */
@DisplayName("SubAgentContext Tests")
class SubAgentContextTest {

    private SubAgentContext context;

    @BeforeEach
    void setUp() {
        context = new SubAgentContext();
    }

    @Nested
    @DisplayName("Pending Result Management Tests")
    class PendingResultManagementTests {

        @Test
        @DisplayName("Should store and retrieve pending result")
        void testStoreAndRetrievePendingResult() {
            String toolId = "tool-123";
            String sessionId = "session-abc";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            context.setSessionId(toolId, sessionId);
            // First param: sub-agent tool ID, Second param: pending result when sub-agent is
            // suspended
            context.submitSubAgentResult(toolId, result);

            assertTrue(context.hasPendingResult(toolId));
            Optional<List<ToolResultBlock>> retrieved = context.getPendingResult(toolId);
            assertTrue(retrieved.isPresent());
            assertEquals(1, retrieved.get().size());
            assertEquals(toolId, retrieved.get().get(0).getId());
        }

        @Test
        @DisplayName("Should consume and remove pending result")
        void testConsumePendingResult() {
            String toolId = "tool-123";
            String sessionId = "session-abc";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            context.setSessionId(toolId, sessionId);
            context.submitSubAgentResult(toolId, result);

            assertTrue(context.hasPendingResult(toolId));
            Optional<SubAgentPendingContext> consumed = context.consumePendingResult(toolId);
            assertTrue(consumed.isPresent());
            assertEquals(toolId, consumed.get().toolId());
            assertEquals(sessionId, consumed.get().sessionId());
            assertEquals(1, consumed.get().pendingResults().size());

            // Verify it has been consumed
            assertFalse(context.hasPendingResult(toolId));
            assertTrue(context.consumePendingResult(toolId).isEmpty());
        }

        @Test
        @DisplayName("Should handle multiple results for same tool ID")
        void testMultipleResultsForSameToolId() {
            String toolId = "tool-123";
            String sessionId = "session-abc";
            ToolResultBlock result1 = createToolResultBlock("inner-call-1", "Result 1");
            ToolResultBlock result2 = createToolResultBlock("inner-call-2", "Result 2");

            context.setSessionId(toolId, sessionId);
            context.submitSubAgentResult(toolId, result1);
            context.submitSubAgentResult(toolId, result2);

            Optional<List<ToolResultBlock>> retrieved = context.getPendingResult(toolId);
            assertTrue(retrieved.isPresent());
            assertEquals(2, retrieved.get().size());
            assertEquals(
                    "Result 1", ((TextBlock) retrieved.get().get(0).getOutput().get(0)).getText());
            assertEquals(
                    "Result 2", ((TextBlock) retrieved.get().get(1).getOutput().get(0)).getText());
        }

        @Test
        @DisplayName("Should consume all results for same tool ID")
        void testConsumeAllResultsForSameToolId() {
            String toolId = "tool-123";
            String sessionId = "session-abc";
            ToolResultBlock result1 = createToolResultBlock(toolId, "Result 1");
            ToolResultBlock result2 = createToolResultBlock(toolId, "Result 2");

            context.setSessionId(toolId, sessionId);
            context.submitSubAgentResult(toolId, result1);
            context.submitSubAgentResult(toolId, result2);

            Optional<SubAgentPendingContext> consumed = context.consumePendingResult(toolId);
            assertTrue(consumed.isPresent());
            assertEquals(2, consumed.get().pendingResults().size());

            // Verify all results have been consumed
            assertFalse(context.hasPendingResult(toolId));
        }

        @Test
        @DisplayName("Should return empty for non-existent tool ID")
        void testNonExistentToolId() {
            Optional<List<ToolResultBlock>> result = context.getPendingResult("non-existent");
            assertFalse(result.isPresent());

            Optional<SubAgentPendingContext> consumed =
                    context.consumePendingResult("non-existent");
            assertFalse(consumed.isPresent());
        }

        @Test
        @DisplayName("Should throw exception when adding result without session ID")
        void testAddResultWithoutSessionId() {
            String toolId = "tool-123";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            // Should throw exception because session ID is not set
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.submitSubAgentResult(toolId, result));
        }
    }

    @Nested
    @DisplayName("Session ID Management Tests")
    class SessionIdManagementTests {

        @Test
        @DisplayName("Should store and retrieve session ID")
        void testStoreAndRetrieveSessionId() {
            String toolId = "tool-456";
            String sessionId = "session-abc";

            context.setSessionId(toolId, sessionId);

            Optional<String> retrieved = context.getSessionId(toolId);
            assertTrue(retrieved.isPresent());
            assertEquals(sessionId, retrieved.get());
        }

        @Test
        @DisplayName("Should update session ID for existing tool ID")
        void testUpdateSessionId() {
            String toolId = "tool-789";
            String sessionId1 = "session-xyz";
            String sessionId2 = "session-updated";

            context.setSessionId(toolId, sessionId1);
            assertEquals(sessionId1, context.getSessionId(toolId).orElse(null));

            context.setSessionId(toolId, sessionId2);
            assertEquals(sessionId2, context.getSessionId(toolId).orElse(null));
        }

        @Test
        @DisplayName("Should return empty for non-existent tool ID")
        void testNonExistentSessionId() {
            Optional<String> sessionId = context.getSessionId("non-existent");
            assertFalse(sessionId.isPresent());
        }

        @Test
        @DisplayName("Should handle multiple session IDs")
        void testMultipleSessionIds() {
            context.setSessionId("tool-1", "session-1");
            context.setSessionId("tool-2", "session-2");
            context.setSessionId("tool-3", "session-3");

            assertEquals("session-1", context.getSessionId("tool-1").orElse(null));
            assertEquals("session-2", context.getSessionId("tool-2").orElse(null));
            assertEquals("session-3", context.getSessionId("tool-3").orElse(null));
        }
    }

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null tool result")
        void testNullToolResult() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.submitSubAgentResult("tool-123", null));
        }

        @Test
        @DisplayName("Should handle null tool ID in setSessionId")
        void testNullToolIdSetSessionId() {
            assertThrows(
                    IllegalArgumentException.class, () -> context.setSessionId(null, "session-1"));
        }

        @Test
        @DisplayName("Should handle tool result with null ID")
        void testToolResultWithNullId() {
            ToolResultBlock result = createToolResultBlock(null, "Result");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.submitSubAgentResult("tool-123", result));
        }
    }

    @Nested
    @DisplayName("Clear and State Management Tests")
    class ClearAndStateManagementTests {

        @Test
        @DisplayName("Should clear all pending data")
        void testClear() {
            context.setSessionId("tool-1", "session-1");
            context.submitSubAgentResult("tool-1", createToolResultBlock("tool-1", "Result 1"));
            context.setSessionId("tool-2", "session-2");

            context.clear();

            assertFalse(context.hasPendingResult("tool-1"));
            assertFalse(context.getSessionId("tool-2").isPresent());
        }

        @Test
        @DisplayName("Should maintain state after multiple operations")
        void testStateAfterMultipleOperations() {
            // Add some data
            context.setSessionId("tool-1", "session-1");
            context.submitSubAgentResult("tool-1", createToolResultBlock("tool-1", "Result 1"));

            // Verify
            assertTrue(context.hasPendingResult("tool-1"));
            assertEquals("session-1", context.getSessionId("tool-1").orElse(null));

            // Add more data
            context.setSessionId("tool-2", "session-2");
            context.submitSubAgentResult("tool-2", createToolResultBlock("tool-2", "Result 2"));

            // Verify all data
            assertTrue(context.hasPendingResult("tool-1"));
            assertTrue(context.hasPendingResult("tool-2"));
            assertEquals("session-1", context.getSessionId("tool-1").orElse(null));
            assertEquals("session-2", context.getSessionId("tool-2").orElse(null));

            context.consumePendingResult("tool-1");

            // Verify state
            assertFalse(context.hasPendingResult("tool-1"));
            assertTrue(context.hasPendingResult("tool-2"));
            assertNull(context.getSessionId("tool-1").orElse(null));
            assertEquals("session-2", context.getSessionId("tool-2").orElse(null));
        }
    }

    @Nested
    @DisplayName("Metadata Extraction Tests")
    class MetadataExtractionTests {

        @Test
        @DisplayName("Should extract session ID from metadata")
        void testExtractSessionId() {
            String sessionId = "session-xyz";
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SubAgentContext.METADATA_SUBAGENT_SESSION_ID, sessionId);
            ToolResultBlock result =
                    new ToolResultBlock(
                            "tool-1",
                            "test-tool",
                            List.of(TextBlock.builder().text("Result").build()),
                            metadata);

            Optional<String> extracted = SubAgentContext.extractSessionId(result);
            assertTrue(extracted.isPresent());
            assertEquals(sessionId, extracted.get());
        }

        @Test
        @DisplayName("Should return empty when session ID not in metadata")
        void testExtractSessionIdEmpty() {
            ToolResultBlock result = createToolResultBlock("tool-1", "Result");

            Optional<String> extracted = SubAgentContext.extractSessionId(result);
            assertFalse(extracted.isPresent());

            assertFalse(SubAgentContext.extractSessionId(null).isPresent());
        }
    }

    @Nested
    @DisplayName("State Consistency Tests")
    class StateConsistencyTests {

        @Test
        @DisplayName("Should maintain consistency after repeated operations")
        void testRepeatedOperations() {
            // Repeatedly add the same data
            for (int i = 0; i < 10; i++) {
                context.setSessionId("tool-1", "session-1");
                context.submitSubAgentResult(
                        "tool-1", createToolResultBlock("tool-1", "Result " + i));
            }

            // Verify there are 10 results
            Optional<List<ToolResultBlock>> results = context.getPendingResult("tool-1");
            assertTrue(results.isPresent());
            assertEquals(10, results.get().size());

            // Consume all results
            Optional<SubAgentPendingContext> consumed = context.consumePendingResult("tool-1");
            assertTrue(consumed.isPresent());
            assertEquals(10, consumed.get().pendingResults().size());

            // Verify it has been cleared
            assertFalse(context.hasPendingResult("tool-1"));
        }

        @Test
        @DisplayName("Should preserve metadata through operations")
        void testMetadataPreservation() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SubAgentContext.METADATA_SUBAGENT_SESSION_ID, "session-123");
            metadata.put("custom_key", "custom_value");

            ToolResultBlock result =
                    ToolResultBlock.builder()
                            .id("tool-1")
                            .output(TextBlock.builder().text("Result").build())
                            .metadata(metadata)
                            .build();

            context.setSessionId("tool-1", "session-123");
            context.submitSubAgentResult("tool-1", result);

            // Get result
            Optional<List<ToolResultBlock>> retrieved = context.getPendingResult("tool-1");
            assertTrue(retrieved.isPresent());
            assertEquals(1, retrieved.get().size());

            ToolResultBlock retrievedResult = retrieved.get().get(0);
            assertEquals(
                    "session-123",
                    retrievedResult
                            .getMetadata()
                            .get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID));
            assertEquals("custom_value", retrievedResult.getMetadata().get("custom_key"));

            // Consume result
            Optional<SubAgentPendingContext> consumed = context.consumePendingResult("tool-1");
            assertTrue(consumed.isPresent());
            assertEquals(1, consumed.get().pendingResults().size());

            ToolResultBlock consumedResult = consumed.get().pendingResults().get(0);
            assertEquals(
                    "session-123",
                    consumedResult.getMetadata().get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID));
            assertEquals("custom_value", consumedResult.getMetadata().get("custom_key"));
        }
    }

    @Nested
    @DisplayName("SessionId-First Constraint Tests")
    class SessionIdFirstConstraintTests {

        @Test
        @DisplayName("Should enforce sessionId-first constraint")
        void testSessionIdFirstConstraint() {
            String toolId = "tool-123";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            // Try to add result without setting session ID
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            context.submitSubAgentResult(
                                    toolId, createToolResultBlock(toolId, "Test result")));

            // Result should not be added
            assertFalse(context.hasPendingResult(toolId));

            // Now set session ID and add result
            context.setSessionId(toolId, "session-abc");
            context.submitSubAgentResult(toolId, result);

            // Result should be added
            assertTrue(context.hasPendingResult(toolId));
        }

        @Test
        @DisplayName("Should handle session ID replacement")
        void testSessionIdReplacement() {
            String toolId = "tool-123";
            ToolResultBlock result1 = createToolResultBlock(toolId, "Result 1");
            ToolResultBlock result2 = createToolResultBlock(toolId, "Result 2");

            // Set first session ID and add result
            context.setSessionId(toolId, "session-1");
            context.submitSubAgentResult(toolId, result1);

            // Replace session ID
            context.setSessionId(toolId, "session-2");
            context.submitSubAgentResult(toolId, result2);

            // Verify only the second result exists
            Optional<List<ToolResultBlock>> results = context.getPendingResult(toolId);
            assertTrue(results.isPresent());
            assertEquals(1, results.get().size());
            assertEquals(
                    "Result 2", ((TextBlock) results.get().get(0).getOutput().get(0)).getText());
        }
    }

    @Nested
    @DisplayName("Generate Reason Tests")
    class GenerateReasonTests {

        @Test
        @DisplayName("Should extract GenerateReason from metadata")
        void testGetSubAgentGenerateReason() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SubAgentContext.METADATA_GENERATE_REASON, GenerateReason.TOOL_SUSPENDED);
            ToolResultBlock result =
                    new ToolResultBlock(
                            "tool-1",
                            "test-tool",
                            List.of(TextBlock.builder().text("Result").build()),
                            metadata);

            GenerateReason reason = SubAgentContext.getSubAgentGenerateReason(result);
            assertEquals(GenerateReason.TOOL_SUSPENDED, reason);
        }

        @Test
        @DisplayName("Should return MODEL_STOP when GenerateReason not in metadata")
        void testGetSubAgentGenerateReasonDefault() {
            ToolResultBlock result = createToolResultBlock("tool-1", "Result");

            GenerateReason reason = SubAgentContext.getSubAgentGenerateReason(result);
            assertEquals(GenerateReason.MODEL_STOP, reason);
        }

        @Test
        @DisplayName("Should return MODEL_STOP when metadata value is not GenerateReason")
        void testGetSubAgentGenerateReasonInvalidType() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SubAgentContext.METADATA_GENERATE_REASON, "invalid_value");
            ToolResultBlock result =
                    new ToolResultBlock(
                            "tool-1",
                            "test-tool",
                            List.of(TextBlock.builder().text("Result").build()),
                            metadata);

            GenerateReason reason = SubAgentContext.getSubAgentGenerateReason(result);
            assertEquals(GenerateReason.MODEL_STOP, reason);
        }

        @Test
        @DisplayName("Should handle all GenerateReason values")
        void testAllGenerateReasonValues() {
            for (GenerateReason expectedReason : GenerateReason.values()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(SubAgentContext.METADATA_GENERATE_REASON, expectedReason);
                ToolResultBlock result =
                        new ToolResultBlock(
                                "tool-1",
                                "test-tool",
                                List.of(TextBlock.builder().text("Result").build()),
                                metadata);

                GenerateReason actualReason = SubAgentContext.getSubAgentGenerateReason(result);
                assertEquals(expectedReason, actualReason);
            }
        }
    }

    @Nested
    @DisplayName("SubAgent Result Detection Tests")
    class SubAgentResultDetectionTests {

        @Test
        @DisplayName("Should detect sub-agent result with session ID")
        void testIsSubAgentResult() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SubAgentContext.METADATA_SUBAGENT_SESSION_ID, "session-123");
            ToolResultBlock result =
                    new ToolResultBlock(
                            "tool-1",
                            "test-tool",
                            List.of(TextBlock.builder().text("Result").build()),
                            metadata);

            assertTrue(SubAgentContext.isSubAgentResult(result));
        }

        @Test
        @DisplayName("Should return false for non-sub-agent result")
        void testIsNotSubAgentResult() {
            ToolResultBlock result = createToolResultBlock("tool-1", "Result");

            assertFalse(SubAgentContext.isSubAgentResult(result));
        }

        @Test
        @DisplayName("Should return false for null result")
        void testIsSubAgentResultNull() {
            assertFalse(SubAgentContext.isSubAgentResult(null));
        }

        @Test
        @DisplayName("Should return false when metadata is null")
        void testIsSubAgentResultNullMetadata() {
            ToolResultBlock result =
                    new ToolResultBlock(
                            "tool-1",
                            "test-tool",
                            List.of(TextBlock.builder().text("Result").build()),
                            null);

            assertFalse(SubAgentContext.isSubAgentResult(result));
        }
    }

    @Nested
    @DisplayName("State Persistence Tests")
    class StatePersistenceTests {

        private Session session;
        private SessionKey sessionKey;

        @BeforeEach
        void setUp() {
            session = new InMemorySession();
            sessionKey = SimpleSessionKey.of("test-session");
        }

        @Test
        @DisplayName("Should save and load context state")
        void testSaveAndLoad() {
            String toolId = "tool-123";
            String sessionId = "session-abc";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            // Setup context
            context.setSessionId(toolId, sessionId);
            context.submitSubAgentResult(toolId, result);

            // Save to session
            context.saveTo(session, sessionKey);

            // Create new context and load
            SubAgentContext newContext = new SubAgentContext();
            newContext.loadFrom(session, sessionKey);

            // Verify state was restored
            assertTrue(newContext.hasPendingResult(toolId));
            assertEquals(sessionId, newContext.getSessionId(toolId).orElse(null));

            Optional<List<ToolResultBlock>> retrieved = newContext.getPendingResult(toolId);
            assertTrue(retrieved.isPresent());
            assertEquals(1, retrieved.get().size());
            assertEquals(toolId, retrieved.get().get(0).getId());
        }

        @Test
        @DisplayName("Should save and load multiple pending results")
        void testSaveAndLoadMultipleResults() {
            String toolId1 = "tool-1";
            String toolId2 = "tool-2";
            String sessionId1 = "session-1";
            String sessionId2 = "session-2";

            context.setSessionId(toolId1, sessionId1);
            context.submitSubAgentResult(toolId1, createToolResultBlock(toolId1, "Result 1"));
            context.setSessionId(toolId2, sessionId2);
            context.submitSubAgentResult(toolId2, createToolResultBlock(toolId2, "Result 2"));

            // Save to session
            context.saveTo(session, sessionKey);

            // Create new context and load
            SubAgentContext newContext = new SubAgentContext();
            newContext.loadFrom(session, sessionKey);

            // Verify both tools' states were restored
            assertTrue(newContext.hasPendingResult(toolId1));
            assertTrue(newContext.hasPendingResult(toolId2));
            assertEquals(sessionId1, newContext.getSessionId(toolId1).orElse(null));
            assertEquals(sessionId2, newContext.getSessionId(toolId2).orElse(null));
        }

        @Test
        @DisplayName("Should handle empty context save and load")
        void testSaveAndLoadEmptyContext() {
            // Save empty context
            context.saveTo(session, sessionKey);

            // Create new context and load
            SubAgentContext newContext = new SubAgentContext();
            newContext.loadFrom(session, sessionKey);

            // Verify context is empty
            assertFalse(newContext.hasPendingResult("any-tool"));
        }

        @Test
        @DisplayName("Should overwrite previous saved state")
        void testOverwritePreviousState() {
            String toolId = "tool-123";

            // Save first state
            context.setSessionId(toolId, "session-1");
            context.submitSubAgentResult(toolId, createToolResultBlock(toolId, "Result 1"));
            context.saveTo(session, sessionKey);

            // Modify and save again
            context.clear();
            context.setSessionId(toolId, "session-2");
            context.submitSubAgentResult(toolId, createToolResultBlock(toolId, "Result 2"));
            context.saveTo(session, sessionKey);

            // Load and verify latest state
            SubAgentContext newContext = new SubAgentContext();
            newContext.loadFrom(session, sessionKey);

            assertEquals("session-2", newContext.getSessionId(toolId).orElse(null));
            Optional<List<ToolResultBlock>> results = newContext.getPendingResult(toolId);
            assertTrue(results.isPresent());
            assertEquals(1, results.get().size());
            assertEquals(
                    "Result 2", ((TextBlock) results.get().get(0).getOutput().get(0)).getText());
        }

        @Test
        @DisplayName("Should load from non-existent session without error")
        void testLoadFromNonExistentSession() {
            SessionKey nonExistentKey = SimpleSessionKey.of("non-existent");

            // Should not throw exception
            SubAgentContext newContext = new SubAgentContext();
            SubAgentPendingStore oldStore = newContext.getPendingStore();

            newContext.loadFrom(session, nonExistentKey);
            SubAgentPendingStore newStore = newContext.getPendingStore();
            // Context should remain empty
            assertSame(oldStore, newStore);
        }

        @Test
        @DisplayName("Should preserve metadata through save and load")
        void testPreserveMetadataThroughSaveLoad() {
            String toolId = "tool-123";
            String sessionId = "session-abc";

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SubAgentContext.METADATA_SUBAGENT_SESSION_ID, sessionId);
            metadata.put(SubAgentContext.METADATA_GENERATE_REASON, GenerateReason.TOOL_SUSPENDED);
            metadata.put("custom_key", "custom_value");

            ToolResultBlock result =
                    new ToolResultBlock(
                            toolId,
                            "test-tool",
                            List.of(TextBlock.builder().text("Result").build()),
                            metadata);

            context.setSessionId(toolId, sessionId);
            context.submitSubAgentResult(toolId, result);

            // Save and load
            context.saveTo(session, sessionKey);
            SubAgentContext newContext = new SubAgentContext();
            newContext.loadFrom(session, sessionKey);

            // Verify metadata preserved
            Optional<List<ToolResultBlock>> retrieved = newContext.getPendingResult(toolId);
            assertTrue(retrieved.isPresent());
            ToolResultBlock retrievedResult = retrieved.get().get(0);

            assertEquals(
                    sessionId,
                    retrievedResult
                            .getMetadata()
                            .get(SubAgentContext.METADATA_SUBAGENT_SESSION_ID));
            assertEquals(
                    GenerateReason.TOOL_SUSPENDED,
                    retrievedResult.getMetadata().get(SubAgentContext.METADATA_GENERATE_REASON));
            assertEquals("custom_value", retrievedResult.getMetadata().get("custom_key"));
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
