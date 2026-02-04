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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for SubAgentPendingStore functionality.
 *
 * <p>Test coverage includes:
 * <ul>
 *   <li>Basic functionality: storing and retrieving pending results, session IDs</li>
 *   <li>SessionId-first constraint: enforcement of lifecycle management</li>
 *   <li>Boundary conditions: null values, empty strings, special characters</li>
 *   <li>Store consistency: correctness of multiple operations</li>
 *   <li>Defensive copying: prevention of external modifications</li>
 *   <li>Context management: new context creation on sessionId updates and result additions</li>
 * </ul>
 */
@DisplayName("SubAgentPendingStore Tests")
class SubAgentPendingStoreTest {

    private SubAgentPendingStore store;

    @BeforeEach
    void setUp() {
        store = new SubAgentPendingStore();
    }

    @Nested
    @DisplayName("Session ID Management Tests")
    class SessionIdManagementTests {

        @Test
        @DisplayName("Should store and retrieve session ID")
        void testStoreAndRetrieveSessionId() {
            String toolId = "tool-456";
            String sessionId = "session-abc";

            store.setSessionId(toolId, sessionId);

            String retrieved = store.getSessionId(toolId);
            assertEquals(sessionId, retrieved);
        }

        @Test
        @DisplayName("Should update session ID for existing tool ID")
        void testUpdateSessionId() {
            String toolId = "tool-789";
            String sessionId1 = "session-xyz";
            String sessionId2 = "session-updated";

            store.setSessionId(toolId, sessionId1);
            assertEquals(sessionId1, store.getSessionId(toolId));

            store.setSessionId(toolId, sessionId2);
            assertEquals(sessionId2, store.getSessionId(toolId));
        }

        @Test
        @DisplayName("Should return null for non-existent tool ID")
        void testNonExistentSessionId() {
            String sessionId = store.getSessionId("non-existent");
            assertNull(sessionId);
        }

        @Test
        @DisplayName("Should handle multiple session IDs")
        void testMultipleSessionIds() {
            store.setSessionId("tool-1", "session-1");
            store.setSessionId("tool-2", "session-2");
            store.setSessionId("tool-3", "session-3");

            assertEquals("session-1", store.getSessionId("tool-1"));
            assertEquals("session-2", store.getSessionId("tool-2"));
            assertEquals("session-3", store.getSessionId("tool-3"));
        }

        @Test
        @DisplayName("Should check if tool has registered session ID")
        void testContains() {
            assertFalse(store.contains("tool-1"));

            store.setSessionId("tool-1", "session-1");

            assertTrue(store.contains("tool-1"));
            assertFalse(store.contains("tool-2"));
        }
    }

    @Nested
    @DisplayName("Pending Result Management Tests")
    class PendingResultManagementTests {

        @Test
        @DisplayName("Should add and retrieve pending result")
        void testAddAndRetrievePendingResult() {
            String toolId = "tool-123";
            String sessionId = "session-abc";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            // Must set session ID first
            store.setSessionId(toolId, sessionId);
            store.addResult(toolId, result);

            List<ToolResultBlock> retrieved = store.getPendingResults(toolId);
            assertEquals(1, retrieved.size());
            assertEquals(toolId, retrieved.get(0).getId());
        }

        @Test
        @DisplayName("Should handle multiple results for same tool ID")
        void testMultipleResultsForSameToolId() {
            String toolId = "tool-1";
            String sessionId = "session-1";
            ToolResultBlock result1 = createToolResultBlock(toolId, "First result");
            ToolResultBlock result2 = createToolResultBlock(toolId, "Second result");

            store.setSessionId(toolId, sessionId);
            store.addResult(toolId, result1);
            store.addResult(toolId, result2);

            List<ToolResultBlock> retrieved = store.getPendingResults(toolId);
            assertEquals(2, retrieved.size());
            assertEquals(
                    "First result", ((TextBlock) retrieved.get(0).getOutput().get(0)).getText());
            assertEquals(
                    "Second result", ((TextBlock) retrieved.get(1).getOutput().get(0)).getText());
        }

