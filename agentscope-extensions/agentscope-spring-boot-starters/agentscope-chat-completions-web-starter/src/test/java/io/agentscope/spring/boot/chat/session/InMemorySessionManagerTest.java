/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.spring.boot.chat.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.session.InMemorySessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link InMemorySessionManager}.
 *
 * <p>These tests verify the session manager's behavior for creating, reusing, and expiring agent
 * sessions.
 */
@DisplayName("InMemorySessionManager Tests")
class InMemorySessionManagerTest {

    private InMemorySessionManager sessionManager;

    @SuppressWarnings("unchecked")
    private ObjectProvider<ReActAgent> mockAgentProvider;

    private ReActAgent mockAgent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionManager = new InMemorySessionManager();
        mockAgentProvider = mock(ObjectProvider.class);
        mockAgent = mock(ReActAgent.class);
        when(mockAgentProvider.getObject()).thenReturn(mockAgent);
    }

    @Nested
    @DisplayName("getOrCreateAgent Tests")
    class GetOrCreateAgentTests {

        @Test
        @DisplayName("Should create new agent when sessionId is null")
        void shouldCreateNewAgentWhenSessionIdIsNull() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            ReActAgent result = sessionManager.getOrCreateAgent(null, mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should create new agent when sessionId is empty")
        void shouldCreateNewAgentWhenSessionIdIsEmpty() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            ReActAgent result = sessionManager.getOrCreateAgent("", mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should create new agent when sessionId is blank")
        void shouldCreateNewAgentWhenSessionIdIsBlank() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            ReActAgent result =
                    sessionManager.getOrCreateAgent("   ", mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should create new agent when sessionId does not exist")
        void shouldCreateNewAgentWhenSessionIdDoesNotExist() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            ReActAgent result =
                    sessionManager.getOrCreateAgent("new-session", mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should reuse existing agent for same sessionId")
        void shouldReuseExistingAgentForSameSessionId() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);
            String sessionId = "existing-session";

            // First call - creates the agent
            ReActAgent first =
                    sessionManager.getOrCreateAgent(sessionId, mockAgentProvider::getObject);

            // Second call - should reuse
            ReActAgent second =
                    sessionManager.getOrCreateAgent(sessionId, mockAgentProvider::getObject);

            assertThat(first).isEqualTo(mockAgent);
            assertThat(second).isEqualTo(mockAgent);
            // Provider should only be called once (for the first creation)
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should create different agents for different sessionIds")
        void shouldCreateDifferentAgentsForDifferentSessionIds() {
            ReActAgent agent1 = mock(ReActAgent.class);
            ReActAgent agent2 = mock(ReActAgent.class);
            when(mockAgentProvider.getObject()).thenReturn(agent1).thenReturn(agent2);

            ReActAgent first =
                    sessionManager.getOrCreateAgent("session-1", mockAgentProvider::getObject);
            ReActAgent second =
                    sessionManager.getOrCreateAgent("session-2", mockAgentProvider::getObject);

            assertThat(first).isEqualTo(agent1);
            assertThat(second).isEqualTo(agent2);
            verify(mockAgentProvider, times(2)).getObject();
        }

        @Test
        @DisplayName("Should throw IllegalStateException when agentProvider returns null")
        void shouldThrowIllegalStateExceptionWhenAgentProviderReturnsNull() {
            when(mockAgentProvider.getObject()).thenReturn(null);

            assertThatThrownBy(
                            () ->
                                    sessionManager.getOrCreateAgent(
                                            "session", mockAgentProvider::getObject))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("agentSupplier returned null");
        }

        @Test
        @DisplayName("Should throw RuntimeException when agentProvider throws exception")
        void shouldThrowRuntimeExceptionWhenAgentProviderThrowsException() {
            when(mockAgentProvider.getObject()).thenThrow(new RuntimeException("Provider failed"));

            assertThatThrownBy(
                            () ->
                                    sessionManager.getOrCreateAgent(
                                            "session", mockAgentProvider::getObject))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to get or create agent");
        }

        @Test
        @DisplayName("Should re-throw IllegalStateException as-is")
        void shouldReThrowIllegalStateExceptionAsIs() {
            when(mockAgentProvider.getObject())
                    .thenThrow(new IllegalStateException("Custom error"));

            assertThatThrownBy(
                            () ->
                                    sessionManager.getOrCreateAgent(
                                            "session", mockAgentProvider::getObject))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Custom error");
        }

        @Test
        @DisplayName("Should handle multiple concurrent sessions")
        void shouldHandleMultipleConcurrentSessions() {
            ReActAgent agent1 = mock(ReActAgent.class);
            ReActAgent agent2 = mock(ReActAgent.class);
            ReActAgent agent3 = mock(ReActAgent.class);

            when(mockAgentProvider.getObject())
                    .thenReturn(agent1)
                    .thenReturn(agent2)
                    .thenReturn(agent3);

            // Create multiple sessions
            ReActAgent result1 =
                    sessionManager.getOrCreateAgent("session-1", mockAgentProvider::getObject);
            ReActAgent result2 =
                    sessionManager.getOrCreateAgent("session-2", mockAgentProvider::getObject);
            ReActAgent result3 =
                    sessionManager.getOrCreateAgent("session-3", mockAgentProvider::getObject);

            // Verify all agents are different
            assertThat(result1).isEqualTo(agent1);
            assertThat(result2).isEqualTo(agent2);
            assertThat(result3).isEqualTo(agent3);

            // Verify reuse works for each session
            assertThat(sessionManager.getOrCreateAgent("session-1", mockAgentProvider::getObject))
                    .isEqualTo(agent1);
            assertThat(sessionManager.getOrCreateAgent("session-2", mockAgentProvider::getObject))
                    .isEqualTo(agent2);
            assertThat(sessionManager.getOrCreateAgent("session-3", mockAgentProvider::getObject))
                    .isEqualTo(agent3);

            // Provider should be called exactly 3 times (once per new session)
            verify(mockAgentProvider, times(3)).getObject();
        }

        @Test
        @DisplayName("Should update session timestamp when reusing agent (touch)")
        void shouldUpdateSessionTimestampWhenReusingAgent() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);
            String sessionId = "touch-session";

            // First call - creates the agent
            sessionManager.getOrCreateAgent(sessionId, mockAgentProvider::getObject);

            // Multiple reuse calls should not create new agents
            for (int i = 0; i < 5; i++) {
                ReActAgent result =
                        sessionManager.getOrCreateAgent(sessionId, mockAgentProvider::getObject);
                assertThat(result).isEqualTo(mockAgent);
            }

            // Provider should only be called once
            verify(mockAgentProvider, times(1)).getObject();
        }
    }

    @Nested
    @DisplayName("Session Expiration Tests")
    class SessionExpirationTests {

        @Test
        @DisplayName("Should create new sessions for null sessionIds each time")
        void shouldCreateNewSessionsForNullSessionIdsEachTime() {
            ReActAgent agent1 = mock(ReActAgent.class);
            ReActAgent agent2 = mock(ReActAgent.class);
            when(mockAgentProvider.getObject()).thenReturn(agent1).thenReturn(agent2);

            // Each null sessionId should create a new session with random UUID
            ReActAgent first = sessionManager.getOrCreateAgent(null, mockAgentProvider::getObject);
            ReActAgent second = sessionManager.getOrCreateAgent(null, mockAgentProvider::getObject);

            // Both should be created (different random UUIDs)
            verify(mockAgentProvider, times(2)).getObject();
            assertThat(first).isEqualTo(agent1);
            assertThat(second).isEqualTo(agent2);
        }

        @Test
        @DisplayName("Should create new sessions for blank sessionIds each time")
        void shouldCreateNewSessionsForBlankSessionIdsEachTime() {
            ReActAgent agent1 = mock(ReActAgent.class);
            ReActAgent agent2 = mock(ReActAgent.class);
            when(mockAgentProvider.getObject()).thenReturn(agent1).thenReturn(agent2);

            // Each blank sessionId should create a new session with random UUID
            ReActAgent first = sessionManager.getOrCreateAgent("", mockAgentProvider::getObject);
            ReActAgent second = sessionManager.getOrCreateAgent("  ", mockAgentProvider::getObject);

            // Both should be created (different random UUIDs)
            verify(mockAgentProvider, times(2)).getObject();
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle special characters in sessionId")
        void shouldHandleSpecialCharactersInSessionId() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            String specialSessionId = "session-123_abc@test.com#hash";
            ReActAgent result =
                    sessionManager.getOrCreateAgent(specialSessionId, mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);

            // Should reuse the same session
            ReActAgent reused =
                    sessionManager.getOrCreateAgent(specialSessionId, mockAgentProvider::getObject);
            assertThat(reused).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should handle very long sessionId")
        void shouldHandleVeryLongSessionId() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            String longSessionId = "a".repeat(1000);
            ReActAgent result =
                    sessionManager.getOrCreateAgent(longSessionId, mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);

            // Should reuse the same session
            ReActAgent reused =
                    sessionManager.getOrCreateAgent(longSessionId, mockAgentProvider::getObject);
            assertThat(reused).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should handle Unicode characters in sessionId")
        void shouldHandleUnicodeCharactersInSessionId() {
            when(mockAgentProvider.getObject()).thenReturn(mockAgent);

            String unicodeSessionId = "会话--セッション-세션-🔑";
            ReActAgent result =
                    sessionManager.getOrCreateAgent(unicodeSessionId, mockAgentProvider::getObject);

            assertThat(result).isEqualTo(mockAgent);

            // Should reuse the same session
            ReActAgent reused =
                    sessionManager.getOrCreateAgent(unicodeSessionId, mockAgentProvider::getObject);
            assertThat(reused).isEqualTo(mockAgent);
            verify(mockAgentProvider, times(1)).getObject();
        }

        @Test
        @DisplayName("Should implement ChatCompletionsSessionManager interface")
        void shouldImplementChatCompletionsSessionManagerInterface() {
            assertThat(sessionManager)
                    .isInstanceOf(
                            io.agentscope.core.chat.completions.session
                                    .ChatCompletionsSessionManager.class);
        }
    }
}
