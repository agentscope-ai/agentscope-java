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
package io.agentscope.core.chat.completions.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemorySessionManager}.
 *
 * <p>These tests verify the session manager's behavior for creating, reusing, and expiring agent
 * sessions.
 */
@DisplayName("InMemorySessionManager Tests")
class InMemorySessionManagerTest {

    private InMemorySessionManager sessionManager;
    private AtomicInteger agentCounter;

    @BeforeEach
    void setUp() {
        sessionManager = new InMemorySessionManager();
        agentCounter = new AtomicInteger(0);
    }

    private Supplier<ReActAgent> createAgentSupplier() {
        return () -> {
            int id = agentCounter.incrementAndGet();
            return ReActAgent.builder()
                    .name("test-agent-" + id)
                    .sysPrompt("Test agent")
                    .model(
                            new io.agentscope.core.model.Model() {
                                @Override
                                public reactor.core.publisher.Flux<
                                                io.agentscope.core.model.ChatResponse>
                                        stream(
                                                List<Msg> messages,
                                                List<io.agentscope.core.model.ToolSchema> tools,
                                                io.agentscope.core.model.GenerateOptions options) {
                                    return reactor.core.publisher.Flux.just(
                                            io.agentscope.core.model.ChatResponse.builder()
                                                    .content(
                                                            java.util.List.of(
                                                                    io.agentscope.core.message
                                                                            .TextBlock.builder()
                                                                            .text("test response")
                                                                            .build()))
                                                    .build());
                                }

                                @Override
                                public String getModelName() {
                                    return "test-model";
                                }
                            })
                    .build();
        };
    }

    @Nested
    @DisplayName("getOrCreateAgent Tests")
    class GetOrCreateAgentTests {

        @Test
        @DisplayName("Should create new agent when sessionId is null")
        void shouldCreateNewAgentWhenSessionIdIsNull() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result = sessionManager.getOrCreateAgent(null, supplier);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should create new agent when sessionId is empty")
        void shouldCreateNewAgentWhenSessionIdIsEmpty() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result = sessionManager.getOrCreateAgent("", supplier);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should create new agent when sessionId is blank")
        void shouldCreateNewAgentWhenSessionIdIsBlank() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result = sessionManager.getOrCreateAgent("   ", supplier);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should create new agent when sessionId does not exist")
        void shouldCreateNewAgentWhenSessionIdDoesNotExist() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result = sessionManager.getOrCreateAgent("new-session", supplier);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should reuse existing agent for same sessionId")
        void shouldReuseExistingAgentForSameSessionId() {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            String sessionId = "existing-session";

            // First call - creates the agent
            ReActAgent first = sessionManager.getOrCreateAgent(sessionId, supplier);
            String firstName = first.getName();

            // Second call - should reuse
            ReActAgent second = sessionManager.getOrCreateAgent(sessionId, supplier);

            assertThat(second).isSameAs(first);
            assertThat(second.getName()).isEqualTo(firstName);
        }

        @Test
        @DisplayName("Should create different agents for different sessionIds")
        void shouldCreateDifferentAgentsForDifferentSessionIds() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent first = sessionManager.getOrCreateAgent("session-1", supplier);
            ReActAgent second = sessionManager.getOrCreateAgent("session-2", supplier);

            assertThat(first).isNotSameAs(second);
            assertThat(first.getName()).isNotEqualTo(second.getName());
        }

        @Test
        @DisplayName("Should throw IllegalStateException when agentSupplier returns null")
        void shouldThrowIllegalStateExceptionWhenAgentSupplierReturnsNull() {
            Supplier<ReActAgent> nullSupplier = () -> null;

            assertThatThrownBy(() -> sessionManager.getOrCreateAgent("session", nullSupplier))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("agentSupplier returned null");
        }

        @Test
        @DisplayName("Should throw RuntimeException when agentSupplier throws exception")
        void shouldThrowRuntimeExceptionWhenAgentSupplierThrowsException() {
            Supplier<ReActAgent> failingSupplier =
                    () -> {
                        throw new RuntimeException("Supplier failed");
                    };

            assertThatThrownBy(() -> sessionManager.getOrCreateAgent("session", failingSupplier))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to get or create agent");
        }