        @Test
        @DisplayName("Should return empty list for non-existent tool ID")
        void testNonExistentToolId() {
            List<ToolResultBlock> results = store.getPendingResults("non-existent");
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("Should check if tool has pending results")
        void testHasPendingResults() {
            String toolId = "tool-1";
            String sessionId = "session-1";

            store.setSessionId(toolId, sessionId);

            assertFalse(store.hasPendingResults(toolId));

            store.addResult(toolId, createToolResultBlock(toolId, "Result"));

            assertTrue(store.hasPendingResults(toolId));
        }

        @Test
        @DisplayName("Should return false for non-existent tool ID in hasPendingResults")
        void testHasPendingResultsNonExistent() {
            assertFalse(store.hasPendingResults("non-existent"));
        }

        @Test
        @DisplayName("Should preserve result order")
        void testPreserveResultOrder() {
            String toolId = "tool-1";
            String sessionId = "session-1";

            store.setSessionId(toolId, sessionId);

            for (int i = 0; i < 5; i++) {
                store.addResult(toolId, createToolResultBlock(toolId, "Result " + i));
            }

            List<ToolResultBlock> results = store.getPendingResults(toolId);
            assertEquals(5, results.size());

            for (int i = 0; i < 5; i++) {
                assertEquals(
                        "Result " + i, ((TextBlock) results.get(i).getOutput().get(0)).getText());
            }
        }

        @Test
        @DisplayName("Should return defensive copy of results")
        void testDefensiveCopy() {
            String toolId = "tool-1";
            String sessionId = "session-1";

            store.setSessionId(toolId, sessionId);
            store.addResult(toolId, createToolResultBlock(toolId, "Original"));

            List<ToolResultBlock> results1 = store.getPendingResults(toolId);
            List<ToolResultBlock> results2 = store.getPendingResults(toolId);

            // Should be different list instances
            assertNotSame(results1, results2);

            // Should have same content
            assertEquals(results1, results2);

            // Modifying one should not affect the other or the Store
            results1.clear();

            List<ToolResultBlock> results3 = store.getPendingResults(toolId);
            assertEquals(1, results3.size());
            assertEquals("Original", ((TextBlock) results3.get(0).getOutput().get(0)).getText());
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
            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class, () -> store.addResult(toolId, result));

            assertTrue(exception.getMessage().contains("Cannot add result"));
            assertTrue(exception.getMessage().contains("without a registered session ID"));
            assertTrue(exception.getMessage().contains("Call setSessionId() first"));
        }

