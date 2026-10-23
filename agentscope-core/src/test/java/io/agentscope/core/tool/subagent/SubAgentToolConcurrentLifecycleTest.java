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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Timeout(20)
class SubAgentToolConcurrentLifecycleTest {

    private static final String SESSION_ID = "conversation";
    private static final String MEMORY = "Remember the code word: maple.";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationWaitsForInFlightStateSaveWithoutBlockingCaller(boolean streaming)
            throws Exception {
        ReActAgent agent = spy(ReActAgent.builder().name("concurrent_agent").build());
        agent.getAgentState(null, SESSION_ID)
                .contextMutable()
                .add(Msg.builder().textContent(MEMORY).build());
        CountDownLatch readingState = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch closed = closeSignal(agent);
        doAnswer(
                        invocation -> {
                            readingState.countDown();
                            assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
                            return invocation.callRealMethod();
                        })
                .when(agent)
                .getAgentState(any(RuntimeContext.class));
        Sinks.One<Msg> response = Sinks.one();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        SubAgentTool tool = tool(agent, response, store, streaming);
        Disposable subscription = tool.callAsync(param()).subscribe();
        var emitter = Executors.newSingleThreadExecutor();
        var cancellation = Executors.newSingleThreadExecutor();
        try {
            var completion = emitter.submit(() -> response.tryEmitValue(reply()));
            assertTrue(readingState.await(5, TimeUnit.SECONDS), "Save must reach the state read");

            cancellation.submit(subscription::dispose).get(1, TimeUnit.SECONDS);
            assertFalse(
                    closed.await(200, TimeUnit.MILLISECONDS),
                    "close must wait until the in-flight save has finished");
            releaseRead.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertEquals(
                    List.of(MEMORY),
                    store
                            .get(null, SESSION_ID, "agent_state", AgentState.class)
                            .orElseThrow()
                            .getContext()
                            .stream()
                            .map(Msg::getTextContent)
                            .toList());
        } finally {
            releaseRead.countDown();
            subscription.dispose();
            emitter.shutdown();
            cancellation.shutdown();
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(cancellation.awaitTermination(5, TimeUnit.SECONDS));
            agent.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationBeforeStateSaveDoesNotPersistClearedState(boolean streaming) throws Exception {
        ReActAgent agent = spy(ReActAgent.builder().name("cancelled_agent").build());
        agent.getAgentState(null, SESSION_ID)
                .contextMutable()
                .add(Msg.builder().textContent(MEMORY).build());
        CountDownLatch closed = closeSignal(agent);
        CountDownLatch mappingResponse = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        Msg reply = spy(reply());
        doAnswer(
                        invocation -> {
                            if (firstRead.compareAndSet(true, false)) {
                                mappingResponse.countDown();
                                assertTrue(releaseResponse.await(5, TimeUnit.SECONDS));
                            }
                            return invocation.callRealMethod();
                        })
                .when(reply)
                .getTextContent();
        Sinks.One<Msg> response = Sinks.one();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        SubAgentTool tool = tool(agent, response, store, streaming);
        Disposable subscription = tool.callAsync(param()).subscribe();
        var emitter = Executors.newSingleThreadExecutor();
        try {
            var completion = emitter.submit(() -> response.tryEmitValue(reply));
            assertTrue(mappingResponse.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            releaseResponse.countDown();
            completion.get(5, TimeUnit.SECONDS);

            assertTrue(
                    store.get(null, SESSION_ID, "agent_state", AgentState.class).isEmpty(),
                    "A late success callback must not save state after cancellation cleanup");
        } finally {
            releaseResponse.countDown();
            subscription.dispose();
            emitter.shutdown();
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            agent.close();
        }
    }

    @ParameterizedTest
    @MethodSource("terminationModes")
    void blockingCloseDoesNotBlockTerminalSignalThread(boolean streaming, String termination)
            throws Exception {
        Agent agent = mock(Agent.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(agent.getName()).thenReturn("blocking_agent");
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean closeOnNonBlockingThread = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            closeOnNonBlockingThread.set(Schedulers.isInNonBlockingThread());
                            closing.countDown();
                            try {
                                assertTrue(releaseClose.await(5, TimeUnit.SECONDS));
                            } finally {
                                closed.countDown();
                            }
                            return null;
                        })
                .when((AutoCloseable) agent)
                .close();
        Sinks.One<Msg> response = Sinks.one();
        SubAgentTool tool = tool(agent, response, new InMemoryAgentStateStore(), streaming);
        Disposable subscription = tool.callAsync(param()).subscribe();
        var terminalScheduler = Schedulers.newSingle("subagent-terminal");
        CountDownLatch terminalReturned = new CountDownLatch(1);
        try {
            terminalScheduler.schedule(
                    () -> {
                        try {
                            switch (termination) {
                                case "completion" -> response.tryEmitValue(reply());
                                case "error" ->
                                        response.tryEmitError(
                                                new IllegalStateException("model failed"));
                                case "cancellation" -> subscription.dispose();
                                default -> throw new AssertionError("Unknown termination");
                            }
                        } finally {
                            terminalReturned.countDown();
                        }
                    });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertTrue(
                    terminalReturned.await(1, TimeUnit.SECONDS),
                    "A blocking AutoCloseable must not hold the completion/error/timeout thread");
            assertFalse(closeOnNonBlockingThread.get());
        } finally {
            releaseClose.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            terminalScheduler.dispose();
        }
    }

    private static Stream<Arguments> terminationModes() {
        return Stream.of(false, true)
                .flatMap(
                        streaming ->
                                Stream.of("completion", "error", "cancellation")
                                        .map(termination -> Arguments.of(streaming, termination)));
    }

    private static CountDownLatch closeSignal(ReActAgent agent) {
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            closed.countDown();
                            return null;
                        })
                .when(agent)
                .close();
        return closed;
    }

    private static SubAgentTool tool(
            Agent agent,
            Sinks.One<Msg> response,
            InMemoryAgentStateStore store,
            boolean streaming) {
        if (agent instanceof ReActAgent ra) {
            doReturn(response.asMono()).when(ra).call(anyList(), any(RuntimeContext.class));
        } else {
            doReturn(response.asMono()).when(agent).call(anyList(), any(RuntimeContext.class));
        }
        doReturn(response.asMono().map(msg -> new Event(EventType.AGENT_RESULT, msg, true)).flux())
                .when(agent)
                .stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class));
        Agent sample = mock(Agent.class);
        when(sample.getName()).thenReturn("sample");
        var provided = List.of(sample, agent).iterator();
        return new SubAgentTool(
                provided::next,
                SubAgentConfig.builder()
                        .forwardEvents(streaming)
                        .stateStore(store)
                        .lifecyclePolicy(SubAgentConfig.LifecyclePolicy.PER_CALL)
                        .build());
    }

    private static ToolCallParam param() {
        return ToolCallParam.builder()
                .input(Map.of("session_id", SESSION_ID, "message", "hello"))
                .emitter(chunk -> {})
                .build();
    }

    private static Msg reply() {
        return Msg.builder().textContent("OK").build();
    }
}
