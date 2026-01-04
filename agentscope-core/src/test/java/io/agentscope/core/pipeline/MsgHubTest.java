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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for MsgHub.
 *
 * <p>These tests verify message broadcasting, participant management, lifecycle, and auto-broadcast
 * functionality using mock agents.
 */
@Tag("unit")
@DisplayName("MsgHub Unit Tests")
class MsgHubTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private AgentBase alice;
    private AgentBase bob;
    private AgentBase charlie;
    private Msg testMsg;

    @BeforeEach
    void setUp() {
        alice = createMockAgent("Alice");
        bob = createMockAgent("Bob");
        charlie = createMockAgent("Charlie");

        testMsg =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("Test message").build())
                        .build();
    }

    @Test
    @DisplayName("Should broadcast announcement to all participants on enter")
    void shouldBroadcastAnnouncementOnEnter() {
        Msg announcement =
                Msg.builder()
                        .name("System")
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("Hello everyone!").build())
                        .build();

        MsgHub hub =
                MsgHub.builder()
                        .participants(alice, bob, charlie)
                        .announcement(announcement)
                        .build();

        hub.enter().block(TIMEOUT);

        // All participants should receive the announcement
        verify(alice, times(1)).observe(announcement);
        verify(bob, times(1)).observe(announcement);
        verify(charlie, times(1)).observe(announcement);

        hub.close();
    }

    @Test
    @DisplayName("Should manually broadcast message to all participants")
    void shouldManuallyBroadcast() {
        MsgHub hub = MsgHub.builder().participants(alice, bob).build();

        hub.enter().block(TIMEOUT);
        hub.broadcast(testMsg).block(TIMEOUT);

        verify(alice, times(1)).observe(testMsg);
        verify(bob, times(1)).observe(testMsg);

        hub.close();
    }

    @Test
    @DisplayName("Should setup subscriber relationships when auto-broadcast is enabled")
    void shouldSetupSubscribersWithAutoBroadcast() {
        MsgHub hub =
                MsgHub.builder()
                        .name("TestHub")
                        .participants(alice, bob, charlie)
                        .enableAutoBroadcast(true)
                        .build();

        hub.enter().block(TIMEOUT);

        // Each agent should have 2 subscribers (the other two agents)
        verify(alice, times(1)).resetSubscribers(any(), any());
        verify(bob, times(1)).resetSubscribers(any(), any());
        verify(charlie, times(1)).resetSubscribers(any(), any());

        hub.close();
    }

    @Test
    @DisplayName("Should not setup subscribers when auto-broadcast is disabled")
    void shouldNotSetupSubscribersWhenDisabled() {
        MsgHub hub = MsgHub.builder().participants(alice, bob).enableAutoBroadcast(false).build();

        hub.enter().block(TIMEOUT);

        // Agents should not have subscribers set
        verify(alice, never()).resetSubscribers(any(), any());
        verify(bob, never()).resetSubscribers(any(), any());

        hub.close();
    }

    @Test
    @DisplayName("Should cleanup subscribers on exit")
    void shouldCleanupSubscribersOnExit() {
        MsgHub hub =
                MsgHub.builder()
                        .name("TestHub")
                        .participants(alice, bob)
                        .enableAutoBroadcast(true)
                        .build();

        hub.enter().block(TIMEOUT);
        hub.exit().block(TIMEOUT);

        // Subscribers should be removed
        verify(alice, times(1)).removeSubscribers("TestHub");
        verify(bob, times(1)).removeSubscribers("TestHub");
    }

    @Test
    @DisplayName("Should support try-with-resources for automatic cleanup")
    void shouldSupportTryWithResources() {
        try (MsgHub hub =
                MsgHub.builder()
                        .name("TestHub")
                        .participants(alice, bob)
                        .enableAutoBroadcast(true)
                        .build()) {

            hub.enter().block(TIMEOUT);

            // Verify setup
            verify(alice, times(1)).resetSubscribers(any(), any());
            verify(bob, times(1)).resetSubscribers(any(), any());
        }

        // After close(), subscribers should be removed
        verify(alice, times(1)).removeSubscribers("TestHub");
        verify(bob, times(1)).removeSubscribers("TestHub");
    }

    @Test
    @DisplayName("Should add new participants dynamically")
    void shouldAddParticipantsDynamically() {
        MsgHub hub =
                MsgHub.builder()
                        .name("TestHub")
                        .participants(alice, bob)
                        .enableAutoBroadcast(true)
                        .build();

        hub.enter().block(TIMEOUT);

        // Add charlie
        hub.add(charlie).block(TIMEOUT);

        // Subscribers should be reset for all agents (including charlie)
        verify(alice, times(2)).resetSubscribers(any(), any()); // Initial + after add
        verify(bob, times(2)).resetSubscribers(any(), any());
        verify(charlie, times(1)).resetSubscribers(any(), any());

        assertEquals(3, hub.getParticipants().size(), "Should have 3 participants");
        assertTrue(hub.getParticipants().contains(charlie), "Charlie should be in participants");

        hub.close();
    }

    @Test
    @DisplayName("Should delete participants dynamically")
    void shouldDeleteParticipantsDynamically() {
        MsgHub hub =
                MsgHub.builder()
                        .name("TestHub")
                        .participants(alice, bob, charlie)
                        .enableAutoBroadcast(true)
                        .build();

        hub.enter().block(TIMEOUT);

        // Delete charlie
        hub.delete(charlie).block(TIMEOUT);

        // Subscribers should be reset after deletion
        verify(alice, times(2)).resetSubscribers(any(), any()); // Initial + after delete
        verify(bob, times(2)).resetSubscribers(any(), any());

        assertEquals(2, hub.getParticipants().size(), "Should have 2 participants");
        assertFalse(
                hub.getParticipants().contains(charlie), "Charlie should not be in participants");

        hub.close();
    }

    @Test
    @DisplayName("Should toggle auto-broadcast dynamically")
    void shouldToggleAutoBroadcast() {
        MsgHub hub =
                MsgHub.builder()
                        .name("TestHub")
                        .participants(alice, bob)
                        .enableAutoBroadcast(true)
                        .build();

        hub.enter().block(TIMEOUT);
        assertTrue(hub.isAutoBroadcastEnabled(), "Auto-broadcast should be enabled initially");

        // Disable auto-broadcast
        hub.setAutoBroadcast(false);
        assertFalse(hub.isAutoBroadcastEnabled(), "Auto-broadcast should be disabled");

        // Subscribers should be removed
        verify(alice, times(1)).removeSubscribers("TestHub");
        verify(bob, times(1)).removeSubscribers("TestHub");

        // Re-enable auto-broadcast
        hub.setAutoBroadcast(true);
        assertTrue(hub.isAutoBroadcastEnabled(), "Auto-broadcast should be re-enabled");

        // Subscribers should be reset again
        verify(alice, times(2)).resetSubscribers(any(), any());
        verify(bob, times(2)).resetSubscribers(any(), any());

        hub.close();
    }

    @Test
    @DisplayName("Should broadcast multiple messages")
    void shouldBroadcastMultipleMessages() {
        MsgHub hub = MsgHub.builder().participants(alice, bob).build();

        Msg msg1 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Message 1").build())
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Message 2").build())
                        .build();

        hub.enter().block(TIMEOUT);
        hub.broadcast(List.of(msg1, msg2)).block(TIMEOUT);

        verify(alice, times(1)).observe(msg1);
        verify(alice, times(1)).observe(msg2);
        verify(bob, times(1)).observe(msg1);
        verify(bob, times(1)).observe(msg2);

        hub.close();
    }

    @Test
    @DisplayName("Should reject empty participant list")
    void shouldRejectEmptyParticipants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MsgHub.builder().participants(List.of()).build(),
                "Should reject empty participant list");
    }

    @Test
    @DisplayName("Should generate random name if not provided")
    void shouldGenerateRandomName() {
        MsgHub hub = MsgHub.builder().participants(alice, bob).build();

        assertNotNull(hub.getName(), "Hub name should not be null");
        assertFalse(hub.getName().isEmpty(), "Hub name should not be empty");
    }

    @Test
    @DisplayName("Should use provided name")
    void shouldUseProvidedName() {
        MsgHub hub = MsgHub.builder().name("MyHub").participants(alice, bob).build();

        assertEquals("MyHub", hub.getName(), "Hub should use provided name");
    }

    @Test
    @DisplayName("Should not reset subscribers when adding participants before enter")
    void shouldNotResetSubscribersBeforeEnter() {
        MsgHub hub = MsgHub.builder().participants(alice).enableAutoBroadcast(true).build();

        // Add bob before entering
        hub.add(bob).block(TIMEOUT);

        // Subscribers should not be set yet
        verify(alice, never()).resetSubscribers(any(), any());
        verify(bob, never()).resetSubscribers(any(), any());

        // Now enter
        hub.enter().block(TIMEOUT);

        // Subscribers should be set for both
        verify(alice, times(1)).resetSubscribers(any(), any());
        verify(bob, times(1)).resetSubscribers(any(), any());

        hub.close();
    }

    @Test
    @DisplayName("Should not duplicate participants when adding existing agent")
    void shouldNotDuplicateParticipants() {
        MsgHub hub = MsgHub.builder().participants(alice, bob).build();

        hub.enter().block(TIMEOUT);

        int initialSize = hub.getParticipants().size();

        // Try to add alice again
        hub.add(alice).block(TIMEOUT);

        assertEquals(
                initialSize,
                hub.getParticipants().size(),
                "Should not duplicate existing participant");

        hub.close();
    }

    /**
     * Create a mock agent with observe() method properly configured.
     *
     * @param name Agent name
     * @return Mock agent
     */
    private AgentBase createMockAgent(String name) {
        AgentBase agent = mock(AgentBase.class);
        when(agent.getName()).thenReturn(name);
        when(agent.getAgentId()).thenReturn("id-" + name);
        when(agent.observe(any(Msg.class))).thenReturn(Mono.empty());
        return agent;
    }
}
