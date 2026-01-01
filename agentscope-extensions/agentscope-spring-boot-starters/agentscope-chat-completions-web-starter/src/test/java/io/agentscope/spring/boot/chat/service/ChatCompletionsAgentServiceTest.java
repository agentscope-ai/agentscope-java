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
package io.agentscope.spring.boot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.session.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link ChatCompletionsAgentService}.
 */
@DisplayName("ChatCompletionsAgentService Tests")
class ChatCompletionsAgentServiceTest {

    private ObjectProvider<ReActAgent> agentProvider;
    private Session session;
    private ReActAgent mockAgent;
    private ChatCompletionsAgentService agentService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentProvider = mock(ObjectProvider.class);
        session = mock(Session.class);
        mockAgent = mock(ReActAgent.class);

        when(agentProvider.getObject()).thenReturn(mockAgent);

        agentService = new ChatCompletionsAgentService(agentProvider, session);
    }

    @Nested
    @DisplayName("getAgent Tests")
    class GetAgentTests {

        @Test
        @DisplayName("Should create agent and load state if session exists")
        void shouldCreateAgentAndLoadStateIfSessionExists() {
            String sessionId = "test-session";
            when(mockAgent.loadIfExists(any(Session.class), anyString())).thenReturn(true);

            ReActAgent agent = agentService.getAgent(sessionId);

            assertThat(agent).isSameAs(mockAgent);
            verify(agentProvider).getObject();
            verify(mockAgent).loadIfExists(session, sessionId);
        }

        @Test
        @DisplayName("Should create agent without loading state if session doesn't exist")
        void shouldCreateAgentWithoutLoadingStateIfSessionDoesNotExist() {
            String sessionId = "new-session";
            when(mockAgent.loadIfExists(any(Session.class), anyString())).thenReturn(false);

            ReActAgent agent = agentService.getAgent(sessionId);

            assertThat(agent).isSameAs(mockAgent);
            verify(agentProvider).getObject();
            verify(mockAgent).loadIfExists(session, sessionId);
        }

        @Test
        @DisplayName("Should generate session ID if null")
        void shouldGenerateSessionIdIfNull() {
            when(mockAgent.loadIfExists(any(Session.class), anyString())).thenReturn(false);

            // First resolve the session ID (which will generate a UUID if null)
            String resolvedSessionId = agentService.resolveSessionId(null);
            assertThat(resolvedSessionId).isNotNull().isNotEmpty();

            // Then get the agent with the resolved session ID
            ReActAgent agent = agentService.getAgent(resolvedSessionId);

            assertThat(agent).isSameAs(mockAgent);
            verify(agentProvider).getObject();
            // loadIfExists should be called with the resolved session ID
            verify(mockAgent).loadIfExists(session, resolvedSessionId);
        }

        @Test
        @DisplayName("Should throw exception if agent provider returns null")
        void shouldThrowExceptionIfAgentProviderReturnsNull() {
            when(agentProvider.getObject()).thenReturn(null);

            assertThatThrownBy(() -> agentService.getAgent("test-session"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to create ReActAgent: supplier returned null");
        }
    }

    @Nested
    @DisplayName("saveAgentState Tests")
    class SaveAgentStateTests {

        @Test
        @DisplayName("Should save agent state to session")
        void shouldSaveAgentStateToSession() {
            String sessionId = "test-session";

            agentService.saveAgentState(sessionId, mockAgent);

            verify(mockAgent).saveTo(session, sessionId);
        }

        @Test
        @DisplayName("Should handle save errors gracefully")
        void shouldHandleSaveErrorsGracefully() {
            String sessionId = "test-session";
            doThrow(new RuntimeException("Save failed"))
                    .when(mockAgent)
                    .saveTo(any(Session.class), anyString());

            // Should not throw exception
            agentService.saveAgentState(sessionId, mockAgent);

            verify(mockAgent).saveTo(session, sessionId);
        }
    }

    @Nested
    @DisplayName("resolveSessionId Tests")
    class ResolveSessionIdTests {

        @Test
        @DisplayName("Should return same ID if not null or blank")
        void shouldReturnSameIdIfNotNullOrBlank() {
            String sessionId = "custom-session";

            String resolved = agentService.resolveSessionId(sessionId);

            assertThat(resolved).isEqualTo(sessionId);
        }

        @Test
        @DisplayName("Should generate UUID if null")
        void shouldGenerateUuidIfNull() {
            String resolved = agentService.resolveSessionId(null);

            assertThat(resolved).isNotNull().isNotEmpty();
            // Should be a valid UUID format
            assertThat(resolved)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("Should generate UUID if blank")
        void shouldGenerateUuidIfBlank() {
            String resolved = agentService.resolveSessionId("   ");

            assertThat(resolved).isNotNull().isNotEmpty();
            assertThat(resolved).doesNotContain(" ");
        }
    }

    @Nested
    @DisplayName("getSession Tests")
    class GetSessionTests {

        @Test
        @DisplayName("Should return the session")
        void shouldReturnTheSession() {
            assertThat(agentService.getSession()).isSameAs(session);
        }
    }
}
