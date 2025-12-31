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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ContextOffloadTool.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Successful reload of offloaded messages</li>
 *   <li>Null contextOffLoader handling</li>
 *   <li>Null or empty UUID handling</li>
 *   <li>Non-existent UUID handling</li>
 *   <li>Exception handling during reload</li>
 * </ul>
 */
@DisplayName("ContextOffloadTool Tests")
class ContextOffloadToolTest {

    private MockContextOffLoader mockContextOffLoader;
    private ContextOffloadTool contextOffloadTool;

    @BeforeEach
    void setUp() {
        mockContextOffLoader = new MockContextOffLoader();
        contextOffloadTool = new ContextOffloadTool(mockContextOffLoader);
    }

    @Test
    @DisplayName("Should successfully reload offloaded messages by UUID")
    void testSuccessfulReload() {
        // Setup: offload some messages
        String uuid = "test-uuid-123";
        List<Msg> messages = new ArrayList<>();
        messages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text("Test message 1").build())
                        .build());
        messages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(TextBlock.builder().text("Test response 1").build())
                        .build());

        mockContextOffLoader.offload(uuid, messages);

        // Execute: reload the messages
        List<Msg> reloaded = contextOffloadTool.reload(uuid);

        // Verify: messages are correctly reloaded
        assertEquals(2, reloaded.size());
        assertEquals("Test message 1", reloaded.get(0).getTextContent());
        assertEquals("Test response 1", reloaded.get(1).getTextContent());
        assertEquals(MsgRole.USER, reloaded.get(0).getRole());
        assertEquals(MsgRole.ASSISTANT, reloaded.get(1).getRole());
    }

    @Test
    @DisplayName("Should return error message when contextOffLoader is null")
    void testNullContextOffLoader() {
        ContextOffloadTool toolWithNullLoader = new ContextOffloadTool(null);

        String uuid = "test-uuid-456";
        List<Msg> result = toolWithNullLoader.reload(uuid);

        // Verify: returns error message
        assertEquals(1, result.size());
        assertTrue(
                result.get(0)
                        .getTextContent()
                        .contains("Error: Context offloader is not available"),
                "Should contain error message about context offloader not being available");
        assertTrue(
                result.get(0).getTextContent().contains(uuid),
                "Error message should contain the UUID");
    }

    @Test
    @DisplayName("Should return error message when UUID is null")
    void testNullUuid() {
        List<Msg> result = contextOffloadTool.reload(null);

        // Verify: returns error message
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("Error: UUID cannot be null or empty"),
                "Should contain error message about UUID being null or empty");
    }

    @Test
    @DisplayName("Should return error message when UUID is empty string")
    void testEmptyUuid() {
        List<Msg> result = contextOffloadTool.reload("");

        // Verify: returns error message
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("Error: UUID cannot be null or empty"),
                "Should contain error message about UUID being null or empty");
    }

    @Test
    @DisplayName("Should return error message when UUID is whitespace only")
    void testWhitespaceUuid() {
        List<Msg> result = contextOffloadTool.reload("   ");

        // Verify: returns error message
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("Error: UUID cannot be null or empty"),
                "Should contain error message about UUID being null or empty");
    }

    @Test
    @DisplayName("Should return error message when UUID does not exist")
    void testNonExistentUuid() {
        String nonExistentUuid = "non-existent-uuid-789";
        List<Msg> result = contextOffloadTool.reload(nonExistentUuid);

        // Verify: returns error message
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("No messages found for UUID"),
                "Should contain error message about UUID not found");
        assertTrue(
                result.get(0).getTextContent().contains(nonExistentUuid),
                "Error message should contain the UUID");
    }

    @Test
    @DisplayName("Should return error message when reload throws exception")
    void testReloadException() {
        // Create a mock that throws exception
        ContextOffLoader exceptionThrowingLoader =
                new ContextOffLoader() {
                    @Override
                    public void offload(String uuid, List<Msg> messages) {
                        // Do nothing
                    }

                    @Override
                    public List<Msg> reload(String uuid) {
                        throw new RuntimeException("Storage error");
                    }

                    @Override
                    public void clear(String uuid) {
                        // Do nothing
                    }
                };

        ContextOffloadTool toolWithException = new ContextOffloadTool(exceptionThrowingLoader);

        String uuid = "test-uuid-exception";
        List<Msg> result = toolWithException.reload(uuid);

        // Verify: returns error message with exception details
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("Error reloading context with UUID"),
                "Should contain error message about reload error");
        assertTrue(
                result.get(0).getTextContent().contains(uuid),
                "Error message should contain the UUID");
        assertTrue(
                result.get(0).getTextContent().contains("Storage error"),
                "Error message should contain the exception message");
    }

    @Test
    @DisplayName("Should handle multiple offloaded contexts")
    void testMultipleOffloadedContexts() {
        // Setup: offload multiple contexts
        String uuid1 = "uuid-1";
        String uuid2 = "uuid-2";
        String uuid3 = "uuid-3";

        List<Msg> messages1 = List.of(createTextMessage("Message 1", MsgRole.USER));
        List<Msg> messages2 = List.of(createTextMessage("Message 2", MsgRole.USER));
        List<Msg> messages3 = List.of(createTextMessage("Message 3", MsgRole.USER));

        mockContextOffLoader.offload(uuid1, messages1);
        mockContextOffLoader.offload(uuid2, messages2);
        mockContextOffLoader.offload(uuid3, messages3);

        // Execute: reload each context
        List<Msg> reloaded1 = contextOffloadTool.reload(uuid1);
        List<Msg> reloaded2 = contextOffloadTool.reload(uuid2);
        List<Msg> reloaded3 = contextOffloadTool.reload(uuid3);

        // Verify: each context is correctly reloaded
        assertEquals(1, reloaded1.size());
        assertEquals("Message 1", reloaded1.get(0).getTextContent());

        assertEquals(1, reloaded2.size());
        assertEquals("Message 2", reloaded2.get(0).getTextContent());

        assertEquals(1, reloaded3.size());
        assertEquals("Message 3", reloaded3.get(0).getTextContent());
    }

    @Test
    @DisplayName("Should handle empty message list from contextOffLoader")
    void testEmptyMessageList() {
        // Setup: offload empty list
        String uuid = "empty-uuid";
        mockContextOffLoader.offload(uuid, new ArrayList<>());

        // Execute: reload
        List<Msg> result = contextOffloadTool.reload(uuid);

        // Verify: returns error message (empty list is treated as not found)
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("No messages found for UUID"),
                "Should contain error message about UUID not found");
    }

    @Test
    @DisplayName("Should handle null message list from contextOffLoader")
    void testNullMessageList() {
        // Create a mock that returns null
        ContextOffLoader nullReturningLoader =
                new ContextOffLoader() {
                    @Override
                    public void offload(String uuid, List<Msg> messages) {
                        // Do nothing
                    }

                    @Override
                    public List<Msg> reload(String uuid) {
                        return null;
                    }

                    @Override
                    public void clear(String uuid) {
                        // Do nothing
                    }
                };

        ContextOffloadTool toolWithNullReturn = new ContextOffloadTool(nullReturningLoader);

        String uuid = "null-uuid";
        List<Msg> result = toolWithNullReturn.reload(uuid);

        // Verify: returns error message
        assertEquals(1, result.size());
        assertTrue(
                result.get(0).getTextContent().contains("No messages found for UUID"),
                "Should contain error message about UUID not found");
    }

    // Helper methods

    private Msg createTextMessage(String text, MsgRole role) {
        return Msg.builder()
                .role(role)
                .name(role == MsgRole.USER ? "user" : "assistant")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * Mock implementation of ContextOffLoader for testing.
     */
    private static class MockContextOffLoader implements ContextOffLoader {
        private final Map<String, List<Msg>> storage = new HashMap<>();

        @Override
        public void offload(String uuid, List<Msg> messages) {
            storage.put(uuid, new ArrayList<>(messages));
        }

        @Override
        public List<Msg> reload(String uuid) {
            List<Msg> messages = storage.get(uuid);
            return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        }

        @Override
        public void clear(String uuid) {
            storage.remove(uuid);
        }
    }
}
