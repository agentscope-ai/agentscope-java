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
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.state.StateModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        @DisplayName("Should propagate RuntimeException from agentSupplier")
        void shouldPropagateRuntimeExceptionFromAgentSupplier() {
            Supplier<ReActAgent> failingSupplier =
                    () -> {
                        throw new RuntimeException("Supplier failed");
                    };

            assertThatThrownBy(() -> sessionManager.getOrCreateAgent("session", failingSupplier))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Supplier failed");
        }

        @Test
        @DisplayName("Should propagate IllegalStateException from agentSupplier")
        void shouldPropagateIllegalStateExceptionFromAgentSupplier() {
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

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create manager with default constructor (no session, default TTL)")
        void shouldCreateManagerWithDefaultConstructor() {
            InMemorySessionManager manager = new InMemorySessionManager();

            assertThat(manager.isPersistenceEnabled()).isFalse();
            assertThat(manager.getSession()).isNull();
            assertThat(manager.getActiveAgentCount()).isZero();
        }

        @Test
        @DisplayName("Should create manager with Session")
        void shouldCreateManagerWithSession() {
            Session session = new InMemorySession();
            InMemorySessionManager manager = new InMemorySessionManager(session);

            assertThat(manager.isPersistenceEnabled()).isTrue();
            assertThat(manager.getSession()).isSameAs(session);
        }

        @Test
        @DisplayName("Should create manager with null Session (pure in-memory)")
        void shouldCreateManagerWithNullSession() {
            InMemorySessionManager manager = new InMemorySessionManager(null);

            assertThat(manager.isPersistenceEnabled()).isFalse();
            assertThat(manager.getSession()).isNull();
        }

        @Test
        @DisplayName("Should create manager with Session and custom TTL")
        void shouldCreateManagerWithSessionAndCustomTtl() {
            Session session = new InMemorySession();
            Duration customTtl = Duration.ofHours(1);
            InMemorySessionManager manager = new InMemorySessionManager(session, customTtl);

            assertThat(manager.isPersistenceEnabled()).isTrue();
            assertThat(manager.getSession()).isSameAs(session);
        }

        @Test
        @DisplayName("Should use default TTL when null TTL is provided")
        void shouldUseDefaultTtlWhenNullTtlProvided() {
            InMemorySessionManager manager = new InMemorySessionManager(null, null);

            // Just verify it's created without error and works
            assertThat(manager.isPersistenceEnabled()).isFalse();
            assertThat(manager.getActiveAgentCount()).isZero();
        }
    }

    @Nested
    @DisplayName("State Management Tests")
    class StateManagementTests {

        @Test
        @DisplayName("Should return correct active agent count")
        void shouldReturnCorrectActiveAgentCount() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            assertThat(sessionManager.getActiveAgentCount()).isZero();

            sessionManager.getOrCreateAgent("session-1", supplier);
            assertThat(sessionManager.getActiveAgentCount()).isEqualTo(1);

            sessionManager.getOrCreateAgent("session-2", supplier);
            assertThat(sessionManager.getActiveAgentCount()).isEqualTo(2);

            sessionManager.getOrCreateAgent("session-3", supplier);
            assertThat(sessionManager.getActiveAgentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should clear all cached agents")
        void shouldClearAllCachedAgents() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            sessionManager.getOrCreateAgent("session-1", supplier);
            sessionManager.getOrCreateAgent("session-2", supplier);
            assertThat(sessionManager.getActiveAgentCount()).isEqualTo(2);

            sessionManager.clear();

            assertThat(sessionManager.getActiveAgentCount()).isZero();
        }

        @Test
        @DisplayName("Should create new agent after clear for same sessionId")
        void shouldCreateNewAgentAfterClearForSameSessionId() {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            String sessionId = "test-session";

            ReActAgent first = sessionManager.getOrCreateAgent(sessionId, supplier);
            sessionManager.clear();
            ReActAgent second = sessionManager.getOrCreateAgent(sessionId, supplier);

            assertThat(second).isNotSameAs(first);
        }
    }

    @Nested
    @DisplayName("Persistence Tests")
    class PersistenceTests {

        @Test
        @DisplayName("Should report persistence enabled when Session is provided")
        void shouldReportPersistenceEnabledWhenSessionProvided() {
            Session session = new InMemorySession();
            InMemorySessionManager manager = new InMemorySessionManager(session);

            assertThat(manager.isPersistenceEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should report persistence disabled when no Session is provided")
        void shouldReportPersistenceDisabledWhenNoSessionProvided() {
            InMemorySessionManager manager = new InMemorySessionManager();

            assertThat(manager.isPersistenceEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should save all agent states when Session is configured")
        void shouldSaveAllAgentStatesWhenSessionConfigured() {
            Session session = new InMemorySession();
            InMemorySessionManager manager = new InMemorySessionManager(session);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            manager.getOrCreateAgent("session-1", supplier);
            manager.getOrCreateAgent("session-2", supplier);

            // This should not throw and should complete successfully
            manager.saveAllAgentStates();

            assertThat(manager.getActiveAgentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should skip save when no Session is configured")
        void shouldSkipSaveWhenNoSessionConfigured() {
            InMemorySessionManager manager = new InMemorySessionManager();
            Supplier<ReActAgent> supplier = createAgentSupplier();

            manager.getOrCreateAgent("session-1", supplier);

            // This should not throw and should complete successfully (no-op)
            manager.saveAllAgentStates();

            assertThat(manager.getActiveAgentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should get Session instance")
        void shouldGetSessionInstance() {
            Session session = new InMemorySession();
            InMemorySessionManager manager = new InMemorySessionManager(session);

            assertThat(manager.getSession()).isSameAs(session);
        }

        @Test
        @DisplayName("Should return null Session when pure in-memory mode")
        void shouldReturnNullSessionWhenPureInMemoryMode() {
            InMemorySessionManager manager = new InMemorySessionManager();

            assertThat(manager.getSession()).isNull();
        }
    }

    @Nested
    @DisplayName("TTL and Expiration Tests")
    class TtlAndExpirationTests {

        @Test
        @DisplayName("Should expire agent after TTL")
        void shouldExpireAgentAfterTtl() throws InterruptedException {
            // Create manager with very short TTL (100ms)
            Duration shortTtl = Duration.ofMillis(100);
            InMemorySessionManager manager = new InMemorySessionManager(null, shortTtl);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String sessionId = "expiring-session";
            ReActAgent first = manager.getOrCreateAgent(sessionId, supplier);

            // Wait for TTL to expire
            Thread.sleep(150);

            // Next access should create a new agent (old one expired)
            ReActAgent second = manager.getOrCreateAgent(sessionId, supplier);

            assertThat(second).isNotSameAs(first);
        }

        @Test
        @DisplayName("Should prune expired agents on getOrCreateAgent")
        void shouldPruneExpiredAgentsOnGetOrCreateAgent() throws InterruptedException {
            Duration shortTtl = Duration.ofMillis(100);
            InMemorySessionManager manager = new InMemorySessionManager(null, shortTtl);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            manager.getOrCreateAgent("session-1", supplier);
            manager.getOrCreateAgent("session-2", supplier);
            assertThat(manager.getActiveAgentCount()).isEqualTo(2);

            // Wait for expiration
            Thread.sleep(150);

            // Creating a new session should trigger pruning
            manager.getOrCreateAgent("session-3", supplier);

            // session-1 and session-2 should be pruned, only session-3 remains
            assertThat(manager.getActiveAgentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should save state before pruning when Session is configured")
        void shouldSaveStateBeforePruningWhenSessionConfigured() throws InterruptedException {
            Session session = new InMemorySession();
            Duration shortTtl = Duration.ofMillis(100);
            InMemorySessionManager manager = new InMemorySessionManager(session, shortTtl);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            manager.getOrCreateAgent("session-to-prune", supplier);
            assertThat(manager.getActiveAgentCount()).isEqualTo(1);

            // Wait for expiration
            Thread.sleep(150);

            // Trigger pruning
            manager.getOrCreateAgent("new-session", supplier);

            // Should have only 1 agent now
            assertThat(manager.getActiveAgentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should refresh TTL when accessing existing agent")
        void shouldRefreshTtlWhenAccessingExistingAgent() throws InterruptedException {
            Duration shortTtl = Duration.ofMillis(200);
            InMemorySessionManager manager = new InMemorySessionManager(null, shortTtl);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String sessionId = "refresh-session";
            ReActAgent first = manager.getOrCreateAgent(sessionId, supplier);

            // Access multiple times within TTL to keep refreshing
            Thread.sleep(100);
            assertThat(manager.getOrCreateAgent(sessionId, supplier)).isSameAs(first);
            Thread.sleep(100);
            assertThat(manager.getOrCreateAgent(sessionId, supplier)).isSameAs(first);
            Thread.sleep(100);
            // Agent should still be the same due to TTL refresh
            assertThat(manager.getOrCreateAgent(sessionId, supplier)).isSameAs(first);
        }
    }

    @Nested
    @DisplayName("Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should handle concurrent access to same session")
        void shouldHandleConcurrentAccessToSameSession() throws InterruptedException {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            String sessionId = "concurrent-session";
            int threadCount = 10;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ReActAgent[] results = new ReActAgent[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(
                        () -> {
                            try {
                                startLatch.await();
                                results[index] =
                                        sessionManager.getOrCreateAgent(sessionId, supplier);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();

            // All threads should get an agent (may or may not be the same due to race)
            for (ReActAgent result : results) {
                assertThat(result).isNotNull();
            }
        }

        @Test
        @DisplayName("Should handle concurrent access to different sessions")
        void shouldHandleConcurrentAccessToDifferentSessions() throws InterruptedException {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            int threadCount = 10;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ReActAgent[] results = new ReActAgent[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(
                        () -> {
                            try {
                                startLatch.await();
                                results[index] =
                                        sessionManager.getOrCreateAgent(
                                                "session-" + index, supplier);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();

            // All threads should get different agents
            for (ReActAgent result : results) {
                assertThat(result).isNotNull();
            }
            assertThat(sessionManager.getActiveAgentCount()).isEqualTo(threadCount);
        }
    }

    @Nested
    @DisplayName("Session Restore Tests")
    class SessionRestoreTests {

        @Test
        @DisplayName("Should not attempt restore when Session is null")
        void shouldNotAttemptRestoreWhenSessionIsNull() {
            InMemorySessionManager manager = new InMemorySessionManager();
            Supplier<ReActAgent> supplier = createAgentSupplier();

            // This should work without any session restore attempt
            ReActAgent agent = manager.getOrCreateAgent("test-session", supplier);

            assertThat(agent).isNotNull();
        }

        @Test
        @DisplayName("Should handle session that does not exist")
        void shouldHandleSessionThatDoesNotExist() {
            Session session = new InMemorySession();
            InMemorySessionManager manager = new InMemorySessionManager(session);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            // Session does not have this sessionId yet, should create new agent normally
            ReActAgent agent = manager.getOrCreateAgent("new-session-id", supplier);

            assertThat(agent).isNotNull();
        }

        @Test
        @DisplayName("Should work correctly when save then restore agent state")
        void shouldWorkCorrectlyWhenSaveThenRestoreAgentState() {
            Session session = new InMemorySession();
            InMemorySessionManager manager = new InMemorySessionManager(session);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String sessionId = "persist-session";

            // Create and use an agent
            ReActAgent first = manager.getOrCreateAgent(sessionId, supplier);
            assertThat(first).isNotNull();

            // Save the state
            manager.saveAllAgentStates();

            // Clear the cache
            manager.clear();
            assertThat(manager.getActiveAgentCount()).isZero();

            // Get agent again - should create a new one (but restore state)
            ReActAgent second = manager.getOrCreateAgent(sessionId, supplier);
            assertThat(second).isNotNull();
            assertThat(second).isNotSameAs(first); // New instance
        }

        @Test
        @DisplayName("Should handle exception during state restore gracefully")
        void shouldHandleExceptionDuringStateRestoreGracefully() {
            // Create a faulty session that throws on load operations
            Session faultySession = new FaultySession(true, false);
            InMemorySessionManager manager = new InMemorySessionManager(faultySession);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            String sessionId = "faulty-restore-session";

            // Despite the restore failure, agent should still be created
            ReActAgent agent = manager.getOrCreateAgent(sessionId, supplier);

            assertThat(agent).isNotNull();
        }

        @Test
        @DisplayName("Should handle exception during state save gracefully")
        void shouldHandleExceptionDuringStateSaveGracefully() {
            // Create a faulty session that throws on save operations
            Session faultySession = new FaultySession(false, true);
            InMemorySessionManager manager = new InMemorySessionManager(faultySession);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            manager.getOrCreateAgent("session-1", supplier);

            // Save should not throw even when Session fails
            manager.saveAllAgentStates();

            // Agent should still be in cache
            assertThat(manager.getActiveAgentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should save state on pruning and handle exception gracefully")
        void shouldSaveStateOnPruningAndHandleExceptionGracefully() throws InterruptedException {
            // Create a faulty session that throws on save operations
            Session faultySession = new FaultySession(false, true);
            Duration shortTtl = Duration.ofMillis(100);
            InMemorySessionManager manager = new InMemorySessionManager(faultySession, shortTtl);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            manager.getOrCreateAgent("expiring-session", supplier);
            assertThat(manager.getActiveAgentCount()).isEqualTo(1);

            // Wait for expiration
            Thread.sleep(150);

            // Trigger pruning - should not throw even when save fails
            manager.getOrCreateAgent("new-session", supplier);

            // Should only have the new session
            assertThat(manager.getActiveAgentCount()).isEqualTo(1);
        }
    }

    /**
     * A test Session implementation that can simulate failures during load/save operations.
     */
    static class FaultySession implements Session {

        private final boolean failOnLoad;
        private final boolean failOnSave;
        private final Set<String> existingSessions = new HashSet<>();

        FaultySession(boolean failOnLoad, boolean failOnSave) {
            this.failOnLoad = failOnLoad;
            this.failOnSave = failOnSave;
            // Pre-populate to make sessionExists return true
            existingSessions.add("faulty-restore-session");
        }

        @Override
        public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
            if (failOnSave) {
                throw new RuntimeException("Simulated save failure");
            }
            existingSessions.add(sessionId);
        }

        @Override
        public void loadSessionState(
                String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules) {
            if (failOnLoad) {
                throw new RuntimeException("Simulated load failure");
            }
        }

        @Override
        public boolean sessionExists(String sessionId) {
            return existingSessions.contains(sessionId);
        }

        @Override
        public boolean deleteSession(String sessionId) {
            return existingSessions.remove(sessionId);
        }

        @Override
        public List<String> listSessions() {
            return new ArrayList<>(existingSessions);
        }

        @Override
        public SessionInfo getSessionInfo(String sessionId) {
            if (!existingSessions.contains(sessionId)) {
                return null;
            }
            return new SessionInfo(sessionId, 0L, System.currentTimeMillis(), 1);
        }
    }
}