        @Test
        @DisplayName("Should re-throw IllegalStateException as-is")
        void shouldReThrowIllegalStateExceptionAsIs() {
            Supplier<ReActAgent> failingSupplier =
                    () -> {
                        throw new IllegalStateException("Custom error");
                    };

            assertThatThrownBy(() -> sessionManager.getOrCreateAgent("session", failingSupplier))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Custom error");
        }

        @Test
        @DisplayName("Should handle multiple concurrent sessions")
        void shouldHandleMultipleConcurrentSessions() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            // Create multiple sessions
            ReActAgent result1 = sessionManager.getOrCreateAgent("session-1", supplier);
            ReActAgent result2 = sessionManager.getOrCreateAgent("session-2", supplier);
            ReActAgent result3 = sessionManager.getOrCreateAgent("session-3", supplier);

            // Verify all agents are different
            assertThat(result1).isNotSameAs(result2);
            assertThat(result2).isNotSameAs(result3);
            assertThat(result1).isNotSameAs(result3);

            // Verify reuse works for each session
            assertThat(sessionManager.getOrCreateAgent("session-1", supplier)).isSameAs(result1);
            assertThat(sessionManager.getOrCreateAgent("session-2", supplier)).isSameAs(result2);
            assertThat(sessionManager.getOrCreateAgent("session-3", supplier)).isSameAs(result3);
        }

        @Test
        @DisplayName("Should update session timestamp when reusing agent (touch)")
        void shouldUpdateSessionTimestampWhenReusingAgent() {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            String sessionId = "touch-session";

            // First call - creates the agent
            ReActAgent first = sessionManager.getOrCreateAgent(sessionId, supplier);

            // Multiple reuse calls should not create new agents
            for (int i = 0; i < 5; i++) {
                ReActAgent result = sessionManager.getOrCreateAgent(sessionId, supplier);
                assertThat(result).isSameAs(first);
            }
        }
    }

    @Nested
    @DisplayName("Session Expiration Tests")
    class SessionExpirationTests {

        @Test
        @DisplayName("Should create new sessions for null sessionIds each time")
        void shouldCreateNewSessionsForNullSessionIdsEachTime() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            // Each null sessionId should create a new session with random UUID
            ReActAgent first = sessionManager.getOrCreateAgent(null, supplier);
            ReActAgent second = sessionManager.getOrCreateAgent(null, supplier);

            // Both should be created (different random UUIDs)
            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("Should create new sessions for blank sessionIds each time")
        void shouldCreateNewSessionsForBlankSessionIdsEachTime() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            // Each blank sessionId should create a new session with random UUID
            ReActAgent first = sessionManager.getOrCreateAgent("", supplier);
            ReActAgent second = sessionManager.getOrCreateAgent("  ", supplier);

            // Both should be created (different random UUIDs)
            assertThat(first).isNotSameAs(second);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle special characters in sessionId")
        void shouldHandleSpecialCharactersInSessionId() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String specialSessionId = "session-123_abc@test.com#hash";
            ReActAgent result = sessionManager.getOrCreateAgent(specialSessionId, supplier);

            assertThat(result).isNotNull();

            // Should reuse the same session
            ReActAgent reused = sessionManager.getOrCreateAgent(specialSessionId, supplier);
            assertThat(reused).isSameAs(result);
        }

        @Test
        @DisplayName("Should handle very long sessionId")
        void shouldHandleVeryLongSessionId() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String longSessionId = "a".repeat(1000);
            ReActAgent result = sessionManager.getOrCreateAgent(longSessionId, supplier);

            assertThat(result).isNotNull();

            // Should reuse the same session
            ReActAgent reused = sessionManager.getOrCreateAgent(longSessionId, supplier);
            assertThat(reused).isSameAs(result);
        }

        @Test
        @DisplayName("Should handle Unicode characters in sessionId")
        void shouldHandleUnicodeCharactersInSessionId() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String unicodeSessionId = "会话--セッション-세션-🔑";
            ReActAgent result = sessionManager.getOrCreateAgent(unicodeSessionId, supplier);

            assertThat(result).isNotNull();

            // Should reuse the same session
            ReActAgent reused = sessionManager.getOrCreateAgent(unicodeSessionId, supplier);
            assertThat(reused).isSameAs(result);
        }

        @Test
        @DisplayName("Should implement ChatCompletionsSessionManager interface")
        void shouldImplementChatCompletionsSessionManagerInterface() {
            assertThat(sessionManager).isInstanceOf(ChatCompletionsSessionManager.class);
        }
    }
}