        @Test
        @DisplayName("Should allow adding result after session ID is set")
        void testAddResultAfterSessionId() {
            String toolId = "tool-123";
            ToolResultBlock result = createToolResultBlock(toolId, "Test result");

            // Set session ID first
            store.setSessionId(toolId, "session-abc");

            // Now adding result should succeed
            assertDoesNotThrow(() -> store.addResult(toolId, result));

            List<ToolResultBlock> results = store.getPendingResults(toolId);
            assertEquals(1, results.size());
        }
    }

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should throw IllegalArgumentException for null tool ID in setSessionId")
        void testNullToolIdInSetSessionId() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> store.setSessionId(null, "session-1"));
            assertTrue(exception.getMessage().contains("toolId cannot be null"));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null session ID in setSessionId")
        void testNullSessionIdInSetSessionId() {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> store.setSessionId("tool-1", null));
            assertTrue(exception.getMessage().contains("sessionId cannot be null"));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null tool ID in addResult")
        void testNullToolIdInAddResult() {
            ToolResultBlock result = createToolResultBlock("tool-1", "Result");

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class, () -> store.addResult(null, result));
            assertTrue(exception.getMessage().contains("toolId cannot be null"));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null result in addResult")
        void testNullResultInAddResult() {
            store.setSessionId("tool-1", "session-1");

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class, () -> store.addResult("tool-1", null));
            assertTrue(exception.getMessage().contains("result cannot be null"));
        }
    }

    @Nested
    @DisplayName("Clear and Remove Tests")
    class ClearAndRemoveTests {

        @Test
        @DisplayName("Should remove tool data")
        void testRemove() {
            store.setSessionId("tool-1", "session-1");
            store.addResult("tool-1", createToolResultBlock("tool-1", "Result"));

            assertTrue(store.contains("tool-1"));
            assertTrue(store.hasPendingResults("tool-1"));

            store.remove("tool-1");

            assertFalse(store.contains("tool-1"));
            assertFalse(store.hasPendingResults("tool-1"));
            assertNull(store.getSessionId("tool-1"));
        }

        @Test
        @DisplayName("Should clear all data")
        void testClearAll() {
            store.setSessionId("tool-1", "session-1");
            store.addResult("tool-1", createToolResultBlock("tool-1", "Result 1"));
            store.setSessionId("tool-2", "session-2");
            store.addResult("tool-2", createToolResultBlock("tool-2", "Result 2"));

            assertFalse(store.isEmpty());

            store.clearAll();

            assertTrue(store.isEmpty());
            assertFalse(store.contains("tool-1"));
            assertFalse(store.contains("tool-2"));
        }

        @Test
        @DisplayName("Should check if Store is empty")
        void testIsEmpty() {
            assertTrue(store.isEmpty());

            store.setSessionId("tool-1", "session-1");

            assertFalse(store.isEmpty());

            store.clearAll();

            assertTrue(store.isEmpty());
        }
    }

    @Nested
    @DisplayName("Store Consistency Tests")
    class StoreConsistencyTests {

        @Test
        @DisplayName("Should handle interleaved operations correctly")
        void testInterleavedOperations() {
            // Add result 1
            store.setSessionId("tool-1", "session-1");
            store.addResult("tool-1", createToolResultBlock("tool-1", "Result 1"));
            // Add session ID 1
            store.setSessionId("tool-2", "session-2");
            // Add result 2
            store.addResult("tool-2", createToolResultBlock("tool-2", "Result 2"));
            // Remove result 1
            store.remove("tool-1");
            // Add result 3
            store.setSessionId("tool-3", "session-3");
            store.addResult("tool-3", createToolResultBlock("tool-3", "Result 3"));

            // Verify final Store
            assertFalse(store.contains("tool-1"));
            assertTrue(store.contains("tool-2"));
            assertTrue(store.contains("tool-3"));
            assertEquals("session-2", store.getSessionId("tool-2"));
            assertEquals("session-3", store.getSessionId("tool-3"));
        }

        @Test
        @DisplayName("Should preserve metadata through operations")
        void testMetadataPreservation() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("custom_key", "custom_value");

            ToolResultBlock result =
                    ToolResultBlock.builder()
                            .id("tool-1")
                            .output(TextBlock.builder().text("Result").build())
                            .metadata(metadata)
                            .build();

            store.setSessionId("tool-1", "session-123");
            store.addResult("tool-1", result);

            // Get result
            List<ToolResultBlock> results = store.getPendingResults("tool-1");
            assertEquals(1, results.size());

            ToolResultBlock retrievedResult = results.get(0);
            assertEquals("custom_value", retrievedResult.getMetadata().get("custom_key"));
        }
    }

    @Nested
    @DisplayName("Context Management Tests")
    class ContextManagementTests {

        @Test
        @DisplayName("Should create new context on sessionId update")
        void testNewContextOnSessionIdUpdate() {
            String toolId = "tool-1";
            String sessionId1 = "session-1";
            String sessionId2 = "session-2";

            store.setSessionId(toolId, sessionId1);
            store.addResult(toolId, createToolResultBlock(toolId, "Result 1"));

            List<ToolResultBlock> results1 = store.getPendingResults(toolId);

            store.setSessionId(toolId, sessionId2);

            List<ToolResultBlock> results2 = store.getPendingResults(toolId);

            // Results should be different instances
            assertNotSame(results1, results2);
            assertEquals(1, results1.size());
            assertEquals(0, results2.size());
        }

        @Test
        @DisplayName("Should create new context on result addition")
        void testNewContextOnResultAddition() {
            String toolId = "tool-1";
            String sessionId = "session-1";

            store.setSessionId(toolId, sessionId);
            store.addResult(toolId, createToolResultBlock(toolId, "Result 1"));

            List<ToolResultBlock> results1 = store.getPendingResults(toolId);

            store.addResult(toolId, createToolResultBlock(toolId, "Result 2"));

            List<ToolResultBlock> results2 = store.getPendingResults(toolId);

            // Results should be different instances
            assertNotSame(results1, results2);
            assertEquals(1, results1.size());
            assertEquals(2, results2.size());
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
