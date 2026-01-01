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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.client.transport.spi.ClientTransport;
import io.a2a.client.transport.spi.ClientTransportConfig;
import io.a2a.client.transport.spi.ClientTransportProvider;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskIdParams;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
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

        lenient().when(a2aAgentConfig.clientTransports()).thenReturn(new java.util.HashMap<>());
        lenient().when(a2aAgentConfig.clientConfig()).thenReturn(null);
        lenient().when(agentCard.preferredTransport()).thenReturn("JSONRPC");
        lenient().when(agentCard.url()).thenReturn("http://localhost:8080");
        AgentInterface defaultInterface = new AgentInterface("JSONRPC", "http://localhost:8080");
        AgentInterface customInterface = new AgentInterface("CUSTOM", "http://localhost:8080");
        lenient()
                .when(agentCard.additionalInterfaces())
                .thenReturn(List.of(defaultInterface, customInterface));
    }

    @Test
    @DisplayName("Should do call with default transport successfully")
    void testCallAgentWithDefaultTransport() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .hook(new ReplaceA2aClientHook())
                        .build();

        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        Msg result = agent.call(Msg.builder().textContent("test").build()).block();
        assertNotNull(result);
        assertEquals("mock success.", result.getTextContent());
        assertEquals(1, agent.getMemory().getMessages().size());
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
                        .hook(new ReplaceA2aClientHook())
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
                        .hook(new ReplaceA2aClientHook())
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
                        .hook(new ReplaceA2aClientHook())
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
                        .hook(new ReplaceA2aClientHook())
                        .build();
        Answer<Void> mockTaskResponse =
                invocationOnMock -> {
                    @SuppressWarnings("unchecked")
                    List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                            invocationOnMock.getArgument(1, List.class);
                    // Mock Task
                    Task task =
                            new Task.Builder()
                                    .id("taskId")
                                    .contextId("contextId")
                                    .status(new TaskStatus(TaskState.WORKING))
                                    .build();

                    // Mock First Task Event
                    TaskEvent taskEvent = new TaskEvent(task);
                    a2aEventConsumer.forEach(consumer -> consumer.accept(taskEvent, agentCard));

                    // Mock Task Artifact Update Event
                    TaskArtifactUpdateEvent artifactUpdateEvent =
                            new TaskArtifactUpdateEvent.Builder()
                                    .taskId("taskId")
                                    .contextId("contextId")
                                    .artifact(
                                            new Artifact.Builder()
                                                    .artifactId("artifactId")
                                                    .name("mockArtifact")
                                                    .parts(new TextPart("mock artifact."))
                                                    .build())
                                    .build();
                    task =
                            new Task.Builder()
                                    .id("taskId")
                                    .contextId("contextId")
                                    .status(new TaskStatus(TaskState.WORKING))
                                    .artifacts(List.of(artifactUpdateEvent.getArtifact()))
                                    .build();
                    TaskUpdateEvent artifactTaskUpdateEvent =
                            new TaskUpdateEvent(task, artifactUpdateEvent);
                    a2aEventConsumer.forEach(
                            consumer -> consumer.accept(artifactTaskUpdateEvent, agentCard));

                    // Mock Task Complete Event
                    task =
                            new Task.Builder()
                                    .id("taskId")
                                    .contextId("contextId")
                                    .status(new TaskStatus(TaskState.COMPLETED))
                                    .artifacts(List.of(artifactUpdateEvent.getArtifact()))
                                    .build();
                    TaskStatusUpdateEvent statusUpdateEvent =
                            new TaskStatusUpdateEvent(
                                    "taskId",
                                    new TaskStatus(TaskState.COMPLETED),
                                    "contextId",
                                    true,
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
        assertEquals(2, streamResults.size());
        assertFalse(streamResults.get(0).isLast());
        assertTrue(streamResults.get(1).isLast());
    }

    @Test
    @DisplayName("Should stop tasks after user request intercept")
    void testStopTasksAfterUserRequestIntercept() {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .hook(new ReplaceA2aClientHook())
                        .build();

        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        doAnswer(mockWaitingStopAnswer(stopFlag))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        when(a2aClient.cancelTask(any(TaskIdParams.class), eq(null)))
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
                        .hook(new ReplaceA2aClientHook())
                        .build();

        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        doAnswer(mockWaitingStopAnswer(stopFlag))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        when(a2aClient.cancelTask(any(TaskIdParams.class), eq(null)))
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
                        .hook(new ReplaceA2aClientHook())
                        .build();

        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        doAnswer(mockWaitingStopAnswer(stopFlag))
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());
        when(a2aClient.cancelTask(any(TaskIdParams.class), eq(null)))
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
                        .hook(new ReplaceA2aClientHook())
                        .build();

        doAnswer(mockSuccessMessage())
                .when(a2aClient)
                .sendMessage(any(Message.class), anyList(), any());

        agent.observe(Msg.builder().textContent("observe test").build()).block();
        Msg result = agent.call(Msg.builder().textContent("test").build()).block();
        assertNotNull(result);
        assertEquals("mock success.", result.getTextContent());
        assertEquals(2, agent.getMemory().getMessages().size());
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

    private Answer<Void> mockWaitingStopAnswer(final AtomicBoolean stopFlag) {
        return invocationOnMock -> {
            @SuppressWarnings("unchecked")
            List<BiConsumer<ClientEvent, AgentCard>> a2aEventConsumer =
                    invocationOnMock.getArgument(1, List.class);
            // Mock Task
            Task task =
                    new Task.Builder()
                            .id("taskId")
                            .contextId("contextId")
                            .status(new TaskStatus(TaskState.WORKING))
                            .build();
            Message message = A2A.toAgentMessage("mock task working.");
            TaskStatusUpdateEvent statusUpdateEvent =
                    new TaskStatusUpdateEvent(
                            "taskId",
                            new TaskStatus(TaskState.WORKING, message, null),
                            "contextId",
                            false,
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
                    new Task.Builder()
                            .id("taskId")
                            .contextId("contextId")
                            .status(new TaskStatus(TaskState.CANCELED))
                            .artifacts(List.of())
                            .build();
            statusUpdateEvent =
                    new TaskStatusUpdateEvent(
                            "taskId",
                            new TaskStatus(TaskState.CANCELED),
                            "contextId",
                            true,
                            Map.of());
            TaskUpdateEvent cancelStatusUpdateEvent = new TaskUpdateEvent(task, statusUpdateEvent);
            a2aEventConsumer.forEach(
                    consumer -> consumer.accept(cancelStatusUpdateEvent, agentCard));
            return null;
        };
    }

    private void assertTimeout(CompletableFuture<Void> future, final AtomicBoolean stopFlag) {
        try {
            future.get(2, TimeUnit.SECONDS);
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
                ClientTransportConfig clientTransportConfig, AgentCard agentCard, String agentUrl)
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

    private class ReplaceA2aClientHook implements Hook {

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreCallEvent preCallEvent) {
                Agent targetAgent = preCallEvent.getAgent();
                replaceA2aClient(targetAgent);
            }
            return Mono.just(event);
        }

        @Override
        public int priority() {
            // should do after A2aAgent inner A2aClientLifecycleHook.
            return 501;
        }

        private void replaceA2aClient(Agent targetAgent) {
            if (targetAgent instanceof A2aAgent a2aAgent) {
                try {
                    Field a2aClientField = A2aAgent.class.getDeclaredField("a2aClient");
                    a2aClientField.setAccessible(true);
                    Client autoBuildClient = (Client) a2aClientField.get(a2aAgent);
                    if (null != autoBuildClient) {
                        autoBuildClient.close();
                    }
                    a2aClientField.set(a2aAgent, a2aClient);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
