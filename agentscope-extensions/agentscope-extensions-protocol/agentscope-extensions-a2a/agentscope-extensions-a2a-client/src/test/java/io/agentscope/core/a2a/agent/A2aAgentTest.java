/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package io.agentscope.core.a2a.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.message.MetadataUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.ClientTransport;
import org.a2aproject.sdk.client.transport.spi.ClientTransportConfig;
import org.a2aproject.sdk.client.transport.spi.ClientTransportProvider;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for A2aAgent.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Do call with different transports.</li>
 *   <li>Do stream with different transports.</li>
 *   <li>Intercept calling.</li>
 *   <li>Observer {@link io.agentscope.core.message.Msg} into memory before calling.</li>
 * </ul>
 *
 * <p>In this Unit test, will not test to build A2aAgent, about Unit test for build A2aAgent, see {@link A2aAgentBuilderTest}.
 */
@DisplayName("A2aAgent Tests")
public class A2aAgentTest {

    private A2aAgentConfig a2aAgentConfig;
    private AgentCard agentCard;
    private Client a2aClient;

    @BeforeEach
    void setUp() {
        a2aAgentConfig = mock(A2aAgentConfig.class);
        agentCard = mock(AgentCard.class);
        a2aClient = mock(Client.class);

        lenient().when(a2aAgentConfig.clientTransports()).thenReturn(new HashMap<>());
        lenient().when(a2aAgentConfig.clientConfig()).thenReturn(null);
        AgentInterface defaultInterface = new AgentInterface("JSONRPC", "http://localhost:8080");
        AgentInterface customInterface = new AgentInterface("CUSTOM", "http://localhost:8080");
        lenient()
                .when(agentCard.supportedInterfaces())
                .thenReturn(List.of(defaultInterface, customInterface));
    }

