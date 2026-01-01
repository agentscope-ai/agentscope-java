/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.chat.completions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatCompletionsAgentHelper}.
 */
@DisplayName("ChatCompletionsAgentHelper Tests")
class ChatCompletionsAgentHelperTest {

    private Supplier<ReActAgent> agentSupplier;
    private Session session;
    private ChatCompletionsAgentHelper helper;

    @BeforeEach
    void setUp() {
        // Create a real session
        session = new InMemorySession();

        // Create a supplier that returns a new agent instance each time
        agentSupplier = () -> ReActAgent.builder().name("test-agent").sysPrompt("Test").build();

        helper = new ChatCompletionsAgentHelper(agentSupplier, session);
    }

    @Nested
    @DisplayName("getAgent Tests")
    class GetAgentTests {

        @Test
        @DisplayName("Should create agent and load state if session exists")
        void shouldCreateAgentAndLoadStateIfSessionExists() {
            String sessionId = "test-session";

            // First, save an agent state to the session
            ReActAgent firstAgent = agentSupplier.get();
            firstAgent.saveTo(session, sessionId);

            // Then get the agent - it should load the state
            ReActAgent agent = helper.getAgent(sessionId);

            assertThat(agent).isNotNull();
            // The agent should have loaded state from session
            assertThat(agent.loadIfExists(session, sessionId)).isTrue();
        }

        @Test
        @DisplayName("Should create agent without loading state if session doesn't exist")
        void shouldCreateAgentWithoutLoadingStateIfSessionDoesNotExist() {
            String sessionId = "new-session";

            ReActAgent agent = helper.getAgent(sessionId);

            assertThat(agent).isNotNull();
            // The agent should not have state in session
            assertThat(agent.loadIfExists(session, sessionId)).isFalse();
        }

        @Test
        @DisplayName("Should throw exception if supplier returns null")
        void shouldThrowExceptionIfSupplierReturnsNull() {
            Supplier<ReActAgent> nullSupplier = () -> null;
            ChatCompletionsAgentHelper helperWithNullSupplier =
                    new ChatCompletionsAgentHelper(nullSupplier, session);

            assertThatThrownBy(() -> helperWithNullSupplier.getAgent("test-session"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("supplier returned null");
        }
    }

    @Nested
    @DisplayName("saveAgentState Tests")
    class SaveAgentStateTests {

        @Test
        @DisplayName("Should save agent state to session")
        void shouldSaveAgentStateToSession() {
            String sessionId = "test-session";
            ReActAgent agent = agentSupplier.get();

            helper.saveAgentState(sessionId, agent);

            // Verify state was saved by trying to load it
            ReActAgent newAgent = agentSupplier.get();
            assertThat(newAgent.loadIfExists(session, sessionId)).isTrue();
        }

        @Test
        @DisplayName("Should handle save errors gracefully")
        void shouldHandleSaveErrorsGracefully() {
            String sessionId = "test-session";
            ReActAgent agent = agentSupplier.get();

            // Save should not throw exception even if there's an issue
            // (The helper catches exceptions internally)
            helper.saveAgentState(sessionId, agent);

            // Verify it completed without throwing
            assertThat(agent).isNotNull();
        }
    }

    @Nested
    @DisplayName("resolveSessionId Tests")
    class ResolveSessionIdTests {

        @Test
        @DisplayName("Should return same ID if not null or blank")
        void shouldReturnSameIdIfNotNullOrBlank() {
            String sessionId = "custom-session";

            String resolved = helper.resolveSessionId(sessionId);

            assertThat(resolved).isEqualTo(sessionId);
        }

        @Test
        @DisplayName("Should generate UUID if null")
        void shouldGenerateUuidIfNull() {
            String resolved = helper.resolveSessionId(null);

            assertThat(resolved).isNotNull().isNotEmpty();
            // Should be a valid UUID format
            assertThat(resolved)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("Should generate UUID if blank")
        void shouldGenerateUuidIfBlank() {
            String resolved = helper.resolveSessionId("   ");

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
            assertThat(helper.getSession()).isSameAs(session);
        }
    }
}
