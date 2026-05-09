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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

            manager.registerRequest(agent);
            assertEquals(1, manager.getActiveRequestCount());

            manager.unregisterRequest(agent);
            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("unregisterRequest is idempotent")
        void unregisterIdempotent() {
            TestableAgent agent = createTestAgent("agent-1");

            manager.registerRequest(agent);
            manager.unregisterRequest(agent);
            manager.unregisterRequest(agent);

            assertEquals(0, manager.getActiveRequestCount());
        }

        @Test
        @DisplayName("unregisterRequest with null agent is no-op")
        void unregisterNullAgent() {
            assertDoesNotThrow(() -> manager.unregisterRequest(null));
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
            manager.registerRequest(agent);

            manager.performGracefulShutdown();

            assertEquals(ShutdownState.SHUTTING_DOWN, manager.getState());

            assertFalse(manager.awaitTermination(Duration.ofMillis(100)));

            manager.unregisterRequest(agent);
            assertTrue(manager.awaitTermination(Duration.ofSeconds(3)));
            assertEquals(ShutdownState.TERMINATED, manager.getState());
        }

        @Test
        @DisplayName("interruptIfShuttingDown sets interrupt flag when SHUTTING_DOWN")
        void interruptIfShuttingDown() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);

            manager.performGracefulShutdown();
            manager.interruptIfShuttingDown(agent);

            assertTrue(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("interruptIfShuttingDown is no-op when RUNNING")
        void interruptIfNotShuttingDown() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);

            manager.interruptIfShuttingDown(agent);

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("interruptIfShuttingDown is no-op for unregistered agent")
        void interruptUnregisteredAgent() {
            TestableAgent agent = createTestAgent("agent-1");

            manager.performGracefulShutdown();
            manager.interruptIfShuttingDown(agent);

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("interruptIfShuttingDown with null agent is no-op")
        void interruptNullAgent() {
            manager.performGracefulShutdown();
            assertDoesNotThrow(() -> manager.interruptIfShuttingDown(null));
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
        @DisplayName("bindSession and checkAndClearShutdownInterrupted work together")
        void sessionBindingAndInterruptedCheck() {
            Session session = new InMemorySession();
            SimpleSessionKey sessionKey = SimpleSessionKey.of("test-session");
            TestableAgent agent = createTestAgent("agent-1");

            manager.bindSession(agent, session, sessionKey);

            assertFalse(manager.checkAndClearShutdownInterrupted(agent));

            manager.registerRequest(agent);
            manager.saveOnInterruptObserved(agent);

            assertTrue(manager.checkAndClearShutdownInterrupted(agent));
            assertFalse(manager.checkAndClearShutdownInterrupted(agent));
        }

        @Test
        @DisplayName("bindSession with null arguments is no-op")
        void bindSessionNullArgs() {
            TestableAgent agent = createTestAgent("agent-1");
            Session session = new InMemorySession();

            assertDoesNotThrow(() -> manager.bindSession(null, session, SimpleSessionKey.of("s")));
            assertDoesNotThrow(() -> manager.bindSession(agent, null, SimpleSessionKey.of("s")));
            assertDoesNotThrow(() -> manager.bindSession(agent, session, null));
        }

        @Test
        @DisplayName("checkAndClearShutdownInterrupted with null agent returns false")
        void checkInterruptedNullAgent() {
            assertFalse(manager.checkAndClearShutdownInterrupted(null));
        }

        @Test
        @DisplayName("checkAndClearShutdownInterrupted with no binding returns false")
        void checkInterruptedNoBinding() {
            TestableAgent agent = createTestAgent("agent-1");
            assertFalse(manager.checkAndClearShutdownInterrupted(agent));
        }

        @Test
        @DisplayName("saveOnInterruptObserved with no context is no-op")
        void saveOnInterruptNoContext() {
            TestableAgent agent = createTestAgent("agent-1");
            assertDoesNotThrow(() -> manager.saveOnInterruptObserved(agent));
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

            manager.registerRequest(agent1);
            manager.registerRequest(agent2);
            assertEquals(2, manager.getActiveRequestCount());

            manager.unregisterRequest(agent1);
            assertEquals(1, manager.getActiveRequestCount());

            manager.unregisterRequest(agent2);
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
            manager.registerRequest(agent1);
            manager.registerRequest(agent2);

            manager.performGracefulShutdown();

            Thread.sleep(1500);

            assertTrue(agent1.isInterruptFlagSet());
            assertTrue(agent2.isInterruptFlagSet());
        }
    }

    // ==================== GracefulShutdownHook ====================

    @Nested
    @DisplayName("GracefulShutdownHook")
    class HookTests {

        private GracefulShutdownHook hook;

        @BeforeEach
        void setUp() {
            hook = new GracefulShutdownHook(manager);
        }

        @Test
        @DisplayName("Priority is 0 (highest)")
        void priorityIsZero() {
            assertEquals(0, hook.priority());
        }

        @Test
        @DisplayName("PostReasoningEvent triggers interruptIfShuttingDown")
        void postReasoningCheckpoint() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            Msg reasoningMsg = buildMsg("test reasoning");
            PostReasoningEvent event =
                    new PostReasoningEvent(agent, "test-model", null, reasoningMsg);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertTrue(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("PostReasoningEvent with null message does not interrupt")
        void postReasoningNullMessage() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            PostReasoningEvent event = new PostReasoningEvent(agent, "test-model", null, null);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("PostActingEvent triggers interruptIfShuttingDown")
        void postActingCheckpoint() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            Toolkit toolkit = new Toolkit();
            ToolUseBlock toolUse =
                    ToolUseBlock.builder().id("call-1").name("test_tool").input(Map.of()).build();
            ToolResultBlock toolResult = ToolResultBlock.text("result");
            PostActingEvent event = new PostActingEvent(agent, toolkit, toolUse, toolResult);
            event.setToolResultMsg(buildMsg("tool result msg"));

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertTrue(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("PostActingEvent with null toolResultMsg does not interrupt")
        void postActingNullToolResultMsg() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            Toolkit toolkit = new Toolkit();
            ToolUseBlock toolUse =
                    ToolUseBlock.builder().id("call-1").name("test_tool").input(Map.of()).build();
            ToolResultBlock toolResult = ToolResultBlock.text("result");
            PostActingEvent event = new PostActingEvent(agent, toolkit, toolUse, toolResult);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("PostSummaryEvent triggers interruptIfShuttingDown")
        void postSummaryCheckpoint() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            Msg summaryMsg = buildMsg("summary");
            PostSummaryEvent event = new PostSummaryEvent(agent, "test-model", null, summaryMsg);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertTrue(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("PostSummaryEvent with null message does not interrupt")
        void postSummaryNullMessage() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);
            manager.performGracefulShutdown();

            PostSummaryEvent event = new PostSummaryEvent(agent, "test-model", null, null);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("Checkpoints do not interrupt when RUNNING")
        void checkpointDoesNotInterruptWhenRunning() {
            TestableAgent agent = createTestAgent("agent-1");
            manager.registerRequest(agent);

            Msg reasoningMsg = buildMsg("test");
            PostReasoningEvent event =
                    new PostReasoningEvent(agent, "test-model", null, reasoningMsg);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertFalse(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("PreCallEvent deduplicates input when shutdown-interrupted")
        void preCallDeduplication() {
            Session session = new InMemorySession();
            SimpleSessionKey sessionKey = SimpleSessionKey.of("test-session");
            TestableAgent agent = createTestAgent("agent-1");

            manager.bindSession(agent, session, sessionKey);
            manager.registerRequest(agent);
            manager.saveOnInterruptObserved(agent);

            List<Msg> inputMsgs = List.of(buildMsg("user input"));
            PreCallEvent event = new PreCallEvent(agent, inputMsgs);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertTrue(event.getInputMessages().isEmpty());
        }

        @Test
        @DisplayName("PreCallEvent does not deduplicate when no interrupted flag")
        void preCallNoDeduplication() {
            TestableAgent agent = createTestAgent("agent-1");

            List<Msg> inputMsgs = List.of(buildMsg("user input"));
            PreCallEvent event = new PreCallEvent(agent, inputMsgs);

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

            assertEquals(1, event.getInputMessages().size());
        }

        @Test
        @DisplayName("Unrelated events pass through unchanged")
        void unrelatedEventsPassThrough() {
            TestableAgent agent = createTestAgent("agent-1");
            io.agentscope.core.hook.ErrorEvent event =
                    new io.agentscope.core.hook.ErrorEvent(agent, new RuntimeException("test"));

            StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
        }
    }

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

    // ==================== ShutdownSessionBinding ====================

    @Nested
    @DisplayName("ShutdownSessionBinding")
    class SessionBindingTests {

        @Test
        @DisplayName("Rejects null session")
        void rejectsNullSession() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ShutdownSessionBinding(null, SimpleSessionKey.of("s")));
        }

        @Test
        @DisplayName("Rejects null sessionKey")
        void rejectsNullSessionKey() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ShutdownSessionBinding(new InMemorySession(), null));
        }

        @Test
        @DisplayName("Valid construction")
        void validConstruction() {
            Session session = new InMemorySession();
            SimpleSessionKey key = SimpleSessionKey.of("test");
            ShutdownSessionBinding binding = new ShutdownSessionBinding(session, key);

            assertSame(session, binding.session());
            assertSame(key, binding.sessionKey());
        }
    }

    // ==================== ShutdownInterruptedState ====================

    @Nested
    @DisplayName("ShutdownInterruptedState")
    class InterruptedStateTests {

        @Test
        @DisplayName("Records interrupted flag")
        void recordsInterruptedFlag() {
            ShutdownInterruptedState state = new ShutdownInterruptedState(true);
            assertTrue(state.interrupted());

            ShutdownInterruptedState notInterrupted = new ShutdownInterruptedState(false);
            assertFalse(notInterrupted.interrupted());
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
            ActiveRequestContext ctx = new ActiveRequestContext("req-1", agent, null, null);

            assertTrue(ctx.interruptForShutdown());
            assertFalse(ctx.interruptForShutdown());

            assertTrue(agent.isInterruptFlagSet());
        }

        @Test
        @DisplayName("hasSessionBinding returns true when both session and key present")
        void hasSessionBindingTrue() {
            TestableAgent agent = createTestAgent("ctx-2");
            Session session = new InMemorySession();
            SimpleSessionKey key = SimpleSessionKey.of("test");
            ActiveRequestContext ctx = new ActiveRequestContext("req-2", agent, session, key);

            assertTrue(ctx.hasSessionBinding());
        }

        @Test
        @DisplayName("hasSessionBinding returns false when session is null")
        void hasSessionBindingNullSession() {
            TestableAgent agent = createTestAgent("ctx-3");
            ActiveRequestContext ctx =
                    new ActiveRequestContext("req-3", agent, null, SimpleSessionKey.of("t"));

            assertFalse(ctx.hasSessionBinding());
        }

        @Test
        @DisplayName("hasSessionBinding returns false when sessionKey is null")
        void hasSessionBindingNullKey() {
            TestableAgent agent = createTestAgent("ctx-4");
            ActiveRequestContext ctx =
                    new ActiveRequestContext("req-4", agent, new InMemorySession(), null);

            assertFalse(ctx.hasSessionBinding());
        }

        @Test
        @DisplayName("saveToSession is no-op when no session binding")
        void saveToSessionNoBinding() {
            TestableAgent agent = createTestAgent("ctx-5");
            ActiveRequestContext ctx = new ActiveRequestContext("req-5", agent, null, null);

            assertDoesNotThrow(ctx::saveToSession);
        }

        @Test
        @DisplayName("saveToSession persists agent state and interrupted flag")
        void saveToSessionPersists() {
            TestableAgent agent = createTestAgent("ctx-6");
            Session session = new InMemorySession();
            SimpleSessionKey key = SimpleSessionKey.of("persist-test");
            ActiveRequestContext ctx = new ActiveRequestContext("req-6", agent, session, key);

            ctx.saveToSession();

            var flag =
                    session.get(
                            key,
                            ActiveRequestContext.SHUTDOWN_INTERRUPTED_KEY,
                            ShutdownInterruptedState.class);
            assertTrue(flag.isPresent());
            assertTrue(flag.get().interrupted());
        }

        @Test
        @DisplayName("getRequestId returns the id")
        void getRequestId() {
            TestableAgent agent = createTestAgent("ctx-7");
            ActiveRequestContext ctx = new ActiveRequestContext("my-request-id", agent, null, null);

            assertEquals("my-request-id", ctx.getRequestId());
        }
    }

    // ==================== Helpers ====================

    static class TestableAgent extends AgentBase {

        private final boolean shouldFail;
        private final boolean shouldBeSlow;

        TestableAgent(String name, boolean shouldFail, boolean shouldBeSlow) {
            super(name);
            this.shouldFail = shouldFail;
            this.shouldBeSlow = shouldBeSlow;
        }

        public boolean isInterruptFlagSet() {
            return getInterruptFlag().get();
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
        return new TestableAgent(name, false, false);
    }

    private static TestableAgent createFailingAgent(String name) {
        return new TestableAgent(name, true, false);
    }

    private static TestableAgent createSlowAgent(String name) {
        return new TestableAgent(name, false, true);
    }

    private static Msg buildMsg(String text) {
        return Msg.builder()
                .name("test")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
