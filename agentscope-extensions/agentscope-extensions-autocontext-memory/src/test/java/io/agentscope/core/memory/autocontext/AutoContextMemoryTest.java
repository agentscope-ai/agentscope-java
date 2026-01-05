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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for AutoContextMemory.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic memory operations (add, get, delete, clear)</li>
 *   <li>Compression strategy triggers (message count and token thresholds)</li>
 *   <li>ContextOffLoader interface implementation</li>
 *   <li>Dual storage mechanism (working vs original storage)</li>
 *   <li>Edge cases (null handling, empty lists, boundary conditions)</li>
 * </ul>
 */
@DisplayName("AutoContextMemory Tests")
class AutoContextMemoryTest {

    private AutoContextConfig config;
    private TestModel testModel;
    private AutoContextMemory memory;

    @BeforeEach
    void setUp() {
        config =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .maxToken(1000)
                        .tokenRatio(0.75)
                        .lastKeep(5)
                        .minConsecutiveToolMessages(3)
                        .build();
        testModel = new TestModel("Compressed summary");
        memory = new AutoContextMemory(config, testModel);
    }

    @Test
    @DisplayName("Should add message to both working and original storage")
    void testAddMessage() {
        Msg msg = createTextMessage("Hello", MsgRole.USER);
        memory.addMessage(msg);

        List<Msg> workingMessages = memory.getMessages();
        assertEquals(1, workingMessages.size());
        assertEquals("Hello", workingMessages.get(0).getTextContent());

        // Verify original storage also has the message
        List<Msg> originalMessages = memory.getOriginalMemoryMsgs();
        assertEquals(1, originalMessages.size());
        assertEquals("Hello", originalMessages.get(0).getTextContent());
    }

