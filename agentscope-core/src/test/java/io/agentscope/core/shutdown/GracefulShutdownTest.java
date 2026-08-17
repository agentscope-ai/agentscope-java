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
package io.agentscope.core.shutdown;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("Graceful Shutdown Tests")
class GracefulShutdownTest {

    private GracefulShutdownManager manager;

    @BeforeEach
    void setUp() {
        manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        manager.resetForTesting();
    }

    // ==================== ShutdownState & Config ====================

    @Nested
    @DisplayName("ShutdownState and Config")
    class StateAndConfigTests {

        @Test
        @DisplayName("Initial state is RUNNING")
        void initialStateIsRunning() {
            assertEquals(ShutdownState.RUNNING, manager.getState());
        }

        @Test
        @DisplayName("Default config has null timeout and SAVE policy")
        void defaultConfig() {
            GracefulShutdownConfig cfg = manager.getConfig();
            assertNull(cfg.shutdownTimeout());
            assertEquals(PartialReasoningPolicy.SAVE, cfg.partialReasoningPolicy());
        }

        @Test
        @DisplayName("setConfig updates config")
        void setConfigUpdates() {
            GracefulShutdownConfig custom =
                    new GracefulShutdownConfig(
                            Duration.ofSeconds(5), PartialReasoningPolicy.DISCARD);
            manager.setConfig(custom);

            assertEquals(Duration.ofSeconds(5), manager.getConfig().shutdownTimeout());
            assertEquals(
                    PartialReasoningPolicy.DISCARD, manager.getConfig().partialReasoningPolicy());
        }

