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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Timeout(15)
class SubAgentToolLifecycleTest {

    private final Map<ReActAgent, CountDownLatch> agents = new LinkedHashMap<>();
    private Map<?, ?> stateSavers;
    private Set<?> initialRegistrations;

    @BeforeEach
    void captureRegistrations() throws Exception {
        Field field = GracefulShutdownManager.class.getDeclaredField("stateSavers");
        field.setAccessible(true);
        stateSavers = (Map<?, ?>) field.get(GracefulShutdownManager.getInstance());
        initialRegistrations = new HashSet<>(stateSavers.keySet());
    }

    @AfterEach
    void closeTestAgents() {
        // Also release agents when an assertion fails against the unfixed implementation.
        agents.keySet().forEach(ReActAgent::close);
    }

    @Test
    void closesInitializationAgent() {
        SubAgentTool tool =
                new SubAgentTool(() -> registeredAgent(new MockModel("OK")), config(false));

        assertEquals("call_test_agent", tool.getName());
        assertEquals("Test sub-agent", tool.getDescription());
        verify(lastAgent()).close();
        assertNoLeakedRegistrations();
    }

    @Test
    void closesInitializationAgentWhenMetadataResolutionFails() {
        ReActAgent sample = registeredAgent(new MockModel("OK"));
        doThrow(new IllegalStateException("metadata failed")).when(sample).getDescription();

        assertThrows(
                IllegalStateException.class, () -> new SubAgentTool(() -> sample, config(false)));

        verify(sample).close();
        assertNoLeakedRegistrations();
    }