    @Test
    @DisplayName("Should do call with default transport successfully")
    void testCallAgentWithDefaultTransport() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        Msg result = agent.call(Msg.builder().textContent("test").build()).block();
        assertNotNull(result);
        assertEquals("mock success.", result.getTextContent());
        assertEquals(2, agent.getMemory().getMessages().size());
        verify(a2aClient).close();
    }

    @Test
    @DisplayName("Should do call with custom transport successfully")
    @SuppressWarnings({"rawtypes"})
    void testCallAgentWithCustomTransport() {
        // Set custom transport to A2aAgentConfig.
        ClientTransportConfig customTransportConfig = mock(ClientTransportConfig.class);
        when(a2aAgentConfig.clientTransports())
                .thenReturn(Map.of(ClientTransport.class, customTransportConfig));
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        Msg result = agent.call(Msg.builder().textContent("test").build()).block();
        assertNotNull(result);
        assertEquals("mock success.", result.getTextContent());
    }

    @Test
    @DisplayName("Should do call with default transport for exception")
    void testCallAgentWithDefaultTransportForException() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        doThrow(new RuntimeException("mock exception."))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        assertThrows(
                RuntimeException.class,
                () -> agent.call(Msg.builder().textContent("test").build()).block());
        verify(a2aClient).close();
    }

    @Test
    @DisplayName("Should fail when stream completes without final response")
    void testCallAgentWithCompletedStreamButNoFinalResponse() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        doAnswer(
                        invocationOnMock -> {
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> errorCallback =
                                    invocationOnMock.getArgument(2, Consumer.class);
                            errorCallback.accept(null);
                            return null;
                        })
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(Msg.builder().textContent("test").build()).block());
        assertEquals("A2A stream completed before final response.", exception.getMessage());
        verify(a2aClient).close();
    }

    @Test
    @DisplayName("Should stream the text and thinking of the remote agent, then the call result")
    void testStreamEvents() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        doAnswer(
                        mockStreamedTask(
                                new TextPart(
                                        "let me think",
                                        MetadataUtil.buildTypeMetadata(
                                                MessageConstants.BlockContent.TYPE_THINKING)),
                                new TextPart("Hello, "),
                                new TextPart("world")))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        List<AgentEvent> events =
                agent.streamEvents(Msg.builder().textContent("test").build()).collectList().block();
        Msg result = agent.call(Msg.builder().textContent("test").build()).block();

        assertNotNull(events);
        AgentStartEvent start = assertInstanceOf(AgentStartEvent.class, events.get(0));
        assertEquals("test-agent", start.getName());
        assertEquals(
                List.of("let me think"),
                events.stream()
                        .filter(ThinkingBlockDeltaEvent.class::isInstance)
                        .map(event -> ((ThinkingBlockDeltaEvent) event).getDelta())
                        .toList());
        List<TextBlockDeltaEvent> texts =
                events.stream()
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .map(TextBlockDeltaEvent.class::cast)
                        .toList();
        assertEquals(
                List.of("Hello, ", "world"),
                texts.stream().map(TextBlockDeltaEvent::getDelta).toList());
        texts.forEach(text -> assertEquals(start.getReplyId(), text.getReplyId()));
        AgentResultEvent resultEvent =
                assertInstanceOf(AgentResultEvent.class, events.get(events.size() - 2));
        assertInstanceOf(AgentEndEvent.class, events.get(events.size() - 1));
        assertNotNull(result);
        assertEquals(result.getTextContent(), resultEvent.getResult().getTextContent());
    }

    @Test
    @DisplayName("Should start the event stream with the session of the call")
    void testStreamEventsWithContext() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(Msg.builder().textContent("test").build()),
                                RuntimeContext.builder().sessionId("session-1").build())
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals(
                "session-1", assertInstanceOf(AgentStartEvent.class, events.get(0)).getSessionId());
        assertEquals(
                "mock success.",
                assertInstanceOf(AgentResultEvent.class, events.get(events.size() - 2))
                        .getResult()
                        .getTextContent());
    }

    @Test
    @DisplayName("Should end the event stream with the error of a failed call")
    void testStreamEventsWithError() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        doThrow(new RuntimeException("mock exception."))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        List<AgentEvent> events = new ArrayList<>();

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                agent.streamEvents(Msg.builder().textContent("test").build())
                                        .doOnNext(events::add)
                                        .blockLast());

        assertEquals("mock exception.", error.getMessage());
        assertInstanceOf(AgentStartEvent.class, events.get(0));
        assertTrue(events.stream().noneMatch(AgentResultEvent.class::isInstance));
        verify(a2aClient).close();
    }

    @Test
    @DisplayName("Should close the client when the call is cancelled")
    void testCloseClientWhenCallIsCancelled() throws InterruptedException {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(
                        invocationOnMock -> {
                            sent.countDown();
                            return null;
                        })
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        Disposable call = agent.call(Msg.builder().textContent("test").build()).subscribe();
        assertTrue(sent.await(5, TimeUnit.SECONDS));
        call.dispose();

        verify(a2aClient, timeout(5000)).close();
    }

    @Test
    @DisplayName("Should run the onAgent middlewares around the call, outermost first")
    void testOnAgentMiddlewares() {
        List<String> entered = new ArrayList<>();
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .middleware(onAgent("inner", 1, entered, null))
                        .middleware(onAgent("outer", 2, entered, "changed"))
                        .build();
        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        Msg result = agent.call(Msg.builder().textContent("test").build()).block();

        assertEquals(List.of("outer", "inner"), entered);
        assertNotNull(result);
        assertEquals("changed", result.getTextContent());
    }

    @Test
    @DisplayName("Should run the onReasoning middlewares around the exchange with the remote agent")
    void testOnReasoningMiddlewares() {
        List<String> deltas = new ArrayList<>();
        MiddlewareBase rewriteInput =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        assertEquals("test", input.messages().get(0).getTextContent());
                        return next.apply(
                                        new ReasoningInput(
                                                List.of(
                                                        Msg.builder()
                                                                .textContent("rewritten")
                                                                .build()),
                                                input.tools(),
                                                input.options()))
                                .doOnNext(
                                        event -> {
                                            if (event instanceof TextBlockDeltaEvent delta) {
                                                deltas.add(delta.getDelta());
                                            }
                                        });
                    }
                };
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .middleware(rewriteInput)
                        .build();
        doAnswer(mockStreamedTask(new TextPart("Hello, "), new TextPart("world")))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        List<AgentEvent> events =
                agent.streamEvents(Msg.builder().textContent("test").build()).collectList().block();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(a2aClient).sendMessage(sent.capture(), anyList(), any());
        assertEquals("rewritten", ((TextPart) sent.getValue().parts().get(0)).text());
        assertEquals(List.of("Hello, ", "world"), deltas);
        assertNotNull(events);
        assertEquals(
                List.of("Hello, ", "world"),
                events.stream()
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                        .toList());
    }

    @Test
    @DisplayName("Should skip a middleware at the points it is not active at")
    void testInactiveMiddleware() {
        List<String> entered = new ArrayList<>();
        MiddlewareBase actingOnly =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_ACTING);
                    }

                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        entered.add("onAgent");
                        return next.apply(input);
                    }
                };
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .middleware(actingOnly)
                        .build();
        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        Msg result = agent.call(Msg.builder().textContent("test").build()).block();

        assertNotNull(result);
        assertEquals("mock success.", result.getTextContent());
        assertTrue(entered.isEmpty());
    }

    @Test
    @DisplayName("Should keep the history of each session apart")
    void testSessionsKeepSeparateHistories() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        RuntimeContext sessionA = RuntimeContext.builder().sessionId("a").build();
        RuntimeContext sessionB = RuntimeContext.builder().sessionId("b").build();

        agent.call(List.of(Msg.builder().textContent("a1").build()), sessionA).block();
        agent.call(List.of(Msg.builder().textContent("b1").build()), sessionB).block();
        agent.call(List.of(Msg.builder().textContent("a2").build()), sessionA).block();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(a2aClient, times(3)).sendMessage(sent.capture(), anyList(), any());
        assertEquals(List.of("a1", "mock success.", "a2"), texts(sent.getAllValues().get(2)));
        assertEquals(
                List.of("a1", "mock success.", "a2", "mock success."),
                agent.getAgentState(sessionA).getContext().stream()
                        .map(Msg::getTextContent)
                        .toList());
        assertEquals(
                List.of("b1", "mock success."),
                agent.getAgentState(sessionB).getContext().stream()
                        .map(Msg::getTextContent)
                        .toList());
    }

    @Test
    @DisplayName("Should share a memory given to the builder between all sessions")
    @SuppressWarnings("deprecation")
    void testBuilderMemoryIsSharedBetweenSessions() {
        InMemoryMemory memory = new InMemoryMemory();
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .memory(memory)
                        .build();
        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        agent.call(
                        List.of(Msg.builder().textContent("a1").build()),
                        RuntimeContext.builder().sessionId("a").build())
                .block();
        agent.call(
                        List.of(Msg.builder().textContent("b1").build()),
                        RuntimeContext.builder().sessionId("b").build())
                .block();

        assertEquals(4, memory.getMessages().size());
        assertEquals(memory, agent.getMemory());
    }

    @Test
    @DisplayName("Should see the history of the default session through getMemory")
    @SuppressWarnings("deprecation")
    void testGetMemoryIsTheDefaultSession() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        agent.observe(Msg.builder().textContent("first").build()).block();
        agent.getMemory().addMessage(Msg.builder().textContent("second").build());

        assertEquals(
                List.of("first", "second"),
                agent.getAgentState(null).getContext().stream().map(Msg::getTextContent).toList());
        agent.getMemory().deleteMessage(0);
        assertEquals(1, agent.getAgentState(null).getContext().size());
        agent.getMemory().clear();
        assertTrue(agent.getAgentState(null).getContext().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> agent.getMemory().saveTo(null, null, null));
        assertThrows(
                UnsupportedOperationException.class,
                () -> agent.getMemory().loadFrom(null, null, null));
    }

    @Test
    @DisplayName("Should cancel only the remote task of the interrupted session")
    void testInterruptCancelsOnlyItsSession() throws InterruptedException {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        CountDownLatch sent = new CountDownLatch(2);
        doAnswer(
                        invocationOnMock -> {
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocationOnMock.getArgument(1, List.class);
                            Message message = invocationOnMock.getArgument(0, Message.class);
                            Task task =
                                    Task.builder()
                                            .id("task-" + texts(message).get(0))
                                            .contextId("contextId")
                                            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                                            .build();
                            consumers.forEach(
                                    consumer -> consumer.accept(new TaskEvent(task), agentCard));
                            sent.countDown();
                            return null;
                        })
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        RuntimeContext sessionA = RuntimeContext.builder().sessionId("a").build();
        RuntimeContext sessionB = RuntimeContext.builder().sessionId("b").build();

        Disposable callA =
                agent.call(List.of(Msg.builder().textContent("a").build()), sessionA).subscribe();
        Disposable callB =
                agent.call(List.of(Msg.builder().textContent("b").build()), sessionB).subscribe();
        try {
            assertTrue(sent.await(5, TimeUnit.SECONDS));
            agent.interrupt(sessionA);

            ArgumentCaptor<CancelTaskParams> cancelled =
                    ArgumentCaptor.forClass(CancelTaskParams.class);
            verify(a2aClient).cancelTask(cancelled.capture(), eq(null));
            assertEquals("task-a", cancelled.getValue().id());
        } finally {
            callA.dispose();
            callB.dispose();
        }
    }

    @Test
    @DisplayName("Should do nothing when interrupted without a call in progress")
    void testInterruptWithoutCall() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        agent.interrupt();
        agent.interrupt(RuntimeContext.builder().sessionId("a").build());

        verify(a2aClient, never()).cancelTask(any(CancelTaskParams.class), any());
    }

    @Test
    @DisplayName("Should keep only the hooks it is given")
    void testHooksAreTheGivenOnes() {
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        return Mono.just(event);
                    }
                };

        A2aAgent agent =
                A2aAgent.builder().name("test-agent").agentCard(agentCard).hook(hook).build();

        assertEquals(List.of(hook), agent.getHooks());
    }

    @Test
    @DisplayName("Should do call with non-exist transport failed")
    @SuppressWarnings({"rawtypes"})
    void testCallAgentWithNonExistTransport() {
        // Set non-exist transport to A2aAgentConfig.
        Class<? extends ClientTransport> mockNonExistClass = mock(ClientTransport.class).getClass();
        ClientTransportConfig customTransportConfig = mock(ClientTransportConfig.class);
        when(a2aAgentConfig.clientTransports())
                .thenReturn(Map.of(mockNonExistClass, customTransportConfig));
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .a2aAgentConfig(a2aAgentConfig)
                        .build();

        assertThrows(
                A2AClientException.class,
                () -> agent.call(Msg.builder().textContent("test").build()).block());
        // When build A2A client thrown exception with non-exist transport, a2aClient is not be
        // created actual.
        verify(a2aClient, never()).close();
    }

    @Test
    @DisplayName("Should do stream with default transport successful")
    void testStreamAgentWithDefaultTransport() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();
        Answer<Void> mockTaskResponse =
                invocationOnMock -> {
                    @SuppressWarnings("unchecked")
                    List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                            invocationOnMock.getArgument(1, List.class);
                    // Mock Task
                    Task task =
                            Task.builder()
                                    .id("taskId")
                                    .contextId("contextId")
                                    .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                                    .build();

                    // Mock First Task Event
                    TaskEvent taskEvent = new TaskEvent(task);
                    a2aEventConsumer.forEach(consumer -> consumer.accept(taskEvent, agentCard));

                    // Mock Task Artifact Update Event
                    TaskArtifactUpdateEvent artifactUpdateEvent =
                            TaskArtifactUpdateEvent.builder()
                                    .taskId("taskId")
                                    .contextId("contextId")
                                    .artifact(
                                            Artifact.builder()
                                                    .artifactId("artifactId")
                                                    .name("mockArtifact")
                                                    .parts(new TextPart("mock artifact."))
                                                    .build())
                                    .build();
                    task =
                            Task.builder()
                                    .id("taskId")
                                    .contextId("contextId")
                                    .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                                    .artifacts(List.of(artifactUpdateEvent.artifact()))
                                    .build();
                    TaskUpdateEvent artifactTaskUpdateEvent =
                            new TaskUpdateEvent(task, artifactUpdateEvent);
                    a2aEventConsumer.forEach(
                            consumer -> consumer.accept(artifactTaskUpdateEvent, agentCard));

                    // Mock Task Complete Event
                    task =
                            Task.builder()
                                    .id("taskId")
                                    .contextId("contextId")
                                    .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                                    .artifacts(List.of(artifactUpdateEvent.artifact()))
                                    .build();
                    TaskStatusUpdateEvent statusUpdateEvent =
                            new TaskStatusUpdateEvent(
                                    "taskId",
                                    new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                                    "contextId",
                                    Map.of());
                    TaskUpdateEvent completedTaskUpdateEvent =
                            new TaskUpdateEvent(task, statusUpdateEvent);
                    a2aEventConsumer.forEach(
                            consumer -> consumer.accept(completedTaskUpdateEvent, agentCard));
                    return null;
                };

        doAnswer(mockTaskResponse)
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        List<Event> streamResults =
                agent.stream(Msg.builder().textContent("test").build()).collectList().block();
        assertNotNull(streamResults);
        assertEquals(3, streamResults.size());
        assertFalse(streamResults.get(0).isLast()); // ReasoningChunkEvent
        assertTrue(streamResults.get(1).isLast()); // PostReasoningEvent
        assertTrue(streamResults.get(2).isLast()); // AGENT_RESULT
    }

    @Test
    @DisplayName("Should stop tasks after user request intercept")
    void testStopTasksAfterUserRequestIntercept() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        doAnswer(mockWaitingStopAnswer(stopFlag))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        when(a2aClient.cancelTask(any(CancelTaskParams.class), eq(null)))
                .then(
                        (Answer<Task>)
                                invocationOnMock -> {
                                    stopFlag.set(true);
                                    return null;
                                });

        CompletableFuture<Void> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            agent.stream(Msg.builder().textContent("test").build())
                                    .doOnNext(
                                            event -> {
                                                if (!stopFlag.get()) {
                                                    agent.interrupt();
                                                }
                                            })
                                    .then()
                                    .block();
                            return null;
                        });
        assertTimeout(future, stopFlag);
    }

    @Test
    @DisplayName("Should not stop tasks when cancel task failed or server not support stop task.")
    void testStopTasksForUnsupportedCancelTaskServer() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        doAnswer(mockWaitingStopAnswer(stopFlag))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        when(a2aClient.cancelTask(any(CancelTaskParams.class), eq(null)))
                .thenThrow(new A2AClientException("Server not support cancel task."));

        CompletableFuture<Void> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            agent.stream(Msg.builder().textContent("test").build())
                                    .doOnNext(
                                            event -> {
                                                if (!stopFlag.get()) {
                                                    agent.interrupt();
                                                }
                                            })
                                    .then()
                                    .block();
                            return null;
                        });
        try {
            assertThrows(TimeoutException.class, () -> future.get(2, TimeUnit.SECONDS));
        } finally {
            // Force stop task
            stopFlag.set(true);
        }
    }

    @Test
    @DisplayName("Should stop tasks after user request intercept with Msg")
    void testStopTasksAfterUserRequestInterceptWithMsg() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        doAnswer(mockWaitingStopAnswer(stopFlag))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        when(a2aClient.cancelTask(any(CancelTaskParams.class), eq(null)))
                .then(
                        (Answer<Task>)
                                invocationOnMock -> {
                                    stopFlag.set(true);
                                    return null;
                                });

        CompletableFuture<Void> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            agent.stream(Msg.builder().textContent("test").build())
                                    .doOnNext(
                                            event -> {
                                                if (!stopFlag.get()) {
                                                    agent.interrupt(
                                                            Msg.builder()
                                                                    .textContent("user intercept")
                                                                    .build());
                                                }
                                            })
                                    .then()
                                    .block();
                            return null;
                        });
        assertTimeout(future, stopFlag);
    }

    @Test
    @DisplayName("Should do call with default transport by observe successfully")
    void testCallAgentWithDefaultTransportByObserve() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .build();

        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        agent.observe(Msg.builder().textContent("observe test").build()).block();
        Msg result = agent.call(Msg.builder().textContent("test").build()).block();
        assertNotNull(result);
        assertEquals("mock success.", result.getTextContent());
        assertEquals(3, agent.getMemory().getMessages().size());
    }

    @Test
    @DisplayName("Should trigger Pre, Chunk, Post reasoning events")
    void testAgentLifecycleHooksTriggeredCorrectly() {
        AtomicInteger preCount = new AtomicInteger(0);
        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicInteger postCount = new AtomicInteger(0);

        Hook lifecycleMonitorHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            preCount.incrementAndGet();
                        } else if (event instanceof ReasoningChunkEvent) {
                            chunkCount.incrementAndGet();
                        } else if (event instanceof PostReasoningEvent) {
                            postCount.incrementAndGet();
                        }
                        return Mono.just(event);
                    }

                    @Override
                    public int priority() {
                        return 1;
                    }
                };

        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-lifecycle-agent")
                        .agentCard(agentCard)
                        .clientReplacer(this::useMockClient)
                        .hook(lifecycleMonitorHook)
                        .build();

        Answer<Void> mockTaskResponse =
                invocation -> {
                    @SuppressWarnings("unchecked")
                    List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                            invocation.getArgument(1, List.class);

                    // Task creation
                    Task initialTask =
                            Task.builder()
                                    .id("t1")
                                    .contextId("c1")
                                    .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                                    .build();
                    a2aEventConsumer.forEach(c -> c.accept(new TaskEvent(initialTask), agentCard));

                    // Stream output a piece of text (Artifact Update)
                    TaskArtifactUpdateEvent chunkEvent =
                            TaskArtifactUpdateEvent.builder()
                                    .taskId("t1")
                                    .contextId("c1")
                                    .artifact(
                                            Artifact.builder()
                                                    .artifactId("a1")
                                                    .name("mockArtifact")
                                                    .parts(new TextPart("Hello A2A"))
                                                    .build())
                                    .build();
                    Task workingTask =
                            Task.builder()
                                    .id("t1")
                                    .contextId("c1")
                                    .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                                    .artifacts(List.of(chunkEvent.artifact()))
                                    .build();
                    a2aEventConsumer.forEach(
                            c -> c.accept(new TaskUpdateEvent(workingTask, chunkEvent), agentCard));

                    // Task complete (Status Update - COMPLETED)
                    Task completedTask =
                            Task.builder()
                                    .id("t1")
                                    .contextId("c1")
                                    .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                                    .artifacts(List.of(chunkEvent.artifact()))
                                    .build();
                    TaskStatusUpdateEvent completeEvent =
                            new TaskStatusUpdateEvent(
                                    "t1",
                                    new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                                    "c1",
                                    Map.of());
                    a2aEventConsumer.forEach(
                            c ->
                                    c.accept(
                                            new TaskUpdateEvent(completedTask, completeEvent),
                                            agentCard));

                    return null;
                };

        doAnswer(mockTaskResponse)
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        agent.stream(Msg.builder().textContent("测试触发").build()).collectList().block();

        assertEquals(1, preCount.get());
        assertEquals(1, chunkCount.get());
        assertEquals(1, postCount.get());
    }

    private Answer<Void> mockSuccessMessage() {
        return invocationOnMock -> {
            @SuppressWarnings("unchecked")
            List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                    invocationOnMock.getArgument(1, List.class);
            Message mockResponseMessage = A2A.toAgentMessage("mock success.");
            MessageEvent messageEvent = new MessageEvent(mockResponseMessage);
            a2aEventConsumer.forEach(consumer -> consumer.accept(messageEvent, agentCard));
            return null;
        };
    }

    /**
     * An onAgent middleware that records its name when it is entered, and replaces the result
     * with {@code result} unless it is {@code null}.
     */
    private static MiddlewareBase onAgent(
            String name, int order, List<String> entered, String result) {
        return new MiddlewareBase() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public Flux<AgentEvent> onAgent(
                    Agent agent,
                    RuntimeContext ctx,
                    AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                entered.add(name);
                return next.apply(input)
                        .map(
                                event ->
                                        null != result && event instanceof AgentResultEvent
                                                ? new AgentResultEvent(
                                                        Msg.builder().textContent(result).build())
                                                : event);
            }
        };
    }

    /** The texts of the parts of {@code message}. */
    private static List<String> texts(Message message) {
        return message.parts().stream()
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).text())
                .toList();
    }

    /** A task that streams each part in its own artifact, then completes. */
    private Answer<Void> mockStreamedTask(Part<?>... parts) {
        return invocationOnMock -> {
            @SuppressWarnings("unchecked")
            List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                    invocationOnMock.getArgument(1, List.class);
            List<Artifact> artifacts = new ArrayList<>();
            Task task =
                    Task.builder()
                            .id("taskId")
                            .contextId("contextId")
                            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                            .build();
            a2aEventConsumer.forEach(consumer -> consumer.accept(new TaskEvent(task), agentCard));
            for (Part<?> part : parts) {
                Artifact artifact =
                        Artifact.builder()
                                .artifactId("artifact-" + artifacts.size())
                                .parts(part)
                                .build();
                artifacts.add(artifact);
                TaskArtifactUpdateEvent update =
                        TaskArtifactUpdateEvent.builder()
                                .taskId("taskId")
                                .contextId("contextId")
                                .artifact(artifact)
                                .build();
                Task updated = Task.builder(task).artifacts(List.copyOf(artifacts)).build();
                a2aEventConsumer.forEach(
                        consumer ->
                                consumer.accept(new TaskUpdateEvent(updated, update), agentCard));
            }
            Task completed =
                    Task.builder(task)
                            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                            .artifacts(List.copyOf(artifacts))
                            .build();
            TaskStatusUpdateEvent done =
                    new TaskStatusUpdateEvent(
                            "taskId",
                            new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                            "contextId",
                            Map.of());
            a2aEventConsumer.forEach(
                    consumer -> consumer.accept(new TaskUpdateEvent(completed, done), agentCard));
            return null;
        };
    }

    private Answer<Void> mockWaitingStopAnswer(final AtomicBoolean stopFlag) {
        return invocationOnMock -> {
            @SuppressWarnings("unchecked")
            List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                    invocationOnMock.getArgument(1, List.class);
            // Mock Task
            Task task =
                    Task.builder()
                            .id("taskId")
                            .contextId("contextId")
                            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                            .build();
            Message message = A2A.toAgentMessage("mock task working.");
            TaskStatusUpdateEvent statusUpdateEvent =
                    new TaskStatusUpdateEvent(
                            "taskId",
                            new TaskStatus(TaskState.TASK_STATE_WORKING, message, null),
                            "contextId",
                            Map.of());
            TaskUpdateEvent workingStatusUpdateEvent = new TaskUpdateEvent(task, statusUpdateEvent);
            a2aEventConsumer.forEach(
                    consumer -> consumer.accept(workingStatusUpdateEvent, agentCard));

            // Waiting stop
            while (!stopFlag.get()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }

            // Mock Task Cancel Event
            task =
                    Task.builder()
                            .id("taskId")
                            .contextId("contextId")
                            .status(new TaskStatus(TaskState.TASK_STATE_CANCELED))
                            .artifacts(List.of())
                            .build();
            statusUpdateEvent =
                    new TaskStatusUpdateEvent(
                            "taskId",
                            new TaskStatus(TaskState.TASK_STATE_CANCELED),
                            "contextId",
                            Map.of());
            TaskUpdateEvent cancelStatusUpdateEvent = new TaskUpdateEvent(task, statusUpdateEvent);
            a2aEventConsumer.forEach(
                    consumer -> consumer.accept(cancelStatusUpdateEvent, agentCard));
            return null;
        };
    }

    private void assertTimeout(CompletableFuture<Void> future, final AtomicBoolean stopFlag) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail(
                    "interrupt operation should stop task running and stop agent running. But"
                            + " timeout");
        } catch (InterruptedException | ExecutionException ignored) {
        } finally {
            // Force stop task
            stopFlag.set(true);
        }
    }

    @SuppressWarnings("rawtypes")
    public static class CustomClientTransportProvider implements ClientTransportProvider {

        @Override
        public ClientTransport create(
                ClientTransportConfig clientTransportConfig,
                AgentCard agentCard,
                AgentInterface agentInterface)
                throws A2AClientException {
            return mock(ClientTransport.class);
        }

        @Override
        public String getTransportProtocol() {
            return "CUSTOM";
        }

        @Override
        public Class getTransportProtocolClass() {
            return ClientTransport.class;
        }
    }

    /** Uses the mock client instead of the one the agent built for the call. */
    private Client useMockClient(Client built) {
        built.close();
        return a2aClient;
    }
}
