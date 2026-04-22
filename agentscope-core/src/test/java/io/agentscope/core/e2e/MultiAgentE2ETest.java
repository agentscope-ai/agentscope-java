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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consolidated E2E tests for multi-agent collaboration functionality.
 *
 * <p>Tests multi-agent scenarios using MsgHub across various scenarios including:
 * <ul>
 *   <li>Basic multi-agent conversation with automatic broadcasting</li>
 *   <li>Multi-agent collaboration with tool calling</li>
 *   <li>Role-based collaboration (innovator, critic, synthesizer)</li>
 *   <li>Dynamic participant management (add/remove agents)</li>
 *   <li>Multi-agent with structured output generation</li>
 *   <li>Manual broadcast control</li>
 * </ul>
 *
 * <p><b>Requirements:</b> OPENAI_API_KEY and/or DASHSCOPE_API_KEY environment variables
 * must be set. Tests use MultiAgent formatters for proper multi-agent message handling.
 */
@Tag("e2e")
@Tag("multi-agent")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("MultiAgent E2E Tests")
class MultiAgentE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    // ==================== Data Structure Definitions ====================

    /** Discussion summary structure. */
    public static class DiscussionSummary {
        public String topic;
        public List<String> keyPoints;
        public String conclusion;
        public List<String> participants;

        @Override
        public String toString() {
            return "DiscussionSummary{"
                    + "topic='"
                    + topic
                    + '\''
                    + ", keyPoints="
                    + keyPoints
                    + ", conclusion='"
                    + conclusion
                    + '\''
                    + ", participants="
                    + participants
                    + '}';
        }
    }

    // ==================== Test Methods ====================

    private void sanitizeMemory(ReActAgent agent) {
        List<Msg> msgs = new ArrayList<>(agent.getMemory().getMessages());
        agent.getMemory().clear();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT && !agent.getName().equals(msg.getName())) {
                msg =
                        Msg.builder()
                                .id(msg.getId())
                                .name(msg.getName())
                                .role(MsgRole.USER)
                                .content(msg.getContent())
                                .build();
            }
            agent.getMemory().addMessage(msg);
        }
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle basic multi-agent conversation with MsgHub")
    void testBasicMultiAgentConversation(ModelProvider provider) {
        if (provider.getClass().getName().contains("MultiAgent")
                && (provider.getProviderName().equals("Google")
                        || provider.getProviderName().equals("Anthropic"))) {
            // Gemini and Claude might return empty data in this case
            return;
        }

        System.out.println(
                "\n=== Test: Basic Multi-Agent Conversation with "
                        + provider.getProviderName()
                        + " ===");

        // Create three agents
        Toolkit toolkit = new Toolkit();

        ReActAgent alice = provider.createAgent("Alice", toolkit);
        ReActAgent bob = provider.createAgent("Bob", toolkit);
        ReActAgent charlie = provider.createAgent("Charlie", toolkit);

        // Create announcement
        Msg announcement =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Welcome everyone! Please introduce yourself"
                                                        + " briefly in one sentence.")
                                        .build())
                        .build();
        System.out.println("Announcement: " + TestUtils.extractTextContent(announcement));

        // Use MsgHub for multi-agent conversation
        try (MsgHub hub =
                MsgHub.builder()
                        .name("StudentChat")
                        .participants(alice, bob, charlie)
                        .announcement(announcement)
                        .enableAutoBroadcast(true)
                        .build()) {

            hub.enter().block(TEST_TIMEOUT);

            // Verify all agents received the announcement
            assertEquals(
                    1,
                    alice.getMemory().getMessages().size(),
                    "Alice should have announcement in memory");
            assertEquals(
                    1,
                    bob.getMemory().getMessages().size(),
                    "Bob should have announcement in memory");
            assertEquals(
                    1,
                    charlie.getMemory().getMessages().size(),
                    "Charlie should have announcement in memory");

            System.out.println("\n--- Round 1: Alice introduces herself ---");
            Msg aliceResponse = alice.call().block(TEST_TIMEOUT);
            assertNotNull(aliceResponse, "Alice should respond");
            System.out.println("Alice: " + TestUtils.extractTextContent(aliceResponse));

            // Bob and Charlie should receive Alice's message
            assertEquals(
                    2,
                    bob.getMemory().getMessages().size(),
                    "Bob should have announcement + Alice's response");
            assertEquals(
                    2,
                    charlie.getMemory().getMessages().size(),
                    "Charlie should have announcement + Alice's response");

            System.out.println("\n--- Round 2: Bob introduces himself ---");
            sanitizeMemory(bob);
            Msg bobResponse = bob.call().block(TEST_TIMEOUT);
            assertNotNull(bobResponse, "Bob should respond");
            System.out.println("Bob: " + TestUtils.extractTextContent(bobResponse));

            // Alice and Charlie should receive Bob's message
            List<Msg> aliceMemory = alice.getMemory().getMessages();
            List<Msg> charlieMemory = charlie.getMemory().getMessages();

            assertEquals(
                    3,
                    aliceMemory.size(),
                    "Alice should have announcement + her response + Bob's response");
            assertEquals(
                    3,
                    charlieMemory.size(),
                    "Charlie should have announcement + Alice's response + Bob's response");

            System.out.println("\n--- Round 3: Charlie introduces himself ---");
            sanitizeMemory(charlie);
            Msg charlieResponse = charlie.call().block(TEST_TIMEOUT);
            assertNotNull(charlieResponse, "Charlie should respond");
            System.out.println("Charlie: " + TestUtils.extractTextContent(charlieResponse));

            // Verify final memory states
            aliceMemory = alice.getMemory().getMessages();
            List<Msg> bobMemory = bob.getMemory().getMessages();
            charlieMemory = charlie.getMemory().getMessages();

            assertTrue(
                    aliceMemory.size() >= 4,
                    "Alice should have full conversation history for " + provider.getModelName());
            assertTrue(
                    bobMemory.size() >= 4,
                    "Bob should have full conversation history for " + provider.getModelName());
            assertTrue(
                    charlieMemory.size() >= 3,
                    "Charlie should have conversation history for " + provider.getModelName());

            // Verify subscriber relationships
            assertTrue(alice.hasSubscribers(), "Alice should have subscribers");
            assertEquals(2, alice.getSubscriberCount(), "Alice should have 2 subscribers");

            System.out.println("\nFinal memory sizes:");
            System.out.println("  Alice: " + aliceMemory.size() + " messages");
            System.out.println("  Bob: " + bobMemory.size() + " messages");
            System.out.println("  Charlie: " + charlieMemory.size() + " messages");
        }

        // After closing, subscribers should be cleaned up
        assertEquals(0, alice.getSubscriberCount(), "Alice should have no subscribers after close");
        assertEquals(0, bob.getSubscriberCount(), "Bob should have no subscribers after close");
        assertEquals(
                0, charlie.getSubscriberCount(), "Charlie should have no subscribers after close");

        System.out.println(
                "✓ Basic multi-agent conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should handle multi-agent with tool calling")
    void testMultiAgentWithToolCalling(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Multi-Agent with Tool Calling - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();

        ReActAgent researcher = provider.createAgent("Researcher", toolkit);
        ReActAgent reviewer = provider.createAgent("Reviewer", toolkit);

        Msg announcement =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Researcher, please calculate 15 times 8 using the"
                                                    + " multiply tool. Reviewer, please comment on"
                                                    + " the result.")
                                        .build())
                        .build();

        try (MsgHub hub =
                MsgHub.builder()
                        .name("ResearchHub")
                        .participants(researcher, reviewer)
                        .announcement(announcement)
                        .build()) {

            hub.enter().block(TEST_TIMEOUT);

            System.out.println("\n--- Researcher performs calculation ---");
            Msg researcherResponse = researcher.call().block(TEST_TIMEOUT);
            assertNotNull(researcherResponse, "Researcher should respond");
            System.out.println("Researcher: " + TestUtils.extractTextContent(researcherResponse));

            // Verify researcher used tools
            List<Msg> researcherMemory = researcher.getMemory().getMessages();
            boolean hasToolMessage =
                    researcherMemory.stream()
                            .anyMatch(
                                    m ->
                                            m.getRole() == MsgRole.ASSISTANT
                                                    || m.getRole() == MsgRole.TOOL);
            assertTrue(
                    hasToolMessage,
                    "Researcher should have tool-related messages for " + provider.getModelName());

            // Reviewer should receive researcher's response
            List<Msg> reviewerMemory = reviewer.getMemory().getMessages();
            assertTrue(
                    reviewerMemory.size() >= 2,
                    "Reviewer should have announcement + researcher's response");

            System.out.println("\n--- Reviewer analyzes result ---");
            Msg reviewerResponse = reviewer.call().block(TEST_TIMEOUT);
            assertNotNull(reviewerResponse, "Reviewer should respond");
            System.out.println("Reviewer: " + TestUtils.extractTextContent(reviewerResponse));

            // Researcher should receive reviewer's feedback
            researcherMemory = researcher.getMemory().getMessages();
            boolean hasReviewerMessage =
                    researcherMemory.stream().anyMatch(m -> "Reviewer".equals(m.getName()));
            assertTrue(
                    hasReviewerMessage,
                    "Researcher should have received reviewer's message for "
                            + provider.getModelName());

            System.out.println("\nFinal memory sizes:");
            System.out.println("  Researcher: " + researcherMemory.size() + " messages");
            System.out.println("  Reviewer: " + reviewerMemory.size() + " messages");
        }

        System.out.println(
                "✓ Multi-agent with tool calling verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should support role-based collaboration (innovator, critic, synthesizer)")
    void testRoleBasedMultiAgentCollaboration(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Role-Based Multi-Agent Collaboration - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent innovator = provider.createAgent("Innovator", toolkit);
        ReActAgent critic = provider.createAgent("Critic", toolkit);
        ReActAgent synthesizer = provider.createAgent("Synthesizer", toolkit);

        Msg topic =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Topic: How can we reduce carbon emissions in urban"
                                                    + " transportation? Innovator (creative"
                                                    + " thinker), please share your innovative idea"
                                                    + " first. Critic (feasibility analyst),"
                                                    + " evaluate the idea. Synthesizer, combine the"
                                                    + " viewpoints. Keep responses brief (1-2"
                                                    + " sentences each).")
                                        .build())
                        .build();

        try (MsgHub hub =
                MsgHub.builder()
                        .name("BrainstormHub")
                        .participants(innovator, critic, synthesizer)
                        .announcement(topic)
                        .build()) {

            hub.enter().block(TEST_TIMEOUT);

            System.out.println("\n--- Phase 1: Innovator proposes idea ---");
            Msg innovatorResponse = innovator.call().block(TEST_TIMEOUT);
            assertNotNull(innovatorResponse, "Innovator should respond");
            System.out.println("Innovator: " + TestUtils.extractTextContent(innovatorResponse));

            System.out.println("\n--- Phase 2: Critic evaluates idea ---");
            Msg criticResponse = critic.call().block(TEST_TIMEOUT);
            assertNotNull(criticResponse, "Critic should respond");
            System.out.println("Critic: " + TestUtils.extractTextContent(criticResponse));

            System.out.println("\n--- Phase 3: Synthesizer combines viewpoints ---");
            Msg synthesizerResponse = synthesizer.call().block(TEST_TIMEOUT);
            assertNotNull(synthesizerResponse, "Synthesizer should respond");
            System.out.println("Synthesizer: " + TestUtils.extractTextContent(synthesizerResponse));

            // Verify all agents have the full discussion
            assertTrue(
                    innovator.getMemory().getMessages().size() >= 3,
                    "Innovator should have full discussion for " + provider.getModelName());
            assertTrue(
                    critic.getMemory().getMessages().size() >= 3,
                    "Critic should have full discussion for " + provider.getModelName());
            assertTrue(
                    synthesizer.getMemory().getMessages().size() >= 2,
                    "Synthesizer should have discussion for " + provider.getModelName());

            // Verify role differentiation through content
            String innovatorText = TestUtils.extractTextContent(innovatorResponse);
            String criticText = TestUtils.extractTextContent(criticResponse);

            assertTrue(
                    innovatorText.length() > 5,
                    "Innovator should have meaningful content for " + provider.getModelName());
            assertTrue(
                    criticText.length() > 5,
                    "Critic should have meaningful content for " + provider.getModelName());
        }

        System.out.println("✓ Role-based collaboration verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should support dynamic participant management")
    void testDynamicParticipantManagement(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Dynamic Participant Management - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent alice = provider.createAgent("Alice", toolkit);
        ReActAgent bob = provider.createAgent("Bob", toolkit);
        ReActAgent charlie = provider.createAgent("Charlie", toolkit);

        try (MsgHub hub = MsgHub.builder().name("DynamicChat").participants(alice, bob).build()) {

            hub.enter().block(TEST_TIMEOUT);

            // Initial participants: Alice and Bob
            assertEquals(2, hub.getParticipants().size(), "Should start with 2 participants");
            System.out.println("Initial participants: Alice, Bob");

            System.out.println("\n--- Alice speaks (before Charlie joins) ---");
            Msg aliceMsg =
                    TestUtils.createUserMessage("Alice", "Hello everyone, I'm starting the chat.");
            hub.broadcast(aliceMsg).block(TEST_TIMEOUT);

            // Bob should receive Alice's message
            assertEquals(
                    1, bob.getMemory().getMessages().size(), "Bob should have Alice's message");

            System.out.println("\n--- Adding Charlie to the conversation ---");
            hub.add(charlie).block(TEST_TIMEOUT);
            assertEquals(3, hub.getParticipants().size(), "Should have 3 participants after add");
            System.out.println("Participants after add: Alice, Bob, Charlie");

            System.out.println("\n--- Bob speaks (after Charlie joined) ---");
            Msg bobMsg = TestUtils.createUserMessage("Bob", "Welcome Charlie!");
            hub.broadcast(bobMsg).block(TEST_TIMEOUT);

            // Charlie should receive Bob's message
            assertEquals(
                    1,
                    charlie.getMemory().getMessages().size(),
                    "Charlie should have Bob's message");

            System.out.println("\n--- Removing Bob from the conversation ---");
            hub.delete(bob).block(TEST_TIMEOUT);
            assertEquals(
                    2, hub.getParticipants().size(), "Should have 2 participants after delete");
            System.out.println("Participants after delete: Alice, Charlie");

            System.out.println("\n--- Charlie speaks (after Bob left) ---");
            Msg charlieMsg = TestUtils.createUserMessage("Charlie", "Thanks for having me!");
            hub.broadcast(charlieMsg).block(TEST_TIMEOUT);

            // Alice should receive Charlie's message
            List<Msg> aliceMemory = alice.getMemory().getMessages();
            assertTrue(
                    aliceMemory.size() >= 2,
                    "Alice should have messages from the conversation for "
                            + provider.getModelName());

            // Bob should NOT receive Charlie's message (he was removed)
            List<Msg> bobMemory = bob.getMemory().getMessages();
            assertEquals(
                    2,
                    bobMemory.size(),
                    "Bob should not receive Charlie's message after removal for "
                            + provider.getModelName());

            System.out.println("\nFinal memory sizes:");
            System.out.println("  Alice: " + aliceMemory.size() + " messages");
            System.out.println("  Bob: " + bobMemory.size() + " messages (removed)");
            System.out.println(
                    "  Charlie: " + charlie.getMemory().getMessages().size() + " messages");
        }

        System.out.println(
                "✓ Dynamic participant management verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should combine multi-agent with structured output")
    void testMultiAgentWithStructuredOutput(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Multi-Agent with Structured Output - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent analyst1 = provider.createAgent("Analyst1", toolkit);
        ReActAgent analyst2 = provider.createAgent("Analyst2", toolkit);
        ReActAgent summarizer = provider.createAgent("Summarizer", toolkit);

        Msg topic =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "Topic: Benefits of renewable energy. Analysts,"
                                                        + " please share your key points.")
                                        .build())
                        .build();

        try (MsgHub hub =
                MsgHub.builder()
                        .name("DiscussionHub")
                        .participants(analyst1, analyst2, summarizer)
                        .announcement(topic)
                        .build()) {

            hub.enter().block(TEST_TIMEOUT);

            System.out.println("\n--- Analyst 1 shares insight ---");
            Msg analyst1Response = analyst1.call().block(TEST_TIMEOUT);
            assertNotNull(analyst1Response, "Analyst1 should respond");
            System.out.println("Analyst1: " + TestUtils.extractTextContent(analyst1Response));

            System.out.println("\n--- Analyst 2 shares insight ---");
            Msg analyst2Response = analyst2.call().block(TEST_TIMEOUT);
            assertNotNull(analyst2Response, "Analyst2 should respond");
            System.out.println("Analyst2: " + TestUtils.extractTextContent(analyst2Response));

            System.out.println("\n--- Summarizer creates structured summary ---");
            Msg summaryRequest =
                    TestUtils.createUserMessage(
                            "User",
                            "Summarizer, please create a structured summary of the discussion.");
            hub.broadcast(summaryRequest).block(TEST_TIMEOUT);

            Msg structuredResponse = summarizer.call(DiscussionSummary.class).block(TEST_TIMEOUT);
            assertNotNull(structuredResponse, "Summarizer should generate structured output");
            System.out.println("Raw response: " + TestUtils.extractTextContent(structuredResponse));

            // Extract and validate structured summary
            DiscussionSummary summary =
                    structuredResponse.getStructuredData(DiscussionSummary.class);
            assertNotNull(summary, "Summary should be extracted");
            System.out.println("Structured summary: " + summary);

            // Validate summary fields
            assertNotNull(summary.topic, "Topic should be populated");
            assertNotNull(summary.keyPoints, "Key points should be populated");

            System.out.println("Summary details:");
            System.out.println("  Topic: " + summary.topic);
            System.out.println("  Key Points: " + summary.keyPoints);
            System.out.println("  Conclusion: " + summary.conclusion);
        }

        System.out.println(
                "✓ Multi-agent with structured output verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should support manual broadcast control")
    void testMsgHubManualBroadcast(ModelProvider provider) {
        System.out.println(
                "\n=== Test: MsgHub Manual Broadcast - " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent alice = provider.createAgent("Alice", toolkit);
        ReActAgent bob = provider.createAgent("Bob", toolkit);

        try (MsgHub hub =
                MsgHub.builder()
                        .name("ManualChat")
                        .participants(alice, bob)
                        .enableAutoBroadcast(false)
                        .build()) {

            hub.enter().block(TEST_TIMEOUT);

            System.out.println("\n--- Alice speaks (auto-broadcast disabled) ---");
            Msg aliceMsg = TestUtils.createUserMessage("Alice", "Hello Bob!");
            alice.observe(aliceMsg).block(TEST_TIMEOUT);

            // Bob should NOT receive Alice's message automatically
            assertEquals(
                    0,
                    bob.getMemory().getMessages().size(),
                    "Bob should not receive message with auto-broadcast disabled");

            System.out.println("\n--- Manually broadcast Alice's message ---");
            hub.broadcast(aliceMsg).block(TEST_TIMEOUT);

            // Now Bob should have the message
            assertEquals(
                    1,
                    bob.getMemory().getMessages().size(),
                    "Bob should receive message after manual broadcast");

            Msg bobMsg = bob.call().block(TEST_TIMEOUT);
            assertNotNull(bobMsg, "Bob should respond");
            System.out.println("Bob: " + TestUtils.extractTextContent(bobMsg));

            // Manually broadcast Bob's response
            hub.broadcast(bobMsg).block(TEST_TIMEOUT);

            // Alice should receive Bob's response
            List<Msg> aliceMemory = alice.getMemory().getMessages();
            boolean hasBobMessage = aliceMemory.stream().anyMatch(m -> "Bob".equals(m.getName()));
            assertTrue(
                    hasBobMessage,
                    "Alice should have Bob's message after manual broadcast for "
                            + provider.getModelName());
        }

        System.out.println("✓ Manual broadcast control verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify basic provider availability for multi-agent tests")
    void testProviderAvailability() {
        System.out.println("\n=== Test: Provider Availability ===");

        long enabledBasicProviders = ProviderFactory.getBasicProviders().count();

        System.out.println("Enabled basic providers: " + enabledBasicProviders);

        // At least one provider should be available if API keys are set
        assertTrue(
                enabledBasicProviders > 0,
                "At least one provider should be enabled (check OPENAI_API_KEY or"
                        + " DASHSCOPE_API_KEY)");

        System.out.println("✓ Provider availability verified");
    }
}