        @Test
        @DisplayName("Config rejects non-positive timeout")
        void configRejectsInvalidTimeout() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GracefulShutdownConfig(Duration.ZERO, PartialReasoningPolicy.SAVE));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new GracefulShutdownConfig(
                                    Duration.ofSeconds(-1), PartialReasoningPolicy.SAVE));
        }

        @Test
        @DisplayName("Config rejects null policy")
        void configRejectsNullPolicy() {
            assertThrows(NullPointerException.class, () -> new GracefulShutdownConfig(null, null));
        }
    }

    // ==================== GracefulShutdownManager ====================

    @Nested
    @DisplayName("GracefulShutdownManager")
    class ManagerTests {

        @Test
        @DisplayName("getInstance returns singleton")
        void singletonInstance() {
            assertSame(
                    GracefulShutdownManager.getInstance(), GracefulShutdownManager.getInstance());
        }

        @Test
        @DisplayName("performGracefulShutdown transitions RUNNING to SHUTTING_DOWN")
        void performShutdownTransition() {
            assertTrue(manager.isAcceptingRequests());

            boolean result = manager.performGracefulShutdown();

            assertTrue(result);
            assertEquals(ShutdownState.SHUTTING_DOWN, manager.getState());
            assertFalse(manager.isAcceptingRequests());
        }

        @Test
        @DisplayName("performGracefulShutdown is idempotent")
        void performShutdownIdempotent() {
            assertTrue(manager.performGracefulShutdown());
            assertTrue(manager.performGracefulShutdown());
            assertEquals(ShutdownState.SHUTTING_DOWN, manager.getState());
        }

        @Test
        @DisplayName("performGracefulShutdown returns false when already TERMINATED")
        void performShutdownWhenTerminated() {
            manager.performGracefulShutdown();
            manager.awaitTermination(Duration.ofSeconds(3));
            assertEquals(ShutdownState.TERMINATED, manager.getState());

            assertFalse(manager.performGracefulShutdown());
        }

        @Test
        @DisplayName("ensureAcceptingRequests throws when shutting down")
        void ensureAcceptingRequestsThrows() {
            manager.performGracefulShutdown();

            AgentShuttingDownException ex =
                    assertThrows(
                            AgentShuttingDownException.class, manager::ensureAcceptingRequests);
            assertEquals(AgentShuttingDownException.DEFAULT_MESSAGE, ex.getMessage());
        }

        @Test
        @DisplayName("ensureAcceptingRequests passes when RUNNING")
        void ensureAcceptingRequestsPasses() {
            assertDoesNotThrow(manager::ensureAcceptingRequests);
        }

        @Test
        @DisplayName("registerRequest and unregisterRequest track count")
        void registerUnregisterCount() {
            TestableAgent agent = createTestAgent("agent-1");

            assertEquals(0, manager.getActiveRequestCount());

            String requestId = manager.registerRequest(agent);
            assertEquals(1, manager.getActiveRequestCount());

            manager.unregisterRequest(requestId);
            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("unregisterRequest is idempotent")
        void unregisterIdempotent() {
            TestableAgent agent = createTestAgent("agent-1");

            String requestId = manager.registerRequest(agent);
            manager.unregisterRequest(requestId);
            manager.unregisterRequest(requestId);

            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("unregisterRequest with null/blank requestId is no-op")
        void unregisterNullRequestId() {
            assertDoesNotThrow(() -> manager.unregisterRequest(null));
            assertDoesNotThrow(() -> manager.unregisterRequest(""));
        }

        @Test
        @DisplayName("Concurrent calls on one agent are tracked as distinct requests")
        void concurrentRequestsSameAgentTrackedIndependently() {
            TestableAgent agent = createTestAgent("agent-shared");

            String r1 = manager.registerRequest(agent);
            String r2 = manager.registerRequest(agent);

            assertNotEquals(r1, r2);
            // Both concurrent calls on the SAME agent instance are tracked separately (keying by
            // agent id would collapse them into one).
            assertEquals(2, manager.getActiveRequestCount());

            manager.unregisterRequest(r1);
            // One call completing must not unregister the other in-flight call.
            assertEquals(1, manager.getActiveRequestCount());

            manager.unregisterRequest(r2);
            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("registerRequest with non-AgentBase returns empty string")
        void registerNonAgentBase() {
            io.agentscope.core.agent.Agent mockAgent = mock(io.agentscope.core.agent.Agent.class);
            String requestId = manager.registerRequest(mockAgent);
            assertEquals("", requestId);
            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("Shutdown transitions to TERMINATED when no active requests")
        void transitionToTerminated() {
            manager.performGracefulShutdown();

            boolean terminated = manager.awaitTermination(Duration.ofSeconds(3));

            assertTrue(terminated);
            assertEquals(ShutdownState.TERMINATED, manager.getState());
        }

        @Test
        @DisplayName("Shutdown waits for active requests before transitioning")
        void shutdownWaitsForActiveRequests() {
            TestableAgent agent = createTestAgent("agent-1");
            String requestId = manager.registerRequest(agent);

            manager.performGracefulShutdown();

            assertEquals(ShutdownState.SHUTTING_DOWN, manager.getState());

            assertFalse(manager.awaitTermination(Duration.ofMillis(100)));

            manager.unregisterRequest(requestId);
            assertTrue(manager.awaitTermination(Duration.ofSeconds(3)));
            assertEquals(ShutdownState.TERMINATED, manager.getState());
        }

        @Test
        @DisplayName(
                "interruptIfShuttingDown sets interrupt on bound AgentState when SHUTTING_DOWN")
        void interruptIfShuttingDown() {
            TestableAgent agent = createTestAgent("agent-1");
            String requestId = manager.registerRequest(agent);
            manager.bindRequestState(requestId, agent.getAgentState());

            manager.performGracefulShutdown();
            manager.interruptIfShuttingDown(requestId);

            assertTrue(agent.getAgentState().interruptControl().isInterrupted());
        }

        @Test
        @DisplayName("interruptIfShuttingDown is no-op when RUNNING")
        void interruptIfNotShuttingDown() {
            TestableAgent agent = createTestAgent("agent-1");
            String requestId = manager.registerRequest(agent);

            manager.interruptIfShuttingDown(requestId);

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("interruptIfShuttingDown is no-op for unregistered request")
        void interruptUnregisteredRequest() {
            TestableAgent agent = createTestAgent("agent-1");

            manager.performGracefulShutdown();
            manager.interruptIfShuttingDown("no-such-request");

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("interruptIfShuttingDown with null/blank requestId is no-op")
        void interruptNullRequestId() {
            manager.performGracefulShutdown();
            assertDoesNotThrow(() -> manager.interruptIfShuttingDown(null));
            assertDoesNotThrow(() -> manager.interruptIfShuttingDown(""));
        }

        @Test
        @DisplayName("getShutdownTimeoutSignal completes after timeout enforcement")
        void shutdownTimeoutSignal() {
            manager.setConfig(
                    new GracefulShutdownConfig(
                            Duration.ofMillis(100), PartialReasoningPolicy.SAVE));

            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);

            manager.performGracefulShutdown();

            StepVerifier.create(manager.getShutdownTimeoutSignal())
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("bindStateSaver and checkAndClearShutdownInterrupted work together")
        void stateSaverAndInterruptedCheck() {
            TestableAgent agent = createTestAgent("agent-1");
            AtomicReference<AgentState> savedState = new AtomicReference<>();

            manager.bindStateSaver(agent, savedState::set);

            assertFalse(manager.checkAndClearShutdownInterrupted(agent));

            String requestId = manager.registerRequest(agent);
            manager.saveOnInterruptObserved(requestId);

            assertTrue(agent.getAgentState().isShutdownInterrupted());
            assertNotNull(savedState.get());

            assertTrue(manager.checkAndClearShutdownInterrupted(agent));
            assertFalse(manager.checkAndClearShutdownInterrupted(agent));
        }

        @Test
        @DisplayName("bindStateSaver with null arguments is no-op")
        void bindStateSaverNullArgs() {
            TestableAgent agent = createTestAgent("agent-1");

            assertDoesNotThrow(() -> manager.bindStateSaver(null, s -> {}));
            assertDoesNotThrow(() -> manager.bindStateSaver(agent, null));
        }

        @Test
        @DisplayName("unbindStateSaver removes a previously bound saver")
        void unbindStateSaverRemovesBoundSaver() {
            TestableAgent agent = createTestAgent("agent-1");
            AtomicReference<AgentState> savedState = new AtomicReference<>();

            manager.bindStateSaver(agent, savedState::set);

            // Before unbind: the saver is reachable via registerRequest -> saveOnInterruptObserved
            String requestIdBefore = manager.registerRequest(agent);
            manager.saveOnInterruptObserved(requestIdBefore);
            assertNotNull(savedState.get(), "saver should be invoked before unbind");

            // After unbind: registerRequest finds no saver, so saveOnInterruptObserved is a no-op
            savedState.set(null);
            manager.unbindStateSaver(agent);
            String requestIdAfter = manager.registerRequest(agent);
            manager.saveOnInterruptObserved(requestIdAfter);
            assertNull(savedState.get(), "saver should not be invoked after unbind");
        }

        @Test
        @DisplayName("unbindStateSaver with null agent is no-op")
        void unbindStateSaverNullAgent() {
            assertDoesNotThrow(() -> manager.unbindStateSaver(null));
        }

        @Test
        @DisplayName("unbindStateSaver for an unregistered agent is no-op")
        void unbindStateSaverUnregisteredAgent() {
            TestableAgent agent = createTestAgent("agent-1");
            assertDoesNotThrow(() -> manager.unbindStateSaver(agent));
        }

        @Test
        @DisplayName("checkAndClearShutdownInterrupted with null agent returns false")
        void checkInterruptedNullAgent() {
            assertFalse(manager.checkAndClearShutdownInterrupted(null));
        }

        @Test
        @DisplayName("checkAndClearShutdownInterrupted with no state returns false")
        void checkInterruptedNoState() {
            TestableAgent agent = new TestableAgent("no-state", false, false, false);
            assertFalse(manager.checkAndClearShutdownInterrupted(agent));
        }

        @Test
        @DisplayName("saveOnInterruptObserved with no context is no-op")
        void saveOnInterruptNoContext() {
            createTestAgent("agent-1");
            assertDoesNotThrow(() -> manager.saveOnInterruptObserved("no-such-request"));
            assertDoesNotThrow(() -> manager.saveOnInterruptObserved(null));
        }

        @Test
        @DisplayName("resetForTesting restores initial state")
        void resetForTesting() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            manager.resetForTesting();

            assertEquals(ShutdownState.RUNNING, manager.getState());
            assertEquals(0, manager.getActiveRequestCount());
            assertTrue(manager.isAcceptingRequests());
        }

        @Test
        @DisplayName("Multiple agents can be registered concurrently")
        void multipleAgentsRegistered() {
            TestableAgent agent1 = createTestAgent("multi-1");
            TestableAgent agent2 = createTestAgent("multi-2");

            String r1 = manager.registerRequest(agent1);
            String r2 = manager.registerRequest(agent2);
            assertEquals(2, manager.getActiveRequestCount());

            manager.unregisterRequest(r1);
            assertEquals(1, manager.getActiveRequestCount());

            manager.unregisterRequest(r2);
            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("Timeout enforcement interrupts all active requests")
        void timeoutEnforcesInterruptAll() throws Exception {
            manager.setConfig(
                    new GracefulShutdownConfig(
                            Duration.ofMillis(200), PartialReasoningPolicy.SAVE));

            TestableAgent agent1 = createTestAgent("timeout-1");
            TestableAgent agent2 = createTestAgent("timeout-2");
            String req1 = manager.registerRequest(agent1);
            String req2 = manager.registerRequest(agent2);
            manager.bindRequestState(req1, agent1.getAgentState());
            manager.bindRequestState(req2, agent2.getAgentState());

            manager.performGracefulShutdown();

            Thread.sleep(1500);

            assertTrue(agent1.getAgentState().interruptControl().isInterrupted());
            assertTrue(agent2.getAgentState().interruptControl().isInterrupted());
        }
    }

    // The legacy `GracefulShutdownHook` has been replaced by `GracefulShutdownMiddleware`. Its
    // checkpoint and deduplication semantics are now exercised through end-to-end agent calls
    // (see e.g. tests under HarnessAgent E2E suites) rather than unit-tested at the
    // Hook.onEvent dispatch level.

    // ==================== AgentShuttingDownException ====================

    @Nested
    @DisplayName("AgentShuttingDownException")
    class ExceptionTests {

        @Test
        @DisplayName("Default constructor uses DEFAULT_MESSAGE")
        void defaultMessage() {
            AgentShuttingDownException ex = new AgentShuttingDownException();
            assertEquals(AgentShuttingDownException.DEFAULT_MESSAGE, ex.getMessage());
        }

        @Test
        @DisplayName("Custom message constructor")
        void customMessage() {
            AgentShuttingDownException ex = new AgentShuttingDownException("custom");
            assertEquals("custom", ex.getMessage());
        }

        @Test
        @DisplayName("Is a RuntimeException")
        void isRuntimeException() {
            assertInstanceOf(RuntimeException.class, new AgentShuttingDownException());
        }
    }

    // ==================== ShutdownSessionBinding (deprecated) ====================

    @Nested
    @DisplayName("ShutdownSessionBinding (deprecated)")
    @SuppressWarnings("deprecation")
    class SessionBindingTests {

        @Test
        @DisplayName("Rejects null session")
        void rejectsNullSession() {
            assertThrows(
                    NullPointerException.class, () -> new ShutdownSessionBinding(null, null, "s"));
        }

        @Test
        @DisplayName("Rejects null sessionId")
        void rejectsNullSessionId() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ShutdownSessionBinding(new InMemoryAgentStateStore(), null, null));
        }

        @Test
        @DisplayName("Valid construction")
        void validConstruction() {
            AgentStateStore stateStore = new InMemoryAgentStateStore();
            ShutdownSessionBinding binding =
                    new ShutdownSessionBinding(stateStore, "user1", "test");

            assertSame(stateStore, binding.stateStore());
            assertEquals("user1", binding.userId());
            assertEquals("test", binding.sessionId());
        }
    }

    // ==================== AgentBase call() lifecycle ====================

    @Nested
    @DisplayName("AgentBase call() lifecycle")
    class AgentBaseLifecycleTests {

        @Test
        @DisplayName("Successful call registers and unregisters request")
        void successfulCallLifecycle() {
            TestableAgent agent = createTestAgent("lifecycle-1");

            Msg input = buildMsg("hello");
            Msg response = agent.call(List.of(input)).block(Duration.ofSeconds(5));

            assertNotNull(response);
            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("call() rejects when shutdown in progress")
        void callRejectsDuringShutdown() {
            manager.performGracefulShutdown();

            TestableAgent agent = createTestAgent("rejected-1");
            Msg input = buildMsg("hello");

            StepVerifier.create(agent.call(List.of(input)))
                    .expectError(AgentShuttingDownException.class)
                    .verify(Duration.ofSeconds(5));

            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("Failed call unregisters request via cleanup")
        void failedCallLifecycle() {
            TestableAgent agent = createFailingAgent("fail-1");

            Msg input = buildMsg("hello");

            StepVerifier.create(agent.call(List.of(input)))
                    .expectError(RuntimeException.class)
                    .verify(Duration.ofSeconds(5));

            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("Cancelled call unregisters request via cleanup")
        void cancelledCallLifecycle() {
            TestableAgent agent = createSlowAgent("cancel-1");
            Msg input = buildMsg("hello");

            StepVerifier.create(agent.call(List.of(input)))
                    .thenAwait(Duration.ofMillis(50))
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));

            // Give cleanup time to execute
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }

            assertEquals(0, manager.getActiveRequestCount());
        }
    }

    // ==================== ActiveRequestContext ====================

    @Nested
    @DisplayName("ActiveRequestContext")
    class ActiveRequestContextTests {

        @Test
        @DisplayName("interruptForShutdown is idempotent (returns false on second call)")
        void interruptIdempotent() {
            TestableAgent agent = createTestAgent("ctx-1");
            ActiveRequestContext ctx = new ActiveRequestContext("req-1", agent, null);
            ctx.bindState(agent.getAgentState());

            assertTrue(ctx.interruptForShutdown());
            assertFalse(ctx.interruptForShutdown());

            assertTrue(agent.getAgentState().interruptControl().isInterrupted());
        }

        @Test
        @DisplayName("saveState sets shutdownInterrupted and invokes saver")
        void saveStatePersists() {
            TestableAgent agent = createTestAgent("ctx-2");
            AtomicReference<AgentState> savedState = new AtomicReference<>();
            ActiveRequestContext ctx = new ActiveRequestContext("req-2", agent, savedState::set);

            ctx.saveState();

            assertTrue(agent.getAgentState().isShutdownInterrupted());
            assertNotNull(savedState.get());
            assertTrue(savedState.get().isShutdownInterrupted());
        }

        @Test
        @DisplayName("saveState is no-op when no saver")
        void saveStateNoSaver() {
            TestableAgent agent = createTestAgent("ctx-3");
            ActiveRequestContext ctx = new ActiveRequestContext("req-3", agent, null);

            assertDoesNotThrow(ctx::saveState);
            assertFalse(agent.getAgentState().isShutdownInterrupted());
        }

        @Test
        @DisplayName("saveState is no-op when agent has no state")
        void saveStateNoAgentState() {
            TestableAgent agent = new TestableAgent("ctx-4", false, false, false);
            AtomicReference<AgentState> savedState = new AtomicReference<>();
            ActiveRequestContext ctx = new ActiveRequestContext("req-4", agent, savedState::set);

            assertDoesNotThrow(ctx::saveState);
            assertNull(savedState.get());
        }

        @Test
        @DisplayName("getRequestId returns the id")
        void getRequestId() {
            TestableAgent agent = createTestAgent("ctx-5");
            ActiveRequestContext ctx = new ActiveRequestContext("my-request-id", agent, null);

            assertEquals("my-request-id", ctx.getRequestId());
        }
    }

    // ==================== Helpers ====================

    static class TestableAgent extends AgentBase {

        private final boolean shouldFail;
        private final boolean shouldBeSlow;
        private final AgentState agentState;

        TestableAgent(String name, boolean shouldFail, boolean shouldBeSlow, boolean hasState) {
            super(name);
            this.shouldFail = shouldFail;
            this.shouldBeSlow = shouldBeSlow;
            this.agentState = hasState ? AgentState.builder().build() : null;
        }

        public boolean isInterruptFlagSet() {
            return getInterruptFlag().get();
        }

        @Override
        public AgentState getAgentState() {
            return agentState;
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            if (shouldFail) {
                return Mono.error(new RuntimeException("agent error"));
            }
            if (shouldBeSlow) {
                return Mono.delay(Duration.ofSeconds(10))
                        .map(
                                l ->
                                        Msg.builder()
                                                .name(getName())
                                                .role(MsgRole.ASSISTANT)
                                                .content(
                                                        TextBlock.builder()
                                                                .text("delayed response")
                                                                .build())
                                                .build());
            }
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("response").build())
                            .build());
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("interrupted").build())
                            .build());
        }
    }

    private static TestableAgent createTestAgent(String name) {
        return new TestableAgent(name, false, false, true);
    }

    private static TestableAgent createFailingAgent(String name) {
        return new TestableAgent(name, true, false, true);
    }

    private static TestableAgent createSlowAgent(String name) {
        return new TestableAgent(name, false, true, true);
    }

    private static Msg buildMsg(String text) {
        return Msg.builder()
                .name("test")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
