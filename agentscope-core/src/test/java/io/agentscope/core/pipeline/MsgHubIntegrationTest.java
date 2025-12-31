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
package io.agentscope.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MsgHub.
 *
 * <p>These tests verify multi-agent conversation scenarios with real ReActAgent instances,
 * memory synchronization, and message propagation.
 */
@Tag("integration")
@DisplayName("MsgHub Integration Tests")
class MsgHubIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private MockModel model1;
    private MockModel model2;
    private MockModel model3;

    @BeforeEach
    void setUp() {
        model1 = new MockModel("Alice says hello");
        model2 = new MockModel("Bob says hi back");
        model3 = new MockModel("Charlie joins the conversation");
    }

    @Test
    @DisplayName("Should enable multi-agent conversation with automatic message broadcasting")
    void shouldEnableMultiAgentConversation() {
        // Create three agents with separate memories
        ReActAgent alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt("You are Alice, a student.")
                        .model(model1)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent bob =
                ReActAgent.builder()
                        .name("Bob")
                        .sysPrompt("You are Bob, a student.")
                        .model(model2)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent charlie =
                ReActAgent.builder()
                        .name("Charlie")
                        .sysPrompt("You are Charlie, a student.")
                        .model(model3)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        // Create announcement message
        Msg announcement =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text("Everyone introduce yourself briefly.")
                                        .build())
                        .build();

        // Use MsgHub for multi-agent conversation
        try (MsgHub hub =
                MsgHub.builder()
                        .name("StudentChat")
                        .participants(alice, bob, charlie)
                        .announcement(announcement)
                        .enableAutoBroadcast(true)
                        .build()) {

            hub.enter().block(TIMEOUT);

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

            // Alice speaks
            Msg aliceResponse = alice.call().block(TIMEOUT);
            assertNotNull(aliceResponse, "Alice should respond");

            // Bob and Charlie should automatically receive Alice's message
            // They each should have: announcement + Alice's response
            List<Msg> bobMemory = bob.getMemory().getMessages();
            List<Msg> charlieMemory = charlie.getMemory().getMessages();

            assertEquals(2, bobMemory.size(), "Bob should have announcement + Alice's response");
            assertEquals(
                    2, charlieMemory.size(), "Charlie should have announcement + Alice's response");

            // Verify the last message in Bob's memory is from Alice
            Msg lastInBob = bobMemory.get(bobMemory.size() - 1);
            assertEquals(
                    "Alice", lastInBob.getName(), "Last message in Bob's memory is from Alice");

            // Bob speaks
            Msg bobResponse = bob.call().block(TIMEOUT);
            assertNotNull(bobResponse, "Bob should respond");

            // Now Alice and Charlie should have Bob's response
            // Alice: announcement + her own response + Bob's response
            // Charlie: announcement + Alice's response + Bob's response
            List<Msg> aliceMemory = alice.getMemory().getMessages();
            charlieMemory = charlie.getMemory().getMessages();

            assertEquals(
                    3,
                    aliceMemory.size(),
                    "Alice should have announcement + her response + Bob's response");
            assertEquals(
                    3,
                    charlieMemory.size(),
                    "Charlie should have announcement + Alice's response + Bob's response");

            // Verify subscriber counts
            assertTrue(alice.hasSubscribers(), "Alice should have subscribers");
            assertEquals(
                    2,
                    alice.getSubscriberCount(),
                    "Alice should have 2 subscribers (Bob, Charlie)");
        }

        // After closing, subscribers should be cleaned up
        assertEquals(0, alice.getSubscriberCount(), "Alice should have no subscribers after close");
        assertEquals(0, bob.getSubscriberCount(), "Bob should have no subscribers after close");
        assertEquals(
                0, charlie.getSubscriberCount(), "Charlie should have no subscribers after close");
    }

    @Test
    @DisplayName("Should support dynamic participant management during conversation")
    void shouldSupportDynamicParticipants() {
        ReActAgent alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt("You are Alice.")
                        .model(model1)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent bob =
                ReActAgent.builder()
                        .name("Bob")
                        .sysPrompt("You are Bob.")
                        .model(model2)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent charlie =
                ReActAgent.builder()
                        .name("Charlie")
                        .sysPrompt("You are Charlie.")
                        .model(model3)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        try (MsgHub hub = MsgHub.builder().name("DynamicChat").participants(alice, bob).build()) {

            hub.enter().block(TIMEOUT);

            // Initial participants: Alice and Bob
            assertEquals(2, hub.getParticipants().size(), "Should start with 2 participants");

            // Alice speaks
            alice.call().block(TIMEOUT);

            // Bob should receive Alice's message (2 subscribers initially: Bob)
            assertEquals(
                    1, bob.getMemory().getMessages().size(), "Bob should have Alice's message");

            // Add Charlie to the conversation
            hub.add(charlie).block(TIMEOUT);
            assertEquals(3, hub.getParticipants().size(), "Should have 3 participants after add");

            // Bob speaks
            bob.call().block(TIMEOUT);

            // Now Charlie should receive Bob's message
            assertEquals(
                    1,
                    charlie.getMemory().getMessages().size(),
                    "Charlie should have Bob's message");

            // Remove Bob from the conversation
            hub.delete(bob).block(TIMEOUT);
            assertEquals(
                    2, hub.getParticipants().size(), "Should have 2 participants after delete");

            // Charlie speaks
            charlie.call().block(TIMEOUT);

            // Alice should receive Charlie's message, but Bob should not
            List<Msg> aliceMemory = alice.getMemory().getMessages();
            List<Msg> bobMemory = bob.getMemory().getMessages();

            // Alice: her own message + Bob's message + Charlie's message
            assertEquals(
                    3, aliceMemory.size(), "Alice should have her message + Bob's + Charlie's");

            // Bob: Alice's message + his own message (Charlie's message not received after removal)
            assertEquals(
                    2, bobMemory.size(), "Bob should not receive Charlie's message after removal");
        }
    }

    @Test
    @DisplayName("Should work with auto-broadcast disabled for manual control")
    void shouldWorkWithManualBroadcasting() {
        ReActAgent alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt("You are Alice.")
                        .model(model1)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent bob =
                ReActAgent.builder()
                        .name("Bob")
                        .sysPrompt("You are Bob.")
                        .model(model2)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        try (MsgHub hub =
                MsgHub.builder().participants(alice, bob).enableAutoBroadcast(false).build()) {

            hub.enter().block(TIMEOUT);

            // Alice speaks
            Msg aliceResponse = alice.call().block(TIMEOUT);

            // Bob should NOT receive Alice's message automatically
            assertEquals(
                    0,
                    bob.getMemory().getMessages().size(),
                    "Bob should not receive message with auto-broadcast disabled");

            // Manually broadcast Alice's message
            hub.broadcast(aliceResponse).block(TIMEOUT);

            // Now Bob should have the message
            assertEquals(
                    1,
                    bob.getMemory().getMessages().size(),
                    "Bob should receive message after manual broadcast");
        }
    }

    @Test
    @DisplayName("Should handle empty announcement gracefully")
    void shouldHandleEmptyAnnouncement() {
        ReActAgent alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt("You are Alice.")
                        .model(model1)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        try (MsgHub hub = MsgHub.builder().participants(alice).build()) {

            hub.enter().block(TIMEOUT);

            // Alice should have no messages (no announcement)
            assertEquals(
                    0,
                    alice.getMemory().getMessages().size(),
                    "Alice should have no messages without announcement");
        }
    }

    @Test
    @DisplayName("Should handle multiple announcement messages")
    void shouldHandleMultipleAnnouncements() {
        ReActAgent alice =
                ReActAgent.builder()
                        .name("Alice")
                        .sysPrompt("You are Alice.")
                        .model(model1)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        Msg msg1 =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("Welcome to the chat!").build())
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("system")
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("Please be respectful.").build())
                        .build();

        try (MsgHub hub = MsgHub.builder().participants(alice).announcement(msg1, msg2).build()) {

            hub.enter().block(TIMEOUT);

            // Alice should receive both announcements
            assertEquals(
                    2,
                    alice.getMemory().getMessages().size(),
                    "Alice should have 2 announcement messages");
        }
    }
}