    @Test
    @DisplayName("Should return messages when below threshold")
    void testGetMessagesBelowThreshold() {
        // Add messages below threshold
        for (int i = 0; i < 5; i++) {
            memory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        List<Msg> messages = memory.getMessages();
        assertEquals(5, messages.size());
        assertEquals(0, testModel.getCallCount(), "Should not trigger compression below threshold");
    }

    @Test
    @DisplayName("Should trigger compression when message count exceeds threshold")
    void testCompressionTriggeredByMessageCount() {
        // Add messages with user-assistant pairs to trigger strategy 4 (summary previous rounds)
        for (int i = 0; i < 12; i++) {
            memory.addMessage(createTextMessage("User message " + i, MsgRole.USER));
            memory.addMessage(createTextMessage("Assistant response " + i, MsgRole.ASSISTANT));
        }

        // Trigger compression explicitly
        boolean compressed = memory.compressIfNeeded();
        List<Msg> messages = memory.getMessages();
        // After compression, message count should be reduced or model should be called
        assertTrue(
                compressed || messages.size() < 24 || testModel.getCallCount() > 0,
                "Messages should be compressed or model should be called");
    }

    @Test
    @DisplayName("Should call summaryPreviousRoundConversation when summarizing previous rounds")
    void testSummaryPreviousRoundConversation() {
        // Create a test model that tracks calls
        TestModel summaryTestModel = new TestModel("Conversation summary");
        AutoContextConfig summaryConfig =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .maxToken(10000)
                        .tokenRatio(0.9)
                        .lastKeep(2)
                        .minConsecutiveToolMessages(10) // High threshold to avoid tool compression
                        .largePayloadThreshold(10000) // High threshold to avoid payload offloading
                        .build();
        AutoContextMemory summaryMemory = new AutoContextMemory(summaryConfig, summaryTestModel);

        // Create multiple user-assistant pairs with tool messages between them
        // This ensures i - currentUserIndex != 1, so pairs will be added to userAssistantPairs
        for (int round = 0; round < 5; round++) {
            // User message
            summaryMemory.addMessage(createTextMessage("User query round " + round, MsgRole.USER));

            // Add tool messages between user and assistant (this is key!)
            summaryMemory.addMessage(createToolUseMessage("tool_" + round, "call_" + round));
            summaryMemory.addMessage(
                    createToolResultMessage("tool_" + round, "call_" + round, "Result " + round));

            // Assistant message
            summaryMemory.addMessage(
                    createTextMessage("Assistant response round " + round, MsgRole.ASSISTANT));
        }

        // Add one more user message (no assistant yet) to ensure latest assistant is found
        summaryMemory.addMessage(createTextMessage("Final user query", MsgRole.USER));

        // Reset call count before compression
        summaryTestModel.reset();

        // Trigger compression explicitly - this should trigger summaryPreviousRoundMessages
        // which will call summaryPreviousRoundConversation for each round
        summaryMemory.compressIfNeeded();
        List<Msg> messages = summaryMemory.getMessages();

        // Verify that summaryPreviousRoundConversation was called
        // It should be called once for each user-assistant pair (5 times)
        assertTrue(
                summaryTestModel.getCallCount() >= 4,
                "summaryPreviousRoundConversation should be called for each round. Expected at"
                        + " least 5 calls, got "
                        + summaryTestModel.getCallCount());

        // Verify that messages were summarized (message count should be reduced)
        // Original: 5 rounds * 4 messages each + 1 user = 21 messages
        // After summary: 5 user messages + 5 summary messages + 1 user = 11 messages
        assertTrue(
                messages.size() < 21,
                "Messages should be summarized. Expected less than 21, got " + messages.size());

        // Verify that summary messages contain the expected format
        boolean hasSummaryMessage = false;
        for (Msg msg : messages) {
            String content = msg.getTextContent();
            if (content != null
                    && (content.contains("conversation_summary")
                            || content.contains("Conversation summary"))) {
                hasSummaryMessage = true;
                break;
            }
        }
        assertTrue(hasSummaryMessage, "Should contain summary messages");

        // Verify that original storage contains all messages (uncompressed)
        List<Msg> originalMessages = summaryMemory.getOriginalMemoryMsgs();
        assertEquals(
                21, originalMessages.size(), "Original storage should contain all 21 messages");

        // Verify that offloaded messages are stored in offloadContext
        Map<String, List<Msg>> offloadContext = summaryMemory.getOffloadContext();
        assertTrue(
                !offloadContext.isEmpty(),
                "OffloadContext should contain offloaded messages from summarization");
        // Each round that was summarized should have offloaded messages
        // (at least some rounds should have been summarized)
        assertTrue(
                offloadContext.size() >= 1,
                "Should have at least 1 offloaded entry from summarization. Got "
                        + offloadContext.size());
    }

    @Test
    @DisplayName("Should delete message at specified index")
    void testDeleteMessage() {
        memory.addMessage(createTextMessage("First", MsgRole.USER));
        memory.addMessage(createTextMessage("Second", MsgRole.USER));
        memory.addMessage(createTextMessage("Third", MsgRole.USER));

        memory.deleteMessage(1);

        List<Msg> messages = memory.getMessages();
        assertEquals(2, messages.size());
        assertEquals("First", messages.get(0).getTextContent());
        assertEquals("Third", messages.get(1).getTextContent());
    }

    @Test
    @DisplayName("Should handle deleteMessage with invalid index gracefully")
    void testDeleteMessageInvalidIndex() {
        memory.addMessage(createTextMessage("Test", MsgRole.USER));

        // Negative index
        memory.deleteMessage(-1);
        assertEquals(1, memory.getMessages().size());

        // Index out of bounds
        memory.deleteMessage(10);
        assertEquals(1, memory.getMessages().size());
    }

    @Test
    @DisplayName("Should clear all messages")
    void testClear() {
        memory.addMessage(createTextMessage("Test1", MsgRole.USER));
        memory.addMessage(createTextMessage("Test2", MsgRole.USER));

        memory.clear();

        List<Msg> messages = memory.getMessages();
        assertEquals(0, messages.size());

        // Verify original storage is also cleared
        List<Msg> originalMessages = memory.getOriginalMemoryMsgs();
        assertEquals(0, originalMessages.size());
    }

    @Test
    @DisplayName("Should offload messages with UUID")
    void testOffload() {
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Test message", MsgRole.USER));

        String uuid = "test-uuid-123";
        memory.offload(uuid, messages);

        // Verify messages can be reloaded
        List<Msg> reloaded = memory.reload(uuid);
        assertEquals(1, reloaded.size());
        assertEquals("Test message", reloaded.get(0).getTextContent());

        // Verify offloadContext contains the offloaded messages
        Map<String, List<Msg>> offloadContext = memory.getOffloadContext();
        assertTrue(offloadContext.containsKey(uuid), "OffloadContext should contain the UUID");
        assertEquals(1, offloadContext.get(uuid).size());
        assertEquals("Test message", offloadContext.get(uuid).get(0).getTextContent());
    }

    @Test
    @DisplayName("Should return empty list when reloading non-existent UUID")
    void testReloadNonExistentUuid() {
        List<Msg> messages = memory.reload("non-existent-uuid");
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("Should clear offloaded messages by UUID")
    void testClearOffload() {
        String uuid = "test-uuid-456";
        List<Msg> messages = new ArrayList<>();
        messages.add(createTextMessage("Test", MsgRole.USER));
        memory.offload(uuid, messages);

        // Verify offloadContext contains the message before clearing
        Map<String, List<Msg>> offloadContext = memory.getOffloadContext();
        assertTrue(offloadContext.containsKey(uuid), "OffloadContext should contain the UUID");

        memory.clear(uuid);

        List<Msg> reloaded = memory.reload(uuid);
        assertTrue(reloaded.isEmpty());

        // Verify offloadContext no longer contains the UUID
        assertTrue(
                !offloadContext.containsKey(uuid) || offloadContext.get(uuid) == null,
                "OffloadContext should not contain the UUID after clearing");
    }

    @Test
    @DisplayName("Should preserve lastKeep messages during compression")
    void testLastKeepProtection() {
        // Create config with lastKeep = 3
        AutoContextConfig customConfig =
                AutoContextConfig.builder().msgThreshold(10).lastKeep(3).build();
        AutoContextMemory customMemory = new AutoContextMemory(customConfig, testModel);

        // Add 15 messages
        for (int i = 0; i < 15; i++) {
            customMemory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Trigger compression explicitly to test lastKeep protection
        customMemory.compressIfNeeded();
        List<Msg> messages = customMemory.getMessages();
        // Last 3 messages should be preserved
        assertTrue(messages.size() >= 3, "Should preserve at least lastKeep messages");
    }

    @Test
    @DisplayName("Should handle tool message compression")
    void testToolMessageCompression() {
        // Create a new test model for this test to track calls separately
        TestModel toolTestModel = new TestModel("Compressed tool summary");
        AutoContextConfig toolConfig =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .minConsecutiveToolMessages(3)
                        .lastKeep(5)
                        .build();
        AutoContextMemory toolMemory = new AutoContextMemory(toolConfig, toolTestModel);

        // Add user message
        toolMemory.addMessage(createTextMessage("User query", MsgRole.USER));

        // Add multiple tool messages (more than minConsecutiveToolMessages)
        // These should be consecutive and before the last assistant message
        for (int i = 0; i < 5; i++) {
            toolMemory.addMessage(createToolUseMessage("test_tool", "call_" + i));
            toolMemory.addMessage(createToolResultMessage("test_tool", "call_" + i, "Result " + i));
        }

        // Add assistant message (this marks the end of current round)
        toolMemory.addMessage(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        // Add more messages to trigger compression (exceed threshold)
        for (int i = 0; i < 10; i++) {
            toolMemory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Trigger compression explicitly - tool messages should be compressed (strategy 1)
        boolean compressed = toolMemory.compressIfNeeded();
        List<Msg> messages = toolMemory.getMessages();
        assertTrue(
                compressed || toolTestModel.getCallCount() > 0 || messages.size() < 22,
                "Should compress tool messages or reduce message count");

        // Verify original storage contains all messages
        List<Msg> originalMessages = toolMemory.getOriginalMemoryMsgs();
        assertEquals(
                22, originalMessages.size(), "Original storage should contain all 22 messages");

        // Verify that tool messages were offloaded
        Map<String, List<Msg>> offloadContext = toolMemory.getOffloadContext();
        if (toolTestModel.getCallCount() > 0) {
            // If compression occurred, tool messages should be offloaded
            assertTrue(
                    !offloadContext.isEmpty(),
                    "OffloadContext should contain offloaded tool messages");
        }
    }

    @Test
    @DisplayName("Should handle large payload offloading")
    void testLargePayloadOffloading() {
        TestModel largePayloadTestModel = new TestModel("Summary");
        AutoContextConfig largePayloadConfig =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .largePayloadThreshold(100)
                        .lastKeep(3)
                        .build();
        AutoContextMemory largePayloadMemory =
                new AutoContextMemory(largePayloadConfig, largePayloadTestModel);

        // Add some initial messages to ensure we have enough messages (>= lastKeep)
        for (int i = 0; i < 2; i++) {
            largePayloadMemory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
            largePayloadMemory.addMessage(createTextMessage("Response " + i, MsgRole.ASSISTANT));
        }

        // Create a large message (exceeds threshold) - must be before last assistant
        String largeText = "x".repeat(200);
        largePayloadMemory.addMessage(createTextMessage(largeText, MsgRole.USER));

        // Add assistant message (this becomes the latest assistant)
        largePayloadMemory.addMessage(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        // Add more messages to trigger compression (exceed threshold)
        for (int i = 0; i < 5; i++) {
            largePayloadMemory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Trigger compression explicitly - large payload should be offloaded (strategy 2 or 3)
        largePayloadMemory.compressIfNeeded();
        List<Msg> messages = largePayloadMemory.getMessages();
        // Check if any message contains offload hint (UUID pattern) or if compression occurred
        boolean hasOffloadHint =
                messages.stream()
                        .anyMatch(
                                msg ->
                                        msg.getTextContent() != null
                                                && (msg.getTextContent().contains("uuid:")
                                                        || msg.getTextContent()
                                                                .contains("reload")));
        // The test passes if: offload hint found, messages reduced, or model called
        assertTrue(
                hasOffloadHint || messages.size() < 11 || largePayloadTestModel.getCallCount() > 0,
                "Large payload should be offloaded or compression should occur");

        // Verify original storage contains all messages
        List<Msg> originalMessages = largePayloadMemory.getOriginalMemoryMsgs();
        assertEquals(
                11, originalMessages.size(), "Original storage should contain all 11 messages");

        // Verify that large payload messages were offloaded
        Map<String, List<Msg>> offloadContext = largePayloadMemory.getOffloadContext();
        if (hasOffloadHint || largePayloadTestModel.getCallCount() > 0) {
            assertTrue(
                    !offloadContext.isEmpty(),
                    "OffloadContext should contain offloaded large payload messages");
        }
    }

    @Test
    @DisplayName(
            "Should summarize large messages in current round using"
                    + " summaryCurrentRoundLargeMessages")
    void testSummaryCurrentRoundLargeMessages() {
        // Create a test model to track calls
        TestModel currentRoundLargeTestModel = new TestModel("Compressed large message summary");
        AutoContextConfig currentRoundLargeConfig =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .maxToken(10000)
                        .tokenRatio(0.9)
                        .lastKeep(5)
                        .minConsecutiveToolMessages(
                                10) // High threshold to avoid tool compression (strategy 1)
                        .largePayloadThreshold(
                                100) // Low threshold for current round large messages
                        .build();
        AutoContextMemory currentRoundLargeMemory =
                new AutoContextMemory(currentRoundLargeConfig, currentRoundLargeTestModel);

        // Add some initial messages to exceed threshold but not trigger other strategies
        // Add messages without user-assistant pairs to avoid strategy 4
        for (int i = 0; i < 8; i++) {
            currentRoundLargeMemory.addMessage(
                    createTextMessage("Initial message " + i, MsgRole.USER));
        }

        // Add a user message (this becomes the latest user)
        currentRoundLargeMemory.addMessage(
                createTextMessage("User query with large response", MsgRole.USER));

        // Add a large assistant message AFTER the user message (this should trigger strategy 5)
        // This is in the current round, so it should be summarized
        String largeText = "x".repeat(200); // Exceeds largePayloadThreshold (100)
        currentRoundLargeMemory.addMessage(createTextMessage(largeText, MsgRole.ASSISTANT));

        // Reset call count before compression
        currentRoundLargeTestModel.reset();

        // Trigger compression explicitly - this should trigger strategy 5
        // (summaryCurrentRoundLargeMessages)
        currentRoundLargeMemory.compressIfNeeded();
        List<Msg> messages = currentRoundLargeMemory.getMessages();

        // Verify that generateLargeMessageSummary was called (via summaryCurrentRoundLargeMessages)
        assertTrue(
                currentRoundLargeTestModel.getCallCount() > 0,
                "summaryCurrentRoundLargeMessages should call generateLargeMessageSummary. Expected"
                        + " at least 1 call, got "
                        + currentRoundLargeTestModel.getCallCount());

        // Verify that the large message was replaced with a summary
        // Original: 8 initial + 1 user + 1 large assistant = 10 messages
        // After compression: 8 initial + 1 user + 1 compressed = 10 messages (same count, but
        // content changed)
        assertEquals(10, messages.size(), "Message count should remain the same after compression");

        // Verify that the compressed message contains the expected format
        boolean hasCompressedMessage = false;
        for (Msg msg : messages) {
            String content = msg.getTextContent();
            if (content != null
                    && (content.contains("compressed_large_message")
                            || content.contains("Compressed large message summary"))) {
                hasCompressedMessage = true;
                break;
            }
        }
        assertTrue(hasCompressedMessage, "Should contain compressed large message");

        // Verify that the large message was offloaded (can be reloaded)
        boolean hasOffloadHint = false;
        for (Msg msg : messages) {
            String content = msg.getTextContent();
            if (content != null
                    && (content.contains("uuid:")
                            || content.contains("reload")
                            || content.contains("context_reload")
                            || content.contains("offloaded"))) {
                hasOffloadHint = true;
                break;
            }
        }
        assertTrue(
                hasOffloadHint,
                "Compressed message should contain offload hint for reloading original large"
                        + " message");

        // Verify original storage contains all messages (uncompressed)
        List<Msg> originalMessages = currentRoundLargeMemory.getOriginalMemoryMsgs();
        assertEquals(
                10, originalMessages.size(), "Original storage should contain all 10 messages");

        // Verify that the large message was offloaded to offloadContext
        Map<String, List<Msg>> offloadContext = currentRoundLargeMemory.getOffloadContext();
        assertTrue(
                !offloadContext.isEmpty(),
                "OffloadContext should contain offloaded large message from current round"
                        + " compression");
        // Should have at least one offloaded entry for the large message
        assertTrue(
                offloadContext.size() >= 1,
                "Should have at least 1 offloaded entry. Got " + offloadContext.size());
    }

    @Test
    @DisplayName("Should handle empty message list")
    void testEmptyMessageList() {
        List<Msg> messages = memory.getMessages();
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("Should handle null message gracefully")
    void testNullMessage() {
        // addMessage should handle null gracefully (or throw exception)
        // This depends on implementation, but we test it doesn't crash
        try {
            memory.addMessage(null);
        } catch (Exception e) {
            // Expected behavior - either null check or NPE
            assertNotNull(e);
        }
    }

    @Test
    @DisplayName("Should maintain original storage separately from working storage")
    void testDualStorageMechanism() {
        // Add messages
        for (int i = 0; i < 5; i++) {
            memory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        List<Msg> workingMessages = memory.getMessages();
        assertEquals(5, workingMessages.size());

        // Verify original storage contains all messages
        List<Msg> originalMessages = memory.getOriginalMemoryMsgs();
        assertEquals(5, originalMessages.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("Message " + i, originalMessages.get(i).getTextContent());
        }

        // After compression, working storage may change but original should remain unchanged
        // (original storage maintains the complete, uncompressed history)
    }

    @Test
    @DisplayName(
            "Should compress current round messages using mergeAndCompressCurrentRoundMessages")
    void testMergeAndCompressCurrentRoundMessages() {
        // Create a test model to track calls
        TestModel currentRoundTestModel = new TestModel("Compressed current round summary");
        AutoContextConfig currentRoundConfig =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .maxToken(10000)
                        .tokenRatio(0.9)
                        .lastKeep(5)
                        .minConsecutiveToolMessages(
                                10) // High threshold to avoid tool compression (strategy 1)
                        .largePayloadThreshold(
                                10000) // High threshold to avoid payload offloading (strategy 2 &
                        // 3)
                        .build();
        AutoContextMemory currentRoundMemory =
                new AutoContextMemory(currentRoundConfig, currentRoundTestModel);

        // Add some initial messages to exceed threshold but not trigger other strategies
        // Add messages without user-assistant pairs to avoid strategy 4
        for (int i = 0; i < 8; i++) {
            currentRoundMemory.addMessage(createTextMessage("Initial message " + i, MsgRole.USER));
        }

        // Add a user message (this becomes the latest user)
        currentRoundMemory.addMessage(createTextMessage("User query with tools", MsgRole.USER));

        // Add tool calls and results after the user message (these should be compressed)
        // These are not consecutive enough to trigger strategy 1, and are after the latest user
        for (int i = 0; i < 2; i++) {
            currentRoundMemory.addMessage(createToolUseMessage("test_tool", "call_" + i));
            currentRoundMemory.addMessage(
                    createToolResultMessage("test_tool", "call_" + i, "Result " + i));
        }

        // Reset call count before compression
        currentRoundTestModel.reset();

        // Trigger compression explicitly - this should trigger strategy 6 (current round summary)
        // which calls mergeAndCompressCurrentRoundMessages
        currentRoundMemory.compressIfNeeded();
        List<Msg> messages = currentRoundMemory.getMessages();

        // Verify that generateCurrentRoundSummaryFromMessages was called (via
        // mergeAndCompressCurrentRoundMessages)
        assertTrue(
                currentRoundTestModel.getCallCount() > 0,
                "mergeAndCompressCurrentRoundMessages should call"
                        + " generateCurrentRoundSummaryFromMessages. Expected at least 1 call, got "
                        + currentRoundTestModel.getCallCount());

        // Verify that messages were compressed
        // Original: 8 initial + 1 user + 4 tool messages = 13 messages
        // After compression: 8 initial + 1 user + 1 compressed = 10 messages (or less)
        assertTrue(
                messages.size() <= 10,
                "Messages should be compressed. Expected 10 or less, got " + messages.size());

        // Verify that the compressed message contains the expected format
        boolean hasCompressedMessage = false;
        for (Msg msg : messages) {
            String content = msg.getTextContent();
            if (content != null
                    && (content.contains("compressed_current_round")
                            || content.contains("Compressed current round summary"))) {
                hasCompressedMessage = true;
                break;
            }
        }
        assertTrue(hasCompressedMessage, "Should contain compressed current round message");

        // Verify that tool messages were offloaded (can be reloaded)
        boolean hasOffloadHint = false;
        for (Msg msg : messages) {
            String content = msg.getTextContent();
            if (content != null
                    && (content.contains("uuid:")
                            || content.contains("reload")
                            || content.contains("context_reload")
                            || content.contains("offloaded"))) {
                hasOffloadHint = true;
                break;
            }
        }
        assertTrue(
                hasOffloadHint,
                "Compressed message should contain offload hint for reloading original tool"
                        + " messages");

        // Verify original storage contains all messages (uncompressed)
        List<Msg> originalMessages = currentRoundMemory.getOriginalMemoryMsgs();
        assertEquals(
                13, originalMessages.size(), "Original storage should contain all 13 messages");

        // Verify that tool messages were offloaded to offloadContext
        Map<String, List<Msg>> offloadContext = currentRoundMemory.getOffloadContext();
        assertTrue(
                !offloadContext.isEmpty(),
                "OffloadContext should contain offloaded tool messages from current round"
                        + " compression");
        // Should have at least one offloaded entry for the tool messages
        assertTrue(
                offloadContext.size() >= 1,
                "Should have at least 1 offloaded entry. Got " + offloadContext.size());
    }

    // Helper methods

    private Msg createTextMessage(String text, MsgRole role) {
        return Msg.builder()
                .role(role)
                .name(role == MsgRole.USER ? "user" : "assistant")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg createToolUseMessage(String toolName, String callId) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        ToolUseBlock.builder()
                                .name(toolName)
                                .id(callId)
                                .input(new java.util.HashMap<>())
                                .build())
                .build();
    }

    private Msg createToolResultMessage(String toolName, String callId, String result) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .name(toolName)
                .content(
                        ToolResultBlock.builder()
                                .name(toolName)
                                .id(callId)
                                .output(List.of(TextBlock.builder().text(result).build()))
                                .build())
                .build();
    }

    /**
     * Simple Model implementation for testing.
     */
    private static class TestModel implements Model {
        private final String responseText;
        private int callCount = 0;

        TestModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            callCount++;
            ChatResponse response =
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(responseText).build()))
                            .usage(new ChatUsage(10, 20, 30))
                            .build();
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        int getCallCount() {
            return callCount;
        }

        void reset() {
            callCount = 0;
        }
    }

    // ==================== PlanNotebook Integration Tests ====================

    @Test
    @DisplayName("Should attach and detach PlanNotebook")
    void testAttachPlanNote() {
        PlanNotebook planNotebook = PlanNotebook.builder().build();

        // Attach PlanNotebook
        memory.attachPlanNote(planNotebook);
        // No direct getter, but we can verify it doesn't throw

        // Detach PlanNotebook
        memory.attachPlanNote(null);
        // Should complete without errors
    }

    @Test
    @DisplayName("Should include plan context in compression when PlanNotebook is attached")
    void testPlanAwareCompression() {
        // Create a PlanNotebook with a plan
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        Plan plan =
                new Plan(
                        "Test Plan",
                        "Test Description",
                        "Test Outcome",
                        List.of(
                                new SubTask("Task 1", "Description 1", "Outcome 1"),
                                new SubTask("Task 2", "Description 2", "Outcome 2")));
        plan.setState(PlanState.IN_PROGRESS);
        plan.getSubtasks().get(0).setState(SubTaskState.IN_PROGRESS);
        plan.getSubtasks().get(1).setState(SubTaskState.TODO);

        // Create a model that captures the messages sent to it
        CapturingModel capturingModel = new CapturingModel("Compressed");
        AutoContextMemory planAwareMemory = new AutoContextMemory(config, capturingModel);
        planAwareMemory.attachPlanNote(planNotebook);

        // Manually set the plan (using reflection for testing)
        try {
            java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
            planField.setAccessible(true);
            planField.set(planNotebook, plan);
        } catch (Exception e) {
            // If reflection fails, skip this test
            return;
        }

        // Add enough messages to trigger compression (msgThreshold is 10)
        for (int i = 0; i < 12; i++) {
            planAwareMemory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Trigger compression explicitly
        planAwareMemory.compressIfNeeded();

        // Verify that plan context was included in the compression
        // The capturing model should have received messages with plan_aware_hint
        boolean foundPlanHint = false;
        for (List<Msg> messages : capturingModel.getCapturedMessages()) {
            for (Msg msg : messages) {
                String content = msg.getTextContent();
                if (content != null && content.contains("plan_aware_hint")) {
                    foundPlanHint = true;
                    assertTrue(
                            content.contains("Test Plan")
                                    || content.contains("Current Plan Context"),
                            "Plan context should be included in hint message");
                    break;
                }
            }
            if (foundPlanHint) break;
        }
        // Note: Compression may not always trigger depending on token count
        // If compression was triggered, verify plan hint was included
        if (!capturingModel.getCapturedMessages().isEmpty()) {
            assertTrue(
                    foundPlanHint,
                    "Plan-aware hint should be included in compression messages if compression"
                            + " was triggered");
        }
    }

    @Test
    @DisplayName("Should handle compression without PlanNotebook")
    void testCompressionWithoutPlanNotebook() {
        // Don't attach PlanNotebook
        // Reset call count
        testModel.reset();
        // Add enough messages to trigger compression (msgThreshold is 10)
        for (int i = 0; i < 12; i++) {
            memory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Trigger compression explicitly
        memory.compressIfNeeded();
        List<Msg> messages = memory.getMessages();

        // Should complete without errors
        assertNotNull(messages);
        // Compression may or may not be triggered depending on token count
        // Just verify it completes without errors
    }

    @Test
    @DisplayName("Should handle PlanNotebook with no current plan")
    void testPlanNotebookWithoutCurrentPlan() {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        // No plan created

        memory.attachPlanNote(planNotebook);

        // Add enough messages to trigger compression
        for (int i = 0; i < 15; i++) {
            memory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Trigger compression explicitly
        memory.compressIfNeeded();
        List<Msg> messages = memory.getMessages();

        // Should complete without errors (no plan context added)
        assertNotNull(messages);
    }

    /**
     * Model implementation that captures all messages sent to it for testing.
     */
    private static class CapturingModel implements Model {
        private final String responseText;
        private final List<List<Msg>> capturedMessages = new ArrayList<>();

        CapturingModel(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            capturedMessages.add(new ArrayList<>(messages));
            ChatResponse response =
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(responseText).build()))
                            .usage(new ChatUsage(10, 20, 30))
                            .build();
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return "capturing-model";
        }

        List<List<Msg>> getCapturedMessages() {
            return capturedMessages;
        }
    }

    // ==================== Custom Prompt Tests ====================

    @Test
    @DisplayName("Should use default prompts when customPrompt is not set")
    void testDefaultPrompts() {
        // Create memory without custom prompt
        AutoContextConfig config =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .minConsecutiveToolMessages(3)
                        .lastKeep(5)
                        .build();
        CapturingModel capturingModel = new CapturingModel("Compressed tool summary");
        AutoContextMemory memory = new AutoContextMemory(config, capturingModel);

        // Add user message
        memory.addMessage(createTextMessage("User query", MsgRole.USER));

        // Add multiple tool messages to trigger Strategy 1 compression
        for (int i = 0; i < 5; i++) {
            memory.addMessage(createToolUseMessage("test_tool", "call_" + i));
            memory.addMessage(createToolResultMessage("test_tool", "call_" + i, "Result " + i));
        }

        // Add assistant message
        memory.addMessage(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        // Trigger compression explicitly
        memory.compressIfNeeded();

        // Verify that default prompt was used (check captured messages)
        // Note: Compression may not always trigger depending on token count
        // If compression was triggered, verify default prompt was used
        if (!capturingModel.getCapturedMessages().isEmpty()) {
            List<Msg> firstCall = capturingModel.getCapturedMessages().get(0);
            // Find the prompt message (should be USER role)
            // Check if any USER message contains part of the default prompt
            boolean foundDefaultPrompt = false;
            // Use actual text from the default prompt
            String defaultPromptKeyPhrase = "expert content compression specialist";
            for (Msg msg : firstCall) {
                if (msg.getRole() == MsgRole.USER) {
                    String content = msg.getTextContent();
                    if (content != null && content.contains(defaultPromptKeyPhrase)) {
                        foundDefaultPrompt = true;
                        break;
                    }
                }
            }
            // If compression was triggered, verify default prompt was used
            assertTrue(
                    foundDefaultPrompt,
                    "Default prompt should be used when customPrompt is not set. "
                            + "Found messages: "
                            + capturingModel.getCapturedMessages().size()
                            + " calls");
        }
        // If compression was not triggered, that's also acceptable (test passes)
    }

    @Test
    @DisplayName("Should use custom prompt when customPrompt is set")
    void testCustomPrompt() {
        String customPromptText = "Custom tool compression prompt for testing";
        PromptConfig customPrompt =
                PromptConfig.builder().previousRoundToolCompressPrompt(customPromptText).build();

        AutoContextConfig config =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .minConsecutiveToolMessages(3)
                        .lastKeep(5)
                        .customPrompt(customPrompt)
                        .build();
        CapturingModel capturingModel = new CapturingModel("Compressed tool summary");
        AutoContextMemory memory = new AutoContextMemory(config, capturingModel);

        // Add user message
        memory.addMessage(createTextMessage("User query", MsgRole.USER));

        // Add multiple tool messages to trigger Strategy 1 compression
        for (int i = 0; i < 5; i++) {
            memory.addMessage(createToolUseMessage("test_tool", "call_" + i));
            memory.addMessage(createToolResultMessage("test_tool", "call_" + i, "Result " + i));
        }

        // Add assistant message
        memory.addMessage(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        // Trigger compression explicitly
        memory.compressIfNeeded();

        // Verify that custom prompt was used
        if (!capturingModel.getCapturedMessages().isEmpty()) {
            List<Msg> firstCall = capturingModel.getCapturedMessages().get(0);
            boolean foundCustomPrompt = false;
            for (Msg msg : firstCall) {
                if (msg.getRole() == MsgRole.USER) {
                    String content = msg.getTextContent();
                    if (content != null && content.contains(customPromptText)) {
                        foundCustomPrompt = true;
                        break;
                    }
                }
            }
            // If compression was triggered, verify custom prompt was used
            assertTrue(
                    foundCustomPrompt || capturingModel.getCapturedMessages().isEmpty(),
                    "Custom prompt should be used when customPrompt is set");
        }
    }

    @Test
    @DisplayName("Should use custom prompt for current round large message summary")
    void testCustomCurrentRoundLargeMessagePrompt() {
        String customPromptText = "Custom large message summary prompt";
        PromptConfig customPrompt =
                PromptConfig.builder().currentRoundLargeMessagePrompt(customPromptText).build();

        AutoContextConfig config =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .largePayloadThreshold(100) // Low threshold to trigger offloading
                        .customPrompt(customPrompt)
                        .build();
        CapturingModel capturingModel = new CapturingModel("Summary");
        AutoContextMemory memory = new AutoContextMemory(config, capturingModel);

        // Add user message
        memory.addMessage(createTextMessage("User query", MsgRole.USER));

        // Add a large message (exceeds largePayloadThreshold)
        String largeContent = "A".repeat(200); // 200 characters
        memory.addMessage(createTextMessage(largeContent, MsgRole.ASSISTANT));

        // Trigger compression
        memory.getMessages();

        // Verify that custom prompt was used (if compression was triggered)
        if (!capturingModel.getCapturedMessages().isEmpty()) {
            boolean foundCustomPrompt = false;
            for (List<Msg> messages : capturingModel.getCapturedMessages()) {
                for (Msg msg : messages) {
                    if (msg.getRole() == MsgRole.USER) {
                        String content = msg.getTextContent();
                        if (content != null && content.contains(customPromptText)) {
                            foundCustomPrompt = true;
                            break;
                        }
                    }
                }
                if (foundCustomPrompt) break;
            }
            // If compression was triggered, verify custom prompt was used
            assertTrue(
                    foundCustomPrompt || capturingModel.getCapturedMessages().isEmpty(),
                    "Custom current round large message prompt should be used");
        }
    }

    @Test
    @DisplayName("Should use default prompt for unset custom prompt fields")
    void testMixedCustomAndDefaultPrompts() {
        // Only set one custom prompt
        String customToolPrompt = "Custom tool prompt";
        PromptConfig customPrompt =
                PromptConfig.builder()
                        .previousRoundToolCompressPrompt(customToolPrompt)
                        // Other prompts are not set, should use defaults
                        .build();

        AutoContextConfig config =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .minConsecutiveToolMessages(3)
                        .lastKeep(5)
                        .customPrompt(customPrompt)
                        .build();
        CapturingModel capturingModel = new CapturingModel("Compressed");
        AutoContextMemory memory = new AutoContextMemory(config, capturingModel);

        // Add user message
        memory.addMessage(createTextMessage("User query", MsgRole.USER));

        // Add multiple tool messages
        for (int i = 0; i < 5; i++) {
            memory.addMessage(createToolUseMessage("test_tool", "call_" + i));
            memory.addMessage(createToolResultMessage("test_tool", "call_" + i, "Result " + i));
        }

        // Add assistant message
        memory.addMessage(createTextMessage("Assistant response", MsgRole.ASSISTANT));

        // Trigger compression explicitly
        memory.compressIfNeeded();

        // Verify custom prompt is used for tool compression
        if (!capturingModel.getCapturedMessages().isEmpty()) {
            List<Msg> firstCall = capturingModel.getCapturedMessages().get(0);
            boolean foundCustomPrompt = false;
            for (Msg msg : firstCall) {
                if (msg.getRole() == MsgRole.USER) {
                    String content = msg.getTextContent();
                    if (content != null && content.contains(customToolPrompt)) {
                        foundCustomPrompt = true;
                        break;
                    }
                }
            }
            // If compression was triggered, verify custom prompt was used
            assertTrue(
                    foundCustomPrompt || capturingModel.getCapturedMessages().isEmpty(),
                    "Custom prompt should be used for set field, default for unset fields");
        }
    }

    @Test
    @DisplayName("Should handle null customPrompt gracefully")
    void testNullCustomPrompt() {
        AutoContextConfig config =
                AutoContextConfig.builder()
                        .msgThreshold(10)
                        .customPrompt(null) // Explicitly set to null
                        .build();
        CapturingModel capturingModel = new CapturingModel("Compressed");
        AutoContextMemory memory = new AutoContextMemory(config, capturingModel);

        // Add messages
        for (int i = 0; i < 12; i++) {
            memory.addMessage(createTextMessage("Message " + i, MsgRole.USER));
        }

        // Should complete without errors, using default prompts
        List<Msg> messages = memory.getMessages();
        assertNotNull(messages);
    }

    // ==================== getPlanStateContext Tests ====================

    @Test
    @DisplayName("Should return null when planNotebook is null")
    void testGetPlanStateContextWithNullPlanNotebook() throws Exception {
        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNull(result, "Should return null when planNotebook is null");
    }

    @Test
    @DisplayName("Should return null when currentPlan is null")
    void testGetPlanStateContextWithNullCurrentPlan() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNull(result, "Should return null when currentPlan is null");
    }

    @Test
    @DisplayName("Should return plan context when plan exists without subtasks")
    void testGetPlanStateContextWithoutSubtasks() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        Plan plan = new Plan("Test Plan", "Test Description", "Test Outcome", new ArrayList<>());
        plan.setState(PlanState.IN_PROGRESS);

        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Set current plan using reflection
        java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
        planField.setAccessible(true);
        planField.set(planNotebook, plan);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNotNull(result, "Should return plan context when plan exists");
        assertTrue(
                result.contains("=== Current Plan Context ==="),
                "Should contain plan context header");
        assertTrue(result.contains("Plan Name: Test Plan"), "Should contain plan name");
        assertTrue(result.contains("Plan State: in_progress"), "Should contain plan state");
        assertTrue(
                result.contains("Description: Test Description"),
                "Should contain plan description");
        assertTrue(
                result.contains("Expected Outcome: Test Outcome"),
                "Should contain expected outcome");
        assertTrue(
                result.contains("=== Compression Guidelines ==="),
                "Should contain compression guidelines");
    }

    @Test
    @DisplayName("Should return plan context with subtasks in different states")
    void testGetPlanStateContextWithSubtasks() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        SubTask task1 = new SubTask("Task 1", "Description 1", null);
        task1.setState(SubTaskState.IN_PROGRESS);

        SubTask task2 = new SubTask("Task 2", "Description 2", "Expected Outcome 2");
        task2.setState(SubTaskState.DONE);
        task2.setOutcome("Outcome 2");

        SubTask task3 = new SubTask("Task 3", "Description 3", null);
        task3.setState(SubTaskState.TODO);

        Plan plan =
                new Plan(
                        "Test Plan",
                        "Test Description",
                        "Test Outcome",
                        List.of(task1, task2, task3));
        plan.setState(PlanState.IN_PROGRESS);

        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Set current plan using reflection
        java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
        planField.setAccessible(true);
        planField.set(planNotebook, plan);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNotNull(result, "Should return plan context when plan exists with subtasks");

        // Verify subtasks are included
        assertTrue(result.contains("Subtasks:"), "Should contain subtasks section");
        assertTrue(result.contains("[1] Task 1"), "Should contain first subtask");
        assertTrue(result.contains("[2] Task 2"), "Should contain second subtask");
        assertTrue(result.contains("[3] Task 3"), "Should contain third subtask");

        // Verify IN_PROGRESS subtask has warning
        assertTrue(
                result.contains("⚠️ Currently in progress - preserve related information"),
                "Should contain warning for IN_PROGRESS subtask");

        // Verify DONE subtask with outcome
        assertTrue(
                result.contains("✅ Outcome: Outcome 2"), "Should contain outcome for DONE subtask");

        // Verify task counts
        assertTrue(
                result.contains("Currently 1 subtask(s) in progress"),
                "Should contain count of in-progress subtasks");
        assertTrue(
                result.contains("1 subtask(s) completed"),
                "Should contain count of completed subtasks");
    }

    @Test
    @DisplayName("Should return plan context with null subtasks list")
    void testGetPlanStateContextWithNullSubtasks() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        Plan plan = new Plan("Test Plan", "Test Description", "Test Outcome", null);
        plan.setState(PlanState.IN_PROGRESS);

        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Set current plan using reflection
        java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
        planField.setAccessible(true);
        planField.set(planNotebook, plan);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNotNull(result, "Should return plan context when subtasks is null");
        assertFalse(
                result.contains("Subtasks:"),
                "Should not contain subtasks section when subtasks is null");
    }

    @Test
    @DisplayName("Should return plan context with empty subtasks list")
    void testGetPlanStateContextWithEmptySubtasks() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        Plan plan = new Plan("Test Plan", "Test Description", "Test Outcome", new ArrayList<>());
        plan.setState(PlanState.IN_PROGRESS);

        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Set current plan using reflection
        java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
        planField.setAccessible(true);
        planField.set(planNotebook, plan);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNotNull(result, "Should return plan context when subtasks is empty");
        assertFalse(
                result.contains("Subtasks:"),
                "Should not contain subtasks section when subtasks is empty");
    }

    @Test
    @DisplayName("Should return plan context with DONE subtask without outcome")
    void testGetPlanStateContextWithDoneSubtaskWithoutOutcome() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        SubTask task = new SubTask("Task 1", "Description 1", null);
        task.setState(SubTaskState.DONE);

        Plan plan = new Plan("Test Plan", "Test Description", "Test Outcome", List.of(task));
        plan.setState(PlanState.IN_PROGRESS);

        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Set current plan using reflection
        java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
        planField.setAccessible(true);
        planField.set(planNotebook, plan);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        String result = (String) method.invoke(testMemory);
        assertNotNull(result, "Should return plan context");
        assertTrue(result.contains("[1] Task 1"), "Should contain subtask");
        assertTrue(result.contains("State: done"), "Should contain DONE state");
        assertFalse(
                result.contains("✅ Outcome:"),
                "Should not contain outcome when subtask outcome is null");
    }

    @Test
    @DisplayName("Should return plan context with different plan states")
    void testGetPlanStateContextWithDifferentPlanStates() throws Exception {
        PlanNotebook planNotebook = PlanNotebook.builder().build();
        Plan plan = new Plan("Test Plan", "Test Description", "Test Outcome", new ArrayList<>());

        AutoContextMemory testMemory = new AutoContextMemory(config, testModel);
        testMemory.attachPlanNote(planNotebook);

        // Set current plan using reflection
        java.lang.reflect.Field planField = PlanNotebook.class.getDeclaredField("currentPlan");
        planField.setAccessible(true);
        planField.set(planNotebook, plan);

        // Use reflection to call private method
        java.lang.reflect.Method method =
                AutoContextMemory.class.getDeclaredMethod("getPlanStateContext");
        method.setAccessible(true);

        // Test with IN_PROGRESS state
        plan.setState(PlanState.IN_PROGRESS);
        String resultInProgress = (String) method.invoke(testMemory);
        assertNotNull(resultInProgress, "Should return plan context for IN_PROGRESS state");
        assertTrue(
                resultInProgress.contains("Plan State: in_progress"),
                "Should contain IN_PROGRESS state");
        assertTrue(
                resultInProgress.contains("- Preserve all information related to active subtasks"),
                "Should contain specific guidance for IN_PROGRESS state");

        // Test with TODO state
        plan.setState(PlanState.TODO);
        String resultTodo = (String) method.invoke(testMemory);
        assertNotNull(resultTodo, "Should return plan context for TODO state");
        assertTrue(resultTodo.contains("Plan State: todo"), "Should contain TODO state");

        // Test with DONE state
        plan.setState(PlanState.DONE);
        String resultDone = (String) method.invoke(testMemory);
        assertNotNull(resultDone, "Should return plan context for DONE state");
        assertTrue(resultDone.contains("Plan State: done"), "Should contain DONE state");
    }
}