    @Test
    void closesAgentWhenSessionSetupFails() throws Exception {
        SubAgentTool tool =
                new SubAgentTool(
                        () -> {
                            ReActAgent agent = registeredAgent(new MockModel("unused"));
                            if (agents.size() > 1) {
                                doThrow(new IllegalStateException("setup failed"))
                                        .when(agent)
                                        .getName();
                            }
                            return agent;
                        },
                        config(false));

        StepVerifier.create(tool.callAsync(param("hello", null)))
                .assertNext(
                        result ->
                                assertTrue(
                                        text(result)
                                                .contains(
                                                        "AgentStateStore setup failed: setup"
                                                                + " failed")))
                .verifyComplete();

        awaitClosed(lastAgent());
        verify(lastAgent()).close();
        assertNoLeakedRegistrations();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedInvocationsDoNotLeakRegistrations(boolean forwardEvents) throws Exception {
        SubAgentTool tool =
                new SubAgentTool(() -> registeredAgent(new MockModel("OK")), config(forwardEvents));

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(tool.callAsync(param("hello", null)))
                    .assertNext(result -> assertTrue(text(result).endsWith("OK")))
                    .verifyComplete();

            ReActAgent agent = lastAgent();
            awaitClosed(agent);
            verify(agent).close();
            verify(agent, never()).interrupt(any(RuntimeContext.class));
            assertNoLeakedRegistrations();
        }
        assertEquals(4, agents.size(), "Initialization and each invocation need fresh agents");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesAgentAfterExecutionError(boolean forwardEvents) throws Exception {
        SubAgentTool tool =
                new SubAgentTool(
                        () -> registeredAgent(new MockModel("unused").withError("model failed")),
                        config(forwardEvents));

        StepVerifier.create(tool.callAsync(param("hello", null)))
                .assertNext(result -> assertTrue(text(result).contains("model failed")))
                .verifyComplete();

        ReActAgent agent = lastAgent();
        awaitClosed(agent);
        verify(agent).close();
        verify(agent, never()).interrupt(any(RuntimeContext.class));
        assertNoLeakedRegistrations();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesAgentWhenExecutionThrowsSynchronously(boolean forwardEvents) throws Exception {
        SubAgentTool tool =
                new SubAgentTool(
                        () -> {
                            ReActAgent agent = registeredAgent(new MockModel("unused"));
                            doThrow(new IllegalStateException("call failed"))
                                    .when(agent)
                                    .call(anyList(), any(RuntimeContext.class));
                            doThrow(new IllegalStateException("call failed")).when(agent).stream(
                                    anyList(), any(StreamOptions.class), any(RuntimeContext.class));
                            return agent;
                        },
                        config(forwardEvents));

        StepVerifier.create(tool.callAsync(param("hello", null))).verifyErrorMessage("call failed");

        ReActAgent agent = lastAgent();
        awaitClosed(agent);
        verify(agent).close();
        verify(agent, never()).interrupt(any(RuntimeContext.class));
        assertNoLeakedRegistrations();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationInterruptsBeforeClosing(boolean forwardEvents) throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Model model = spy(new MockModel("unused"));
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(
                        Flux.<ChatResponse>never()
                                .doOnSubscribe(s -> subscribed.countDown())
                                .doOnCancel(cancelled::countDown));
        SubAgentTool tool = new SubAgentTool(() -> registeredAgent(model), config(forwardEvents));
        RuntimeContext context = RuntimeContext.builder().sessionId("runtime-session").build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .input(Map.of("message", "hello", "session_id", "tool-session"))
                        .runtimeContext(context)
                        .emitter(chunk -> {})
                        .build();

        Disposable subscription = tool.callAsync(param).subscribe();
        try {
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            subscription.dispose();

            ReActAgent agent = lastAgent();
            awaitClosed(agent);
            InOrder order = inOrder(agent);
            order.verify(agent)
                    .interrupt(
                            org.mockito.ArgumentMatchers.argThat(
                                    (RuntimeContext ctx) ->
                                            "tool-session".equals(ctx.getSessionId())));
            order.verify(agent).close();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertNoLeakedRegistrations();
        } finally {
            subscription.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesConversationStateAcrossClosedInstances(boolean forwardEvents) throws Exception {
        MockModel model = new MockModel("I remember.");
        // No agent-level store: only SubAgentTool's state store can carry the history forward.
        SubAgentTool tool =
                new SubAgentTool(
                        () -> track(ReActAgent.builder().name("test_agent").model(model).build()),
                        config(forwardEvents));

        ToolResultBlock first =
                tool.callAsync(param("Remember the code word: maple.", null))
                        .block(Duration.ofSeconds(5));
        assertNotNull(first);
        String sessionId = text(first).split("\n", 2)[0].substring("session_id: ".length());
        ReActAgent firstAgent = lastAgent();
        awaitClosed(firstAgent);
        assertTrue(firstAgent.getAgentState().getContext().isEmpty(), "close clears the cache");

        ToolResultBlock second =
                tool.callAsync(param("What was the code word?", sessionId))
                        .block(Duration.ofSeconds(5));
        assertNotNull(second);
        awaitClosed(lastAgent());
        assertTrue(text(second).startsWith("session_id: " + sessionId + "\n"));
        assertEquals(
                List.of("Remember the code word: maple.", "What was the code word?"),
                model.getLastMessages().stream()
                        .filter(msg -> msg.getRole() == MsgRole.USER)
                        .map(Msg::getTextContent)
                        .toList());
        assertTrue(
                model.getLastMessages().stream()
                        .anyMatch(
                                msg ->
                                        msg.getRole() == MsgRole.ASSISTANT
                                                && "I remember.".equals(msg.getTextContent())));
    }

    @Test
    void supportsAgentsWithoutAutoCloseable() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("plain_agent");
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenReturn(Mono.just(Msg.builder().textContent("OK").build()));
        SubAgentTool tool = new SubAgentTool(() -> agent, config(false));

        StepVerifier.create(tool.callAsync(param("hello", null)))
                .assertNext(result -> assertTrue(text(result).endsWith("OK")))
                .verifyComplete();
        assertFalse(agent instanceof AutoCloseable);
    }

    @Test
    void closesOtherAutoCloseableAgentsWithoutReplacingTheResult() throws Exception {
        List<Agent> created = new ArrayList<>();
        CountDownLatch closed = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            Agent agent = mock(Agent.class, withSettings().extraInterfaces(AutoCloseable.class));
            when(agent.getName()).thenReturn("custom_agent");
            when(agent.call(anyList(), any(RuntimeContext.class)))
                    .thenReturn(Mono.just(Msg.builder().textContent("OK").build()));
            doAnswer(
                            invocation -> {
                                closed.countDown();
                                throw new Exception("close failed");
                            })
                    .when((AutoCloseable) agent)
                    .close();
            created.add(agent);
        }
        SubAgentTool tool = new SubAgentTool(created.iterator()::next, config(false));

        StepVerifier.create(tool.callAsync(param("hello", null)))
                .assertNext(result -> assertTrue(text(result).endsWith("OK")))
                .verifyComplete();

        assertTrue(closed.await(5, TimeUnit.SECONDS), "Invocation cleanup is asynchronous");
        for (Agent agent : created) {
            verify((AutoCloseable) agent).close();
        }
    }

    private ReActAgent registeredAgent(Model model) {
        ReActAgent agent =
                track(
                        ReActAgent.builder()
                                .name("test_agent")
                                .description("Test sub-agent")
                                .model(model)
                                .stateStore(new InMemoryAgentStateStore())
                                .build());
        assertTrue(stateSavers.containsKey(agent.getAgentId()), "Fixture must register a saver");
        return agent;
    }

    private ReActAgent track(ReActAgent realAgent) {
        ReActAgent agent = spy(realAgent);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            closed.countDown();
                            return null;
                        })
                .when(agent)
                .close();
        agents.put(agent, closed);
        return agent;
    }

    private ReActAgent lastAgent() {
        return new ArrayList<>(agents.keySet()).get(agents.size() - 1);
    }

    private void awaitClosed(ReActAgent agent) throws InterruptedException {
        assertTrue(agents.get(agent).await(2, TimeUnit.SECONDS), "Tool must close the agent");
    }

    private void assertNoLeakedRegistrations() {
        assertEquals(initialRegistrations, new HashSet<>(stateSavers.keySet()));
    }

    private static SubAgentConfig config(boolean forwardEvents) {
        return SubAgentConfig.builder()
                .forwardEvents(forwardEvents)
                .lifecyclePolicy(SubAgentConfig.LifecyclePolicy.PER_CALL)
                .build();
    }

    private static ToolCallParam param(String message, String sessionId) {
        Map<String, Object> input = new HashMap<>();
        input.put("message", message);
        if (sessionId != null) {
            input.put("session_id", sessionId);
        }
        return ToolCallParam.builder().input(input).emitter(chunk -> {}).build();
    }

    private static String text(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
