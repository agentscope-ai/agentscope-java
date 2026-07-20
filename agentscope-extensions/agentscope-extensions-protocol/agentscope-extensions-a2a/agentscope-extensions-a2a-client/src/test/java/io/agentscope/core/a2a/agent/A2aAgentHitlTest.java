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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.a2a.agent.hitl.A2aExternalToolResponse;
import io.agentscope.core.a2a.agent.hitl.A2aHandoff;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aHitlResponse;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.hitl.A2aUserConfirmation;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.agent.utils.LoggerUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.Logger;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

@DisplayName("A2aAgent durable HITL turns")
class A2aAgentHitlTest {

    private AgentCard agentCard;
    private Client client;

    @BeforeEach
    void setUp() {
        agentCard = mock(AgentCard.class);
        client = mock(Client.class);
        lenient().when(agentCard.preferredTransport()).thenReturn("JSONRPC");
        lenient().when(agentCard.url()).thenReturn("http://localhost:8080");
        lenient()
                .when(agentCard.supportedInterfaces())
                .thenReturn(List.of(new AgentInterface("JSONRPC", "http://localhost:8080")));
    }

    @AfterEach
    void resetTracer() {
        TracerRegistry.resetToNoop();
    }

    @Test
    void freshCallKeepsCapabilityOutOfEntireLifecycleAndAddsItOnlyForCaller() {
        AtomicReference<Msg> postReasoningMessage = new AtomicReference<>();
        AtomicReference<Msg> postCallMessage = new AtomicReference<>();
        AtomicReference<Msg> tracedMessage = new AtomicReference<>();
        AtomicInteger postHookCount = new AtomicInteger();
        Hook observer =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostReasoningEvent post) {
                            postHookCount.incrementAndGet();
                            postReasoningMessage.set(post.getReasoningMessage());
                        } else if (event instanceof PostCallEvent post) {
                            postCallMessage.set(post.getFinalMessage());
                        }
                        return Mono.just(event);
                    }
                };
        TracerRegistry.register(
                new Tracer() {
                    @Override
                    public Mono<Msg> callAgent(
                            AgentBase instance,
                            List<Msg> inputMessages,
                            Supplier<Mono<Msg>> agentCall) {
                        return agentCall.get().doOnNext(tracedMessage::set);
                    }
                });
        doAnswer(inputRequired("handoff-1", "call-1"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(observer);
        CapturingSubscriber subscriber = new CapturingSubscriber();
        agent.resetSubscribers("hitl-boundary", List.of(subscriber));

        Msg result = agent.call(Msg.builder().textContent("run").build()).block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        Object nextToken =
                request.getValue().metadata().get(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY);
        A2aHandoff handoff = A2aHandoff.tryFrom(result).orElseThrow();
        assertNull(request.getValue().tenant());
        assertTrue(nextToken instanceof String && ((String) nextToken).length() >= 43);
        assertEquals(nextToken, handoff.resumeToken());
        assertFalse(request.getValue().message().toString().contains((String) nextToken));
        assertCredentialFree(postReasoningMessage.get(), (String) nextToken);
        assertCredentialFree(postCallMessage.get(), (String) nextToken);
        assertCredentialFree(tracedMessage.get(), (String) nextToken);
        assertCredentialFree(subscriber.observed.get(), (String) nextToken);
        assertCredentialFree(
                agent.getMemory().getMessages().get(agent.getMemory().getMessages().size() - 1),
                (String) nextToken);
        assertEquals(1, postHookCount.get());
        assertEquals("input-required", result.getMetadata().get("a2aTaskState"));
        assertTrue(result.getMetadata().containsKey(MessageConstants.LOCAL_HANDOFF_METADATA_KEY));
    }

    @Test
    void callerHandoffReingestedByCallIsSanitizedBeforeHooksTracingMemoryAndWire() {
        AtomicReference<List<Msg>> lastPreCallInput = new AtomicReference<>();
        AtomicReference<List<Msg>> lastTracedInput = new AtomicReference<>();
        List<MessageSendParams> requests = new ArrayList<>();
        AtomicInteger sendCount = new AtomicInteger();
        Hook preCallObserver =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent preCall) {
                            lastPreCallInput.set(List.copyOf(preCall.getInputMessages()));
                        }
                        return Mono.just(event);
                    }
                };
        TracerRegistry.register(
                new Tracer() {
                    @Override
                    public Mono<Msg> callAgent(
                            AgentBase instance,
                            List<Msg> inputMessages,
                            Supplier<Mono<Msg>> agentCall) {
                        lastTracedInput.set(List.copyOf(inputMessages));
                        return agentCall.get();
                    }
                });
        doAnswer(
                        invocation -> {
                            requests.add(invocation.getArgument(0));
                            if (sendCount.incrementAndGet() == 1) {
                                return inputRequired("handoff-1", "call-1").answer(invocation);
                            }
                            return success("second call complete").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(preCallObserver);

        Msg paused = agent.call(Msg.builder().textContent("pause").build()).block();
        A2aHandoff handoff = A2aHandoff.tryFrom(paused).orElseThrow();
        String token = handoff.resumeToken();
        Msg result = agent.call(paused).block();

        assertEquals("second call complete", result.getTextContent());
        assertEquals(token, A2aHandoff.tryFrom(paused).orElseThrow().resumeToken());
        lastPreCallInput.get().forEach(msg -> assertCredentialFree(msg, token));
        lastTracedInput.get().forEach(msg -> assertCredentialFree(msg, token));
        agent.getMemory().getMessages().forEach(msg -> assertCredentialFree(msg, token));
        assertFalse(requests.get(1).message().toString().contains(token));
        assertFalse(
                requests.get(1)
                        .message()
                        .metadata()
                        .containsKey(MessageConstants.LOCAL_HANDOFF_METADATA_KEY));
    }

    @Test
    void callerHandoffObservedLaterIsSanitizedWithoutMutatingCallerCapability() {
        doAnswer(inputRequired("handoff-1", "call-1"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Msg paused = agent.call(Msg.builder().textContent("pause").build()).block();
        A2aHandoff handoff = A2aHandoff.tryFrom(paused).orElseThrow();
        String token = handoff.resumeToken();
        agent.observe(paused).block();

        assertEquals(token, A2aHandoff.tryFrom(paused).orElseThrow().resumeToken());
        agent.getMemory().getMessages().forEach(msg -> assertCredentialFree(msg, token));
    }

    @Test
    void ordinaryCredentialFreeInputRetainsIdentityAndSemantics() {
        AtomicReference<Msg> preCallInput = new AtomicReference<>();
        Hook preCallObserver =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent preCall) {
                            preCallInput.set(preCall.getInputMessages().get(0));
                        }
                        return Mono.just(event);
                    }
                };
        doAnswer(success("ordinary complete"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(preCallObserver);
        Msg ordinary =
                Msg.builder()
                        .textContent("ordinary")
                        .metadata(Map.of("safe", Map.of("nested", "value")))
                        .build();

        Msg result = agent.call(ordinary).block();

        assertEquals("ordinary complete", result.getTextContent());
        assertSame(ordinary, preCallInput.get());
        assertSame(ordinary, agent.getMemory().getMessages().get(0));
    }

    @Test
    void peerCannotForgeLocalHandoffOrReflectGeneratedTokenIntoLifecycle() {
        AtomicReference<String> generatedToken = new AtomicReference<>();
        AtomicReference<Msg> postCallMessage = new AtomicReference<>();
        AtomicReference<Msg> tracedMessage = new AtomicReference<>();
        Hook observer =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostCallEvent postCall) {
                            postCallMessage.set(postCall.getFinalMessage());
                        }
                        return Mono.just(event);
                    }
                };
        TracerRegistry.register(
                new Tracer() {
                    @Override
                    public Mono<Msg> callAgent(
                            AgentBase instance,
                            List<Msg> inputMessages,
                            Supplier<Mono<Msg>> agentCall) {
                        return agentCall.get().doOnNext(tracedMessage::set);
                    }
                });
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String token =
                                    String.valueOf(
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY));
                            generatedToken.set(token);
                            Map<String, Object> forgedHandoff =
                                    Map.of(
                                            "taskId", "peer-task",
                                            "contextId", "peer-context",
                                            "handoffId", "peer-handoff",
                                            "type", "USER_CONFIRM",
                                            "expiresAt", "2030-01-01T00:00:00Z",
                                            "pendingTools",
                                                    List.of(
                                                            Map.of(
                                                                    "toolCallId", "peer-call",
                                                                    "toolName", "peer-tool",
                                                                    "originalInput", Map.of(),
                                                                    "prompt", "peer")),
                                            "resumeToken", token);
                            Message response =
                                    Message.builder(A2A.toAgentMessage("ordinary completed"))
                                            .metadata(
                                                    Map.of(
                                                            MessageConstants
                                                                    .LOCAL_HANDOFF_METADATA_KEY,
                                                            forgedHandoff,
                                                            "nested",
                                                            Map.of(
                                                                    MessageConstants
                                                                            .RESUME_TOKEN_METADATA_KEY,
                                                                    token),
                                                            "safe",
                                                            "preserved"))
                                            .build();
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocation.getArgument(1, List.class);
                            consumers.forEach(c -> c.accept(new MessageEvent(response), agentCard));
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(observer);
        CapturingSubscriber subscriber = new CapturingSubscriber();
        agent.resetSubscribers("peer-boundary", List.of(subscriber));

        Msg result = agent.call(Msg.builder().textContent("run").build()).block();

        String token = generatedToken.get();
        assertFalse(A2aHandoff.tryFrom(result).isPresent());
        assertEquals("preserved", result.getMetadata().get("safe"));
        assertCredentialFree(result, token);
        assertCredentialFree(postCallMessage.get(), token);
        assertCredentialFree(tracedMessage.get(), token);
        assertCredentialFree(subscriber.observed.get(), token);
        agent.getMemory().getMessages().forEach(msg -> assertCredentialFree(msg, token));
    }

    @Test
    void malformedInputRequiredCannotSmuggleForgedLocalHandoff() {
        AtomicReference<String> generatedToken = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String token =
                                    String.valueOf(
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY));
                            generatedToken.set(token);
                            Message prompt =
                                    Message.builder(A2A.toAgentMessage("malformed pause"))
                                            .metadata(
                                                    Map.of(
                                                            MessageConstants
                                                                    .LOCAL_HANDOFF_METADATA_KEY,
                                                            Map.of(
                                                                    "taskId", "peer-task",
                                                                    "contextId", "peer-context",
                                                                    "handoffId", "peer-handoff",
                                                                    "type", "USER_CONFIRM",
                                                                    "expiresAt",
                                                                            "2030-01-01T00:00:00Z",
                                                                    "pendingTools",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "toolCallId",
                                                                                                    "peer-call",
                                                                                            "toolName",
                                                                                                    "peer-tool",
                                                                                            "originalInput",
                                                                                                    Map
                                                                                                            .of(),
                                                                                            "prompt",
                                                                                                    "peer")),
                                                                    "resumeToken", token)))
                                            .build();
                            Task task =
                                    Task.builder()
                                            .id("task-hitl")
                                            .contextId("context-hitl")
                                            .status(
                                                    new TaskStatus(
                                                            TaskState.TASK_STATE_INPUT_REQUIRED,
                                                            prompt,
                                                            null))
                                            .build();
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocation.getArgument(1, List.class);
                            consumers.forEach(c -> c.accept(new TaskEvent(task), agentCard));
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        Msg result = agent().call(Msg.builder().textContent("run").build()).block();

        assertEquals("input-required", result.getMetadata().get("a2aTaskState"));
        assertFalse(A2aHandoff.tryFrom(result).isPresent());
        assertCredentialFree(result, generatedToken.get());
    }

    @Test
    void resumeAndCancelLifecycleInputsUseCredentialFreeConstantPlaceholders() {
        String reflectedResumeId = "reflected-resume-capability";
        String reflectedCancelId = "reflected-cancel-capability";
        List<List<Msg>> preCallInputs = new ArrayList<>();
        List<List<Msg>> tracedInputs = new ArrayList<>();
        List<MessageSendParams> requests = new ArrayList<>();
        Hook observer =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent preCall) {
                            preCallInputs.add(List.copyOf(preCall.getInputMessages()));
                        }
                        return Mono.just(event);
                    }
                };
        TracerRegistry.register(
                new Tracer() {
                    @Override
                    public Mono<Msg> callAgent(
                            AgentBase instance,
                            List<Msg> inputMessages,
                            Supplier<Mono<Msg>> agentCall) {
                        tracedInputs.add(List.copyOf(inputMessages));
                        return agentCall.get();
                    }
                });
        doAnswer(
                        invocation -> {
                            requests.add(invocation.getArgument(0));
                            return success("completed").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(observer);
        A2aHandoff resume = handoffWithId(reflectedResumeId, reflectedResumeId, "call-1");
        A2aHandoff cancel = handoffWithId(reflectedCancelId, reflectedCancelId, "call-2");

        agent.resume(resume, List.of(confirmation("call-1", true))).block();
        agent.cancelHandoff(cancel, A2aRequestOptions.empty()).block();

        assertEquals("Resume A2A HITL handoff", preCallInputs.get(0).get(0).getTextContent());
        assertEquals("Cancel A2A HITL handoff", preCallInputs.get(1).get(0).getTextContent());
        assertEquals("Resume A2A HITL handoff", tracedInputs.get(0).get(0).getTextContent());
        assertEquals("Cancel A2A HITL handoff", tracedInputs.get(1).get(0).getTextContent());
        assertEquals(
                "A2A HITL resume",
                assertInstanceOf(TextPart.class, requests.get(0).message().parts().get(0)).text());
        assertEquals(
                "A2A HITL cancel",
                assertInstanceOf(TextPart.class, requests.get(1).message().parts().get(0)).text());
        assertEquals(
                reflectedResumeId,
                requests.get(0).message().metadata().get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertEquals(
                reflectedCancelId,
                requests.get(1).message().metadata().get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        agent.getMemory()
                .getMessages()
                .forEach(
                        msg -> {
                            assertFalse(
                                    JsonUtils.getJsonCodec()
                                            .toJson(msg)
                                            .contains(reflectedResumeId));
                            assertFalse(
                                    JsonUtils.getJsonCodec()
                                            .toJson(msg)
                                            .contains(reflectedCancelId));
                        });
    }

    private static void assertCredentialFree(Msg msg, String token) {
        assertFalse(msg.getMetadata().containsKey(MessageConstants.LOCAL_HANDOFF_METADATA_KEY));
        assertFalse(JsonUtils.getJsonCodec().toJson(msg).contains(token));
    }

    @Test
    void clientConfigCannotPreseedReservedCredentialMetadata() {
        ClientConfig clientConfig =
                ClientConfig.builder()
                        .setMetadata(
                                Map.of(
                                        "safe",
                                        "value",
                                        MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                        "poison-current",
                                        MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY,
                                        "poison-next",
                                        MessageConstants.LOCAL_HANDOFF_METADATA_KEY,
                                        "poison-local"))
                        .build();
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .a2aAgentConfig(A2aAgentConfig.builder().clientConfig(clientConfig).build())
                        .hook(new ReplaceClientHook())
                        .build();
        doAnswer(success("ok"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        agent.call(Msg.builder().textContent("run").build()).block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        Map<String, Object> metadata = request.getValue().metadata();
        assertEquals("value", metadata.get("safe"));
        assertFalse(metadata.containsKey(MessageConstants.RESUME_TOKEN_METADATA_KEY));
        assertFalse(metadata.containsKey(MessageConstants.LOCAL_HANDOFF_METADATA_KEY));
        assertNotEquals(
                "poison-next", metadata.get(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY));
    }

    @Test
    void resumeRejectsMissingExtraDuplicateNullAndWrongTypeBeforeTransport() {
        A2aAgent agent = agent();
        A2aHandoff handoff = handoff(A2aHandoffType.USER_CONFIRM, "resume-token", "call-1");
        List<A2aHitlResponse> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(
                IllegalArgumentException.class, () -> agent.resume(handoff, List.of()).block());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agent.resume(
                                        handoff,
                                        List.of(
                                                confirmation("call-1", true),
                                                confirmation("extra", true)))
                                .block());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agent.resume(
                                        handoff,
                                        List.of(
                                                confirmation("call-1", true),
                                                confirmation("call-1", false)))
                                .block());
        assertThrows(NullPointerException.class, () -> agent.resume(handoff, withNull).block());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agent.resume(
                                        handoff,
                                        List.of(
                                                new A2aExternalToolResponse(
                                                        "call-1",
                                                        ToolResultState.SUCCESS,
                                                        List.of(),
                                                        Map.of())))
                                .block());
        verify(client, never()).sendMessage(any(MessageSendParams.class), anyList(), any(), any());
    }

    @Test
    void responseValidationNeverRendersServerControlledToolIdsOrResumeToken() {
        String reflectedToken = "reflected-resume-capability";
        A2aAgent agent = agent();
        A2aHandoff reflected = handoff(A2aHandoffType.USER_CONFIRM, reflectedToken, reflectedToken);

        List<Throwable> failures =
                List.of(
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> agent.resume(reflected, List.of()).block()),
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        agent.resume(
                                                        handoff(
                                                                A2aHandoffType.USER_CONFIRM,
                                                                reflectedToken,
                                                                "expected-call"),
                                                        List.of(confirmation(reflectedToken, true)))
                                                .block()),
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        agent.resume(
                                                        reflected,
                                                        List.of(
                                                                confirmation(reflectedToken, true),
                                                                confirmation(
                                                                        reflectedToken, false)))
                                                .block()),
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        agent.resume(
                                                        reflected,
                                                        List.of(
                                                                new A2aExternalToolResponse(
                                                                        reflectedToken,
                                                                        ToolResultState.SUCCESS,
                                                                        List.of(),
                                                                        Map.of())))
                                                .block()),
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new A2aHandoff(
                                                "task-hitl",
                                                "context-hitl",
                                                "handoff-1",
                                                A2aHandoffType.USER_CONFIRM,
                                                Instant.parse("2030-01-01T00:00:00Z"),
                                                List.of(
                                                        new A2aPendingTool(
                                                                reflectedToken,
                                                                "probe",
                                                                Map.of(),
                                                                "prompt"),
                                                        new A2aPendingTool(
                                                                reflectedToken,
                                                                "probe",
                                                                Map.of(),
                                                                "prompt")),
                                                reflectedToken)));

        failures.forEach(failure -> assertFalse(failure.toString().contains(reflectedToken)));
        verify(client, never()).sendMessage(any(MessageSendParams.class), anyList(), any(), any());
    }

    @Test
    void resumeEncodesConfirmationAndRotatesTokensOnExactTaskAndContext() {
        doAnswer(success("resumed"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();
        PermissionRule rule =
                new PermissionRule("probe", "value=2", PermissionBehavior.ALLOW, "user");

        agent.resume(
                        handoff(A2aHandoffType.USER_CONFIRM, "resume-token", "call-1"),
                        List.of(
                                new A2aUserConfirmation(
                                        "call-1", true, Map.of("value", 2), List.of(rule))),
                        A2aRequestOptions.empty())
                .block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        MessageSendParams params = request.getValue();
        assertEquals("task-hitl", params.message().taskId());
        assertEquals("context-hitl", params.message().contextId());
        assertEquals(
                "resume",
                params.message().metadata().get(MessageConstants.HITL_OPERATION_METADATA_KEY));
        assertEquals(
                "handoff-1",
                params.message().metadata().get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertEquals(
                "resume-token", params.metadata().get(MessageConstants.RESUME_TOKEN_METADATA_KEY));
        assertTrue(params.metadata().containsKey(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY));
        String wire = params.message().toString();
        assertFalse(wire.contains("resume-token"));
        assertTrue(wire.contains("modifiedInput"));
        assertTrue(wire.contains("permissionRules"));
    }

    @Test
    void resumeMergesSanitizedMetadataThenOverlaysProtocolAndRuntimeIdentity() {
        A2aHandoff handoff = handoff(A2aHandoffType.USER_CONFIRM, "resume-token", "call-1");
        A2aAgentConfig config =
                A2aAgentConfig.builder()
                        .defaultMetadata(
                                Map.of(
                                        "defaultOnly",
                                        "default",
                                        "shared",
                                        "default",
                                        "agentId",
                                        "default-agent",
                                        MessageConstants.HITL_OPERATION_METADATA_KEY,
                                        "spoof-default",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        "spoof-default",
                                        MessageConstants.HITL_RESPONSES_METADATA_KEY,
                                        "spoof-default"))
                        .build();
        A2aRequestOptions options =
                A2aRequestOptions.builder()
                        .metadata(
                                Map.of(
                                        "requestOnly",
                                        "request",
                                        "shared",
                                        "request",
                                        "agentId",
                                        "metadata-agent",
                                        "sessionId",
                                        "metadata-session",
                                        "userId",
                                        "metadata-user",
                                        "capabilityAlias",
                                        handoff,
                                        MessageConstants.HITL_OPERATION_METADATA_KEY,
                                        "spoof-request",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        "spoof-request",
                                        MessageConstants.HITL_RESPONSES_METADATA_KEY,
                                        "spoof-request"))
                        .agentId("option-agent")
                        .runtimeContext(
                                RuntimeContext.builder()
                                        .sessionId("runtime-session")
                                        .userId("runtime-user")
                                        .build())
                        .build();
        doAnswer(success("resumed"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .a2aAgentConfig(config)
                        .hook(new ReplaceClientHook())
                        .build();

        agent.resume(handoff, List.of(confirmation("call-1", true)), options).block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        Message message = request.getValue().message();
        Map<String, Object> metadata = message.metadata();
        assertEquals("task-hitl", message.taskId());
        assertEquals("context-hitl", message.contextId());
        assertEquals("default", metadata.get("defaultOnly"));
        assertEquals("request", metadata.get("requestOnly"));
        assertEquals("request", metadata.get("shared"));
        assertEquals("option-agent", metadata.get("agentId"));
        assertEquals("runtime-session", metadata.get("sessionId"));
        assertEquals("runtime-user", metadata.get("userId"));
        assertFalse(metadata.containsKey("capabilityAlias"));
        assertEquals("resume", metadata.get(MessageConstants.HITL_OPERATION_METADATA_KEY));
        assertEquals("handoff-1", metadata.get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertInstanceOf(List.class, metadata.get(MessageConstants.HITL_RESPONSES_METADATA_KEY));
    }

    @Test
    void resumeEncodesExternalTextDataAndErrorResultsWithoutCredentials() {
        doAnswer(success("resumed"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();
        A2aHandoff handoff =
                handoff(
                        A2aHandoffType.EXTERNAL_EXECUTION,
                        "external-token",
                        "text-call",
                        "data-call",
                        "error-call");

        agent.resume(
                        handoff,
                        List.of(
                                new A2aExternalToolResponse(
                                        "text-call",
                                        ToolResultState.SUCCESS,
                                        List.of(TextBlock.builder().text("ok").build()),
                                        Map.of("provider", "text")),
                                new A2aExternalToolResponse(
                                        "data-call",
                                        ToolResultState.SUCCESS,
                                        List.of(
                                                DataBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(
                                                                                "https://example.test/result.json")
                                                                        .mimeType(
                                                                                "application/json")
                                                                        .build())
                                                        .build()),
                                        Map.of("provider", "data")),
                                new A2aExternalToolResponse(
                                        "error-call",
                                        ToolResultState.ERROR,
                                        List.of(TextBlock.builder().text("failed").build()),
                                        Map.of(
                                                MessageConstants.RESUME_TOKEN_METADATA_KEY,
                                                "nested-secret"))))
                .block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        String wire = request.getValue().message().toString();
        assertTrue(wire.contains("text-call"));
        assertTrue(wire.contains("data-call"));
        assertTrue(wire.contains("error-call"));
        assertTrue(wire.contains("error"));
        assertFalse(wire.contains("external-token"));
        assertFalse(wire.contains("nested-secret"));
    }

    @Test
    void resumeDropsNestedTypedHandoffFromFinalWireJson() {
        A2aHandoff nested =
                handoff(A2aHandoffType.EXTERNAL_EXECUTION, "nested-wire-token", "call-1");
        doAnswer(success("resumed"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        agent().resume(
                        nested,
                        List.of(
                                new A2aExternalToolResponse(
                                        "call-1",
                                        ToolResultState.SUCCESS,
                                        List.of(TextBlock.builder().text("ok").build()),
                                        Map.of(
                                                "nested",
                                                List.of(Map.of("capabilityAlias", nested))))))
                .block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        String wireJson = JsonUtils.getJsonCodec().toJson(request.getValue().message());
        assertFalse(wireJson.contains("nested-wire-token"));
        assertFalse(wireJson.contains("resumeToken"));
    }

    @Test
    void freshClientResumesSerializedHandoff() {
        doAnswer(success("fresh client resumed"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        String json =
                JsonUtils.getJsonCodec()
                        .toJson(handoff(A2aHandoffType.USER_CONFIRM, "serialized-token", "call-1"));
        A2aHandoff restored = JsonUtils.getJsonCodec().fromJson(json, A2aHandoff.class);

        Msg result = agent().resume(restored, List.of(confirmation("call-1", false))).block();

        assertEquals("fresh client resumed", result.getTextContent());
        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        assertEquals(
                "serialized-token",
                request.getValue().metadata().get(MessageConstants.RESUME_TOKEN_METADATA_KEY));
    }

    @Test
    void secondPauseRotatesTokenAndPreservesNewHandoff() {
        doAnswer(inputRequired("handoff-1", "call-1"))
                .doAnswer(inputRequired("handoff-2", "call-2"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();
        A2aHandoff first =
                A2aHandoff.tryFrom(agent.call(Msg.builder().textContent("run").build()).block())
                        .orElseThrow();

        A2aHandoff second =
                A2aHandoff.tryFrom(
                                agent.resume(first, List.of(confirmation("call-1", true))).block())
                        .orElseThrow();

        assertEquals("handoff-2", second.handoffId());
        assertEquals("call-2", second.pendingTools().get(0).toolCallId());
        assertNotEquals(first.resumeToken(), second.resumeToken());
    }

    @Test
    void staleInputRequiredSnapshotDoesNotPrematurelyCompleteResume() {
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocation.getArgument(1, List.class);
                            Message priorPrompt =
                                    Message.builder(A2A.toAgentMessage("prior handoff"))
                                            .metadata(
                                                    Map.of(
                                                            MessageConstants
                                                                    .HANDOFF_ID_METADATA_KEY,
                                                            "handoff-1"))
                                            .build();
                            Task prior =
                                    Task.builder()
                                            .id("task-hitl")
                                            .contextId("context-hitl")
                                            .status(
                                                    new TaskStatus(
                                                            TaskState.TASK_STATE_INPUT_REQUIRED,
                                                            priorPrompt,
                                                            null))
                                            .build();
                            consumers.forEach(c -> c.accept(new TaskEvent(prior), agentCard));
                            consumers.forEach(
                                    c ->
                                            c.accept(
                                                    new MessageEvent(
                                                            A2A.toAgentMessage("resumed success")),
                                                    agentCard));
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        Msg result =
                agent().resume(
                                handoff(A2aHandoffType.USER_CONFIRM, "resume-token", "call-1"),
                                List.of(confirmation("call-1", true)))
                        .block();

        assertEquals("resumed success", result.getTextContent());
    }

    @Test
    void cancelUsesCurrentTokenOnlyAndValidatesLocally() {
        doAnswer(success("cancelled"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();
        assertThrows(
                NullPointerException.class,
                () -> agent.cancelHandoff(null, A2aRequestOptions.empty()).block());

        agent.cancelHandoff(
                        handoff(A2aHandoffType.USER_CONFIRM, "cancel-token", "call-1"),
                        A2aRequestOptions.empty())
                .block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        assertEquals(
                "cancel",
                request.getValue()
                        .message()
                        .metadata()
                        .get(MessageConstants.HITL_OPERATION_METADATA_KEY));
        assertEquals(
                "handoff-1",
                request.getValue()
                        .message()
                        .metadata()
                        .get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertEquals(
                "cancel-token",
                request.getValue().metadata().get(MessageConstants.RESUME_TOKEN_METADATA_KEY));
        assertFalse(
                request.getValue()
                        .metadata()
                        .containsKey(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY));
        assertFalse(request.getValue().message().toString().contains("cancel-token"));
    }

    @Test
    void cancelMergesSanitizedMetadataAndKeepsProtocolAndHandoffIdentityAuthoritative() {
        A2aHandoff handoff = handoff(A2aHandoffType.USER_CONFIRM, "cancel-token", "call-1");
        A2aAgentConfig config =
                A2aAgentConfig.builder()
                        .defaultMetadata(
                                Map.of(
                                        "defaultOnly",
                                        "default",
                                        "shared",
                                        "default",
                                        MessageConstants.HITL_OPERATION_METADATA_KEY,
                                        "spoof-default",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        "spoof-default"))
                        .build();
        A2aRequestOptions options =
                A2aRequestOptions.builder()
                        .metadata(
                                Map.of(
                                        "requestOnly",
                                        "request",
                                        "shared",
                                        "request",
                                        MessageConstants.HITL_OPERATION_METADATA_KEY,
                                        "spoof-request",
                                        MessageConstants.HANDOFF_ID_METADATA_KEY,
                                        "spoof-request",
                                        MessageConstants.HITL_RESPONSES_METADATA_KEY,
                                        "spoof-request"))
                        .agentId("cancel-agent")
                        .runtimeContext(
                                RuntimeContext.builder()
                                        .sessionId("cancel-session")
                                        .userId("cancel-user")
                                        .build())
                        .build();
        doAnswer(success("cancelled"))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .a2aAgentConfig(config)
                        .hook(new ReplaceClientHook())
                        .build();

        agent.cancelHandoff(handoff, options).block();

        ArgumentCaptor<MessageSendParams> request =
                ArgumentCaptor.forClass(MessageSendParams.class);
        verify(client).sendMessage(request.capture(), anyList(), any(), any());
        Message message = request.getValue().message();
        assertEquals("task-hitl", message.taskId());
        assertEquals("context-hitl", message.contextId());
        assertEquals("default", message.metadata().get("defaultOnly"));
        assertEquals("request", message.metadata().get("requestOnly"));
        assertEquals("request", message.metadata().get("shared"));
        assertEquals("cancel-agent", message.metadata().get("agentId"));
        assertEquals("cancel-session", message.metadata().get("sessionId"));
        assertEquals("cancel-user", message.metadata().get("userId"));
        assertEquals(
                "cancel", message.metadata().get(MessageConstants.HITL_OPERATION_METADATA_KEY));
        assertEquals("handoff-1", message.metadata().get(MessageConstants.HANDOFF_ID_METADATA_KEY));
        assertFalse(message.metadata().containsKey(MessageConstants.HITL_RESPONSES_METADATA_KEY));
    }

    @Test
    void transportErrorsAreRedactedBeforeErrorHookLoggerAndCaller() {
        String currentToken = "current-error-token";
        AtomicReference<String> nextToken = new AtomicReference<>();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        Hook errorObserver =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ErrorEvent errorEvent) {
                            hookError.set(errorEvent.getError());
                        }
                        return Mono.just(event);
                    }
                };
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String generated =
                                    (String)
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY);
                            nextToken.set(generated);
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> completion = invocation.getArgument(2);
                            completion.accept(
                                    new IllegalStateException(
                                            "transport exposed "
                                                    + currentToken
                                                    + " and "
                                                    + generated,
                                            new IllegalArgumentException(currentToken)));
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(errorObserver);

        try (MockedStatic<LoggerUtil> logger =
                mockStatic(LoggerUtil.class, Mockito.CALLS_REAL_METHODS)) {
            RuntimeException returned =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    agent.resume(
                                                    handoff(
                                                            A2aHandoffType.USER_CONFIRM,
                                                            currentToken,
                                                            "call-1"),
                                                    List.of(confirmation("call-1", true)))
                                            .block());

            ArgumentCaptor<Object[]> logArguments = ArgumentCaptor.forClass(Object[].class);
            logger.verify(
                    () ->
                            LoggerUtil.error(
                                    any(Logger.class), any(String.class), logArguments.capture()));
            Throwable logged =
                    assertInstanceOf(
                            Throwable.class,
                            logArguments.getValue()[logArguments.getValue().length - 1]);
            assertRedactedError(returned, currentToken, nextToken.get());
            assertRedactedError(hookError.get(), currentToken, nextToken.get());
            assertRedactedError(logged, currentToken, nextToken.get());
        }
    }

    @Test
    void cancellationRemainsCancellationAfterTokenRedaction() {
        String currentToken = "current-cancel-error-token";
        AtomicReference<String> nextToken = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String generated =
                                    (String)
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY);
                            nextToken.set(generated);
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> completion = invocation.getArgument(2);
                            completion.accept(
                                    new CancellationException(currentToken + " / " + generated));
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        CancellationException returned =
                assertThrows(
                        CancellationException.class,
                        () ->
                                agent().resume(
                                                handoff(
                                                        A2aHandoffType.USER_CONFIRM,
                                                        currentToken,
                                                        "call-1"),
                                                List.of(confirmation("call-1", true)))
                                        .block());

        assertRedactedError(returned, currentToken, nextToken.get());
    }

    @Test
    void synchronousTransportErrorsAreRedactedBeforeLeavingAgentLifecycle() {
        String currentToken = "current-sync-error-token";
        AtomicReference<String> nextToken = new AtomicReference<>();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        Hook errorObserver =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ErrorEvent errorEvent) {
                            hookError.set(errorEvent.getError());
                        }
                        return Mono.just(event);
                    }
                };
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String generated =
                                    (String)
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY);
                            nextToken.set(generated);
                            throw new IllegalStateException(currentToken + " / " + generated);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        RuntimeException returned =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                agent(errorObserver)
                                        .resume(
                                                handoff(
                                                        A2aHandoffType.USER_CONFIRM,
                                                        currentToken,
                                                        "call-1"),
                                                List.of(confirmation("call-1", true)))
                                        .block());

        assertRedactedError(returned, currentToken, nextToken.get());
        assertRedactedError(hookError.get(), currentToken, nextToken.get());
    }

    @Test
    void credentialFreeCallbackErrorPreservesExactThrowableGraph() {
        IllegalArgumentException cause = new IllegalArgumentException("socket closed");
        IllegalStateException original = new IllegalStateException("offline", cause);
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> completion = invocation.getArgument(2);
                            completion.accept(original);
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        RuntimeException returned =
                assertThrows(
                        RuntimeException.class,
                        () -> agent().call(Msg.builder().textContent("run").build()).block());

        assertSame(original, returned);
        assertSame(cause, returned.getCause());
    }

    @Test
    void credentialFreeSynchronousErrorPreservesExactThrowableGraph() {
        IllegalArgumentException cause = new IllegalArgumentException("route unavailable");
        IllegalStateException original = new IllegalStateException("offline", cause);
        doAnswer(
                        invocation -> {
                            throw original;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        RuntimeException returned =
                assertThrows(
                        RuntimeException.class,
                        () -> agent().call(Msg.builder().textContent("run").build()).block());

        assertSame(original, returned);
        assertSame(cause, returned.getCause());
    }

    @Test
    void credentialFreeSynchronousErrorSubtypePreservesExactThrowable() {
        AssertionError original = new AssertionError("transport assertion");
        doAnswer(
                        invocation -> {
                            throw original;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        Throwable returned =
                assertThrows(
                        Throwable.class,
                        () -> agent().call(Msg.builder().textContent("run").build()).block());

        assertSame(original, Exceptions.unwrap(returned));
    }

    @Test
    void credentialFreeFatalSynchronousErrorReleasesScopeBeforeRethrow() {
        StackOverflowError fatal = new StackOverflowError("fatal transport failure");
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 1) {
                                throw fatal;
                            }
                            return success("second call completed").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Throwable returned =
                assertThrows(
                        Throwable.class,
                        () -> agent.call(Msg.builder().textContent("first").build()).block());
        Msg second = agent.call(Msg.builder().textContent("second").build()).block();

        assertSame(fatal, Exceptions.unwrap(returned));
        assertEquals("second call completed", second.getTextContent());
        assertEquals(2, sendCount.get());
        verify(client, times(2)).close();
    }

    @Test
    void tokenBearingSynchronousErrorIsSanitizedBeforeHooksLogsAndCaller() {
        String currentToken = "sync-assertion-current-token";
        AtomicReference<String> nextToken = new AtomicReference<>();
        AtomicReference<Throwable> hookError = new AtomicReference<>();
        Hook errorObserver =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ErrorEvent errorEvent) {
                            hookError.set(errorEvent.getError());
                        }
                        return Mono.just(event);
                    }
                };
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String generated =
                                    (String)
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY);
                            nextToken.set(generated);
                            throw new AssertionError(currentToken + " / " + generated);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        Throwable returned =
                assertThrows(
                        Throwable.class,
                        () ->
                                agent(errorObserver)
                                        .resume(
                                                handoff(
                                                        A2aHandoffType.USER_CONFIRM,
                                                        currentToken,
                                                        "call-1"),
                                                List.of(confirmation("call-1", true)))
                                        .block());

        Throwable unwrapped = Exceptions.unwrap(returned);
        assertRedactedError(unwrapped, currentToken, nextToken.get());
        assertRedactedError(hookError.get(), currentToken, nextToken.get());
        assertFalse(unwrapped instanceof AssertionError);
    }

    @Test
    void tokenBearingFatalErrorIsConvertedBeforeCrossingDiagnosticBoundary() {
        String currentToken = "sync-fatal-current-token";
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 1) {
                                throw new StackOverflowError(currentToken);
                            }
                            return success("next call completed").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Throwable returned =
                assertThrows(
                        Throwable.class,
                        () ->
                                agent.resume(
                                                handoff(
                                                        A2aHandoffType.USER_CONFIRM,
                                                        currentToken,
                                                        "call-1"),
                                                List.of(confirmation("call-1", true)))
                                        .block());

        Throwable unwrapped = Exceptions.unwrap(returned);
        Msg next = agent.call(Msg.builder().textContent("next").build()).block();
        assertRedactedError(unwrapped, currentToken);
        assertFalse(unwrapped instanceof VirtualMachineError);
        assertEquals("next call completed", next.getTextContent());
        assertEquals(2, sendCount.get());
    }

    @Test
    void tokenInSuppressedDiagnosticIsRedactedWithoutRetainingThrowableGraph() {
        String currentToken = "suppressed-current-token";
        IllegalStateException original = new IllegalStateException("offline");
        original.addSuppressed(new IllegalArgumentException("leaked " + currentToken));
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> completion = invocation.getArgument(2);
                            completion.accept(original);
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        RuntimeException returned =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                agent().resume(
                                                handoff(
                                                        A2aHandoffType.USER_CONFIRM,
                                                        currentToken,
                                                        "call-1"),
                                                List.of(confirmation("call-1", true)))
                                        .block());

        assertRedactedError(returned, currentToken);
        assertNotSame(original, returned);
        for (Throwable suppressed : returned.getSuppressed()) {
            assertFalse(suppressed.toString().contains(currentToken));
        }
    }

    @Test
    void pauseFlatMapResumeReleasesFirstTurnBeforeDownstreamContinuation() {
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 1) {
                                return inputRequired("handoff-1", "call-1").answer(invocation);
                            }
                            return success("resumed in reactive chain").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Msg result =
                agent.call(Msg.builder().textContent("pause").build())
                        .flatMap(
                                paused ->
                                        agent.resume(
                                                A2aHandoff.tryFrom(paused).orElseThrow(),
                                                List.of(confirmation("call-1", true))))
                        .block();

        assertEquals("resumed in reactive chain", result.getTextContent());
        assertEquals(2, sendCount.get());
    }

    @Test
    void errorOnErrorResumeStartsNextTurnAfterExactScopeRelease() {
        IllegalStateException firstFailure = new IllegalStateException("first transport failed");
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 1) {
                                @SuppressWarnings("unchecked")
                                Consumer<Throwable> completion = invocation.getArgument(2);
                                completion.accept(firstFailure);
                                return null;
                            }
                            return success("fallback call completed").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Msg result =
                agent.call(Msg.builder().textContent("first").build())
                        .onErrorResume(
                                ignored ->
                                        agent.call(Msg.builder().textContent("fallback").build()))
                        .block();

        assertEquals("fallback call completed", result.getTextContent());
        assertEquals(2, sendCount.get());
    }

    @Test
    void throwingClientCloseCannotRetainActiveScopeOrOverrideSuccess() {
        AtomicInteger sendCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        doAnswer(invocation -> success("success-" + sendCount.incrementAndGet()).answer(invocation))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            if (closeCount.incrementAndGet() == 1) {
                                throw new IllegalStateException("cleanup failed");
                            }
                            return null;
                        })
                .when(client)
                .close();
        A2aAgent agent = agent();

        Msg first = agent.call(Msg.builder().textContent("first").build()).block();
        Msg second = agent.call(Msg.builder().textContent("second").build()).block();

        assertEquals("success-1", first.getTextContent());
        assertEquals("success-2", second.getTextContent());
        assertEquals(2, closeCount.get());
        verify(client, times(2)).close();
    }

    @Test
    void throwingClientCloseCannotOverridePrimaryError() {
        IllegalStateException primary = new IllegalStateException("primary transport failure");
        AtomicInteger closeCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> completion = invocation.getArgument(2);
                            completion.accept(primary);
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            closeCount.incrementAndGet();
                            throw new IllegalStateException("cleanup failure");
                        })
                .when(client)
                .close();

        RuntimeException returned =
                assertThrows(
                        RuntimeException.class,
                        () -> agent().call(Msg.builder().textContent("run").build()).block());

        assertSame(primary, returned);
        assertEquals(1, closeCount.get());
    }

    @Test
    void assertionErrorFromClientCloseCannotOverrideSuccessOrRetainScope() {
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(invocation -> success("success-" + sendCount.incrementAndGet()).answer(invocation))
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            throw new AssertionError("close assertion diagnostic");
                        })
                .when(client)
                .close();
        A2aAgent agent = agent();

        Msg first = agent.call(Msg.builder().textContent("first").build()).block();
        Msg second = agent.call(Msg.builder().textContent("second").build()).block();

        assertEquals("success-1", first.getTextContent());
        assertEquals("success-2", second.getTextContent());
        assertEquals(2, sendCount.get());
        verify(client, times(2)).close();
    }

    @Test
    void assertionErrorFromClientCloseCannotOverridePrimaryError() {
        IllegalStateException primary = new IllegalStateException("primary transport failure");
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 1) {
                                @SuppressWarnings("unchecked")
                                Consumer<Throwable> completion = invocation.getArgument(2);
                                completion.accept(primary);
                                return null;
                            }
                            return success("next completed").answer(invocation);
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            throw new AssertionError("close assertion diagnostic");
                        })
                .when(client)
                .close();
        A2aAgent agent = agent();

        RuntimeException returned =
                assertThrows(
                        RuntimeException.class,
                        () -> agent.call(Msg.builder().textContent("first").build()).block());
        Msg next = agent.call(Msg.builder().textContent("next").build()).block();

        assertSame(primary, returned);
        assertEquals("next completed", next.getTextContent());
        assertEquals(2, sendCount.get());
    }

    @Test
    void fatalClientCloseDiagnosticCannotLeakTokenOrRetainScopeAfterCancellation() {
        String token = "fatal-close-sensitive-token";
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 2) {
                                return success("next completed").answer(invocation);
                            }
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            throw new StackOverflowError("close diagnostic " + token);
                        })
                .when(client)
                .close();
        A2aAgent agent = agent();

        try (MockedStatic<LoggerUtil> logger =
                mockStatic(LoggerUtil.class, Mockito.CALLS_REAL_METHODS)) {
            Disposable abandoned =
                    agent.resume(
                                    handoff(A2aHandoffType.USER_CONFIRM, token, "call-1"),
                                    List.of(confirmation("call-1", true)))
                            .subscribe();
            abandoned.dispose();

            Msg next = agent.call(Msg.builder().textContent("next").build()).block();

            assertEquals("next completed", next.getTextContent());
            assertEquals(2, sendCount.get());
            ArgumentCaptor<Object[]> logArguments = ArgumentCaptor.forClass(Object[].class);
            logger.verify(
                    () ->
                            LoggerUtil.warn(
                                    any(Logger.class), any(String.class), logArguments.capture()),
                    Mockito.atLeastOnce());
            assertFalse(java.util.Arrays.deepToString(logArguments.getValue()).contains(token));
        }
    }

    @Test
    void downstreamDisposeReleasesExactTurnBeforeNextOrdinaryCall() {
        List<MessageSendParams> requests = new ArrayList<>();
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            requests.add(invocation.getArgument(0));
                            if (sendCount.incrementAndGet() == 2) {
                                @SuppressWarnings("unchecked")
                                List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                        invocation.getArgument(1, List.class);
                                consumers.forEach(
                                        c ->
                                                c.accept(
                                                        new MessageEvent(
                                                                A2A.toAgentMessage("ordinary")),
                                                        agentCard));
                            }
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Disposable abandoned =
                agent.resume(
                                handoff(A2aHandoffType.USER_CONFIRM, "abandoned-token", "call-1"),
                                List.of(confirmation("call-1", true)))
                        .subscribe();
        assertEquals(1, sendCount.get());
        abandoned.dispose();

        Msg result = agent.call(Msg.builder().textContent("ordinary request").build()).block();

        assertEquals("ordinary", result.getTextContent());
        assertEquals(2, requests.size());
        MessageSendParams ordinary = requests.get(1);
        assertNull(ordinary.message().taskId());
        assertFalse(
                ordinary.message()
                        .metadata()
                        .containsKey(MessageConstants.HITL_OPERATION_METADATA_KEY));
        assertFalse(ordinary.metadata().containsKey(MessageConstants.RESUME_TOKEN_METADATA_KEY));
        verify(client, times(2)).close();
    }

    @Test
    void downstreamDisposeAbandonsOldCallbacksWithoutAffectingNextTurn() {
        AtomicReference<List<BiConsumer<ClientEvent, AgentCard>>> firstConsumers =
                new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> firstCompletion = new AtomicReference<>();
        AtomicReference<List<BiConsumer<ClientEvent, AgentCard>>> secondConsumers =
                new AtomicReference<>();
        AtomicReference<Msg> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Msg> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        AtomicInteger reasoningChunks = new AtomicInteger();
        AtomicInteger postReasoning = new AtomicInteger();
        AtomicInteger errorHooks = new AtomicInteger();
        AtomicInteger sendCount = new AtomicInteger();
        Hook lifecycleObserver =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof io.agentscope.core.hook.ReasoningChunkEvent) {
                            reasoningChunks.incrementAndGet();
                        } else if (event instanceof PostReasoningEvent) {
                            postReasoning.incrementAndGet();
                        } else if (event instanceof ErrorEvent) {
                            errorHooks.incrementAndGet();
                        }
                        return Mono.just(event);
                    }
                };
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocation.getArgument(1, List.class);
                            @SuppressWarnings("unchecked")
                            Consumer<Throwable> completion = invocation.getArgument(2);
                            if (sendCount.incrementAndGet() == 1) {
                                firstConsumers.set(consumers);
                                firstCompletion.set(completion);
                            } else {
                                secondConsumers.set(consumers);
                            }
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent(lifecycleObserver);

        Disposable first =
                agent.resume(
                                handoff(A2aHandoffType.USER_CONFIRM, "abandoned-token", "call-1"),
                                List.of(confirmation("call-1", true)))
                        .subscribe(firstResult::set, firstError::set);
        first.dispose();
        Disposable second =
                agent.call(Msg.builder().textContent("next turn").build())
                        .subscribe(secondResult::set, secondError::set);

        Task oldTask =
                Task.builder()
                        .id("old-task")
                        .contextId("old-context")
                        .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                        .build();
        Artifact oldArtifact =
                Artifact.builder()
                        .artifactId("old-artifact")
                        .name("old")
                        .parts(new TextPart("late chunk"))
                        .build();
        firstConsumers
                .get()
                .forEach(
                        consumer -> {
                            consumer.accept(
                                    new org.a2aproject.sdk.client.TaskUpdateEvent(
                                            oldTask,
                                            new TaskArtifactUpdateEvent(
                                                    oldTask.id(),
                                                    oldArtifact,
                                                    oldTask.contextId(),
                                                    false,
                                                    false,
                                                    Map.of())),
                                    agentCard);
                            consumer.accept(
                                    new MessageEvent(A2A.toAgentMessage("late terminal")),
                                    agentCard);
                        });
        firstCompletion.get().accept(new IllegalStateException("late transport error"));

        assertNull(firstResult.get());
        assertNull(firstError.get());
        assertEquals(0, reasoningChunks.get());
        assertEquals(0, postReasoning.get());
        assertEquals(0, errorHooks.get());
        assertNull(secondResult.get());
        assertNull(secondError.get());

        secondConsumers
                .get()
                .forEach(
                        consumer ->
                                consumer.accept(
                                        new MessageEvent(A2A.toAgentMessage("next completed")),
                                        agentCard));

        assertEquals("next completed", secondResult.get().getTextContent());
        assertNull(secondError.get());
        assertEquals(0, reasoningChunks.get());
        assertEquals(1, postReasoning.get());
        assertEquals(0, errorHooks.get());
        assertTrue(second.isDisposed());
    }

    @Test
    void generatedResumeTokenReflectedByPeerNeverReachesInboundLogArguments() {
        AtomicReference<String> generatedToken = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            MessageSendParams params = invocation.getArgument(0);
                            String token =
                                    (String)
                                            params.metadata()
                                                    .get(
                                                            MessageConstants
                                                                    .NEXT_RESUME_TOKEN_METADATA_KEY);
                            generatedToken.set(token);
                            Map<String, Object> reflected =
                                    Map.of(MessageConstants.NEXT_RESUME_TOKEN_METADATA_KEY, token);
                            Message statusMessage =
                                    Message.builder()
                                            .role(Message.Role.ROLE_AGENT)
                                            .parts(new TextPart("safe status text", reflected))
                                            .metadata(reflected)
                                            .build();
                            Artifact artifact =
                                    Artifact.builder()
                                            .artifactId("artifact-sensitive")
                                            .name("agent")
                                            .metadata(reflected)
                                            .parts(new DataPart(Map.of("safe", "value"), reflected))
                                            .build();
                            Task task =
                                    Task.builder()
                                            .id("task-sensitive")
                                            .contextId("context-sensitive")
                                            .status(
                                                    new TaskStatus(
                                                            TaskState.TASK_STATE_COMPLETED,
                                                            statusMessage,
                                                            null))
                                            .artifacts(List.of(artifact))
                                            .build();
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocation.getArgument(1, List.class);
                            consumers.forEach(
                                    consumer -> consumer.accept(new TaskEvent(task), agentCard));
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());

        try (MockedStatic<LoggerUtil> logger =
                mockStatic(LoggerUtil.class, Mockito.CALLS_REAL_METHODS)) {
            Msg result = agent().call(Msg.builder().textContent("run").build()).block();

            assertEquals(
                    "completed",
                    result.getMetadata().get(MessageConstants.A2A_TASK_STATE_METADATA_KEY));
            String token = generatedToken.get();
            assertTrue(token != null && !token.isBlank());
            logger.verify(
                    () -> LoggerUtil.logA2aClientEventDetail(any(Logger.class), any()), never());

            ArgumentCaptor<Object[]> traceArguments = ArgumentCaptor.forClass(Object[].class);
            logger.verify(
                    () ->
                            LoggerUtil.trace(
                                    any(Logger.class), any(String.class), traceArguments.capture()),
                    Mockito.atLeastOnce());
            ArgumentCaptor<Object[]> infoArguments = ArgumentCaptor.forClass(Object[].class);
            logger.verify(
                    () ->
                            LoggerUtil.info(
                                    any(Logger.class), any(String.class), infoArguments.capture()),
                    Mockito.atLeastOnce());
            ArgumentCaptor<Object[]> debugArguments = ArgumentCaptor.forClass(Object[].class);
            logger.verify(
                    () ->
                            LoggerUtil.debug(
                                    any(Logger.class), any(String.class), debugArguments.capture()),
                    Mockito.atLeastOnce());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Msg>> loggedMessages = ArgumentCaptor.forClass(List.class);
            logger.verify(
                    () -> LoggerUtil.logTextMsgDetail(any(Logger.class), loggedMessages.capture()),
                    Mockito.atLeastOnce());
            logger.verify(
                    () -> LoggerUtil.warn(any(Logger.class), any(String.class), any()), never());
            logger.verify(
                    () -> LoggerUtil.error(any(Logger.class), any(String.class), any()), never());

            List<Object[]> captured = new ArrayList<>();
            captured.addAll(traceArguments.getAllValues());
            captured.addAll(infoArguments.getAllValues());
            captured.addAll(debugArguments.getAllValues());
            assertFalse(java.util.Arrays.deepToString(captured.toArray()).contains(token));
            loggedMessages
                    .getAllValues()
                    .forEach(
                            messages ->
                                    assertFalse(
                                            JsonUtils.getJsonCodec()
                                                    .toJson(messages)
                                                    .contains(token)));
            String structure = java.util.Arrays.deepToString(captured.toArray());
            assertTrue(structure.contains("TaskEvent"));
            assertTrue(structure.contains("task-sensitive"));
            assertTrue(structure.contains("TASK_STATE_COMPLETED"));
        }
    }

    @Test
    void throwingClientCloseDuringCancellationStillReleasesExactScope() {
        String currentToken = "cancelled-scope-token";
        AtomicInteger sendCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 2) {
                                return success("next call completed").answer(invocation);
                            }
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            if (closeCount.incrementAndGet() == 1) {
                                throw new IllegalStateException(
                                        "close leaked credential " + currentToken);
                            }
                            return null;
                        })
                .when(client)
                .close();
        A2aAgent agent = agent();

        try (MockedStatic<LoggerUtil> logger =
                mockStatic(LoggerUtil.class, Mockito.CALLS_REAL_METHODS)) {
            Disposable abandoned =
                    agent.resume(
                                    handoff(A2aHandoffType.USER_CONFIRM, currentToken, "call-1"),
                                    List.of(confirmation("call-1", true)))
                            .subscribe();
            assertEquals(1, sendCount.get());
            abandoned.dispose();

            Msg result = agent.call(Msg.builder().textContent("next").build()).block();

            assertEquals("next call completed", result.getTextContent());
            assertEquals(2, sendCount.get());
            assertEquals(2, closeCount.get());
            verify(client, times(2)).close();
            ArgumentCaptor<Object[]> logArguments = ArgumentCaptor.forClass(Object[].class);
            logger.verify(
                    () ->
                            LoggerUtil.warn(
                                    any(Logger.class), any(String.class), logArguments.capture()));
            assertFalse(
                    java.util.Arrays.deepToString(logArguments.getValue()).contains(currentToken));
        }
    }

    @Test
    void assertionErrorFromClientCloseDuringCancellationCannotRetainScope() {
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (sendCount.incrementAndGet() == 2) {
                                return success("next call completed").answer(invocation);
                            }
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        doAnswer(
                        invocation -> {
                            throw new AssertionError("close assertion diagnostic");
                        })
                .when(client)
                .close();
        A2aAgent agent = agent();

        Disposable abandoned =
                agent.resume(
                                handoff(A2aHandoffType.USER_CONFIRM, "cancel-token", "call-1"),
                                List.of(confirmation("call-1", true)))
                        .subscribe();
        abandoned.dispose();

        Msg next = agent.call(Msg.builder().textContent("next").build()).block();

        assertEquals("next call completed", next.getTextContent());
        assertEquals(2, sendCount.get());
    }

    @Test
    void overlappingSubscriptionIsRejectedWithoutMutatingActiveTurn() {
        AtomicReference<List<BiConsumer<ClientEvent, AgentCard>>> firstConsumers =
                new AtomicReference<>();
        AtomicReference<Msg> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            int invocationNumber = sendCount.incrementAndGet();
                            @SuppressWarnings("unchecked")
                            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                                    invocation.getArgument(1, List.class);
                            if (invocationNumber == 1) {
                                firstConsumers.set(consumers);
                            } else {
                                consumers.forEach(
                                        c ->
                                                c.accept(
                                                        new MessageEvent(
                                                                A2A.toAgentMessage(
                                                                        "overlap unexpectedly"
                                                                                + " ran")),
                                                        agentCard));
                            }
                            return null;
                        })
                .when(client)
                .sendMessage(any(MessageSendParams.class), anyList(), any(), any());
        A2aAgent agent = agent();

        Disposable first =
                agent.resume(
                                handoff(A2aHandoffType.USER_CONFIRM, "first-token", "call-1"),
                                List.of(confirmation("call-1", true)))
                        .subscribe(firstResult::set, firstError::set);
        assertEquals(1, sendCount.get());

        IllegalStateException overlap =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(Msg.builder().textContent("must not cross-wire").build())
                                        .block());
        assertTrue(overlap.getMessage().contains("already has an active call"));
        assertEquals(1, sendCount.get());

        firstConsumers
                .get()
                .forEach(
                        c ->
                                c.accept(
                                        new MessageEvent(A2A.toAgentMessage("first complete")),
                                        agentCard));

        assertEquals("first complete", firstResult.get().getTextContent());
        assertNull(firstError.get());
        assertTrue(first.isDisposed());
        verify(client).close();
    }

    private static void assertRedactedError(Throwable error, String... tokens) {
        assertNull(error.getCause());
        String rendered = error.toString();
        for (String token : tokens) {
            assertFalse(rendered.contains(token));
        }
    }

    private A2aAgent agent(Hook... hooks) {
        A2aAgent.Builder builder =
                A2aAgent.builder()
                        .name("test-agent")
                        .agentCard(agentCard)
                        .hook(new ReplaceClientHook());
        for (Hook hook : hooks) {
            builder.hook(hook);
        }
        return builder.build();
    }

    private A2aHandoff handoff(A2aHandoffType type, String token, String... pendingToolCallIds) {
        return new A2aHandoff(
                "task-hitl",
                "context-hitl",
                "handoff-1",
                type,
                Instant.parse("2030-01-01T00:00:00Z"),
                java.util.Arrays.stream(pendingToolCallIds)
                        .map(
                                id ->
                                        new A2aPendingTool(
                                                id, "probe", Map.of("value", 1), "Allow probe?"))
                        .toList(),
                token);
    }

    private A2aHandoff handoffWithId(String handoffId, String token, String toolCallId) {
        return new A2aHandoff(
                "task-hitl",
                "context-hitl",
                handoffId,
                A2aHandoffType.USER_CONFIRM,
                Instant.parse("2030-01-01T00:00:00Z"),
                List.of(
                        new A2aPendingTool(
                                toolCallId, "probe", Map.of("value", 1), "Allow probe?")),
                token);
    }

    private A2aUserConfirmation confirmation(String toolCallId, boolean approved) {
        return new A2aUserConfirmation(toolCallId, approved, null, List.of());
    }

    private org.mockito.stubbing.Answer<Void> success(String text) {
        return invocation -> {
            @SuppressWarnings("unchecked")
            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                    invocation.getArgument(1, List.class);
            consumers.forEach(c -> c.accept(new MessageEvent(A2A.toAgentMessage(text)), agentCard));
            return null;
        };
    }

    private org.mockito.stubbing.Answer<Void> inputRequired(String handoffId, String toolCallId) {
        return invocation -> {
            @SuppressWarnings("unchecked")
            List<BiConsumer<ClientEvent, AgentCard>> consumers =
                    invocation.getArgument(1, List.class);
            Message prompt =
                    Message.builder(A2A.toAgentMessage("Please confirm"))
                            .metadata(
                                    Map.of(
                                            MessageConstants.HANDOFF_ID_METADATA_KEY,
                                            handoffId,
                                            MessageConstants.HANDOFF_TYPE_METADATA_KEY,
                                            "USER_CONFIRM",
                                            MessageConstants.HANDOFF_EXPIRES_AT_METADATA_KEY,
                                            "2030-01-01T00:00:00Z",
                                            MessageConstants.PENDING_TOOLS_METADATA_KEY,
                                            List.of(
                                                    Map.of(
                                                            "toolCallId",
                                                            toolCallId,
                                                            "toolName",
                                                            "probe",
                                                            "originalInput",
                                                            Map.of("value", 1),
                                                            "prompt",
                                                            "Allow probe?"))))
                            .build();
            Task task =
                    Task.builder()
                            .id("task-hitl")
                            .contextId("context-hitl")
                            .status(
                                    new TaskStatus(
                                            TaskState.TASK_STATE_INPUT_REQUIRED, prompt, null))
                            .build();
            consumers.forEach(c -> c.accept(new TaskEvent(task), agentCard));
            return null;
        };
    }

    private static final class CapturingSubscriber extends AgentBase {

        private final AtomicReference<Msg> observed = new AtomicReference<>();

        private CapturingSubscriber() {
            super("subscriber", "captures broadcasts", true, List.of());
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            observed.set(msg);
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            return Mono.empty();
        }
    }

    private class ReplaceClientHook implements Hook {

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PreCallEvent preCallEvent) {
                replace(preCallEvent.getAgent());
            }
            return Mono.just(event);
        }

        @Override
        public int priority() {
            return 501;
        }

        private void replace(Agent target) {
            if (!(target instanceof A2aAgent a2aAgent)) {
                return;
            }
            try {
                Field field = A2aAgent.class.getDeclaredField("a2aClient");
                field.setAccessible(true);
                Client autoBuilt = (Client) field.get(a2aAgent);
                if (autoBuilt != null) {
                    autoBuilt.close();
                }
                field.set(a2aAgent, client);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }
    }
}
