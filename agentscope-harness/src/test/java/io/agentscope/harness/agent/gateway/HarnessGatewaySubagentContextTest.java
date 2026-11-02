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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextRequest;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class HarnessGatewaySubagentContextTest {

    private static final String CHILD_SESSION = "child-session";
    private static final Msg REPLY =
            Msg.builder().role(MsgRole.ASSISTANT).textContent("ok").build();
    private static final List<Msg> MESSAGES =
            List.of(Msg.builder().role(MsgRole.USER).textContent("continue").build());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chatUiPreservesPeerIdentityForExposedSubagent(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        HarnessAgent child = capturingAgent(seen);
        HarnessGateway gateway = HarnessGateway.create();
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, child, null);
        ChatUiChannel chat = ChatUiChannel.create(gateway);
        ChatUiRequest request =
                new ChatUiRequest(
                        "alice",
                        null,
                        id,
                        List.of(Msg.builder().role(MsgRole.USER).textContent("continue").build()));

        if (streaming) {
            chat.sendStream(request).blockLast(Duration.ofSeconds(5));
        } else {
            assertEquals(REPLY, chat.send(request).block(Duration.ofSeconds(5)));
        }

        assertNotNull(seen.get());
        assertEquals(CHILD_SESSION, seen.get().getSessionId());
        assertEquals("alice", seen.get().getUserId());
        assertEquals("subagent:" + id, seen.get().get("gateKey"));
        assertEquals("alice", ((MsgContext) seen.get().get("msgContext")).userId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerAttributesSurviveAndChildIdentityWins(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        HarnessGateway gateway = HarnessGateway.create();
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, capturingAgent(seen), null);
        ChatUiChannel chat = ChatUiChannel.create(gateway);
        Tenant tenant = new Tenant("acme");
        RuntimeContext caller =
                RuntimeContext.builder()
                        .sessionId("parent-session")
                        .userId("caller-user")
                        .put("tenant", "acme")
                        .put(Tenant.class, tenant)
                        .put("gateKey", "caller-gate")
                        .put("msgContext", "caller-context")
                        .put("outboundAddress", "caller-outbound")
                        .build();

        // Even an explicit main-agent hint must not redirect an exposed-subagent turn.
        send(chat, new ChatUiRequest(" alice ", "main", id, MESSAGES), caller, streaming);

        RuntimeContext effective = seen.get();
        assertEquals(CHILD_SESSION, effective.getSessionId());
        assertEquals("alice", effective.getUserId());
        assertEquals("acme", effective.get("tenant"));
        assertSame(tenant, effective.get(Tenant.class));
        assertEquals("subagent:" + id, effective.get("gateKey"));
        assertEquals("alice", ((MsgContext) effective.get("msgContext")).userId());
        assertEquals(
                OutboundAddress.direct("chatui", "chatui:DIRECT:alice"),
                effective.get("outboundAddress"));
        assertEquals("parent-session", caller.getSessionId());
        assertEquals("caller-user", caller.getUserId());
        assertEquals("caller-gate", caller.get("gateKey"));
        assertEquals("caller-context", caller.get("msgContext"));
        assertEquals("caller-outbound", caller.get("outboundAddress"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolverReceivesEnvelopeAndReplacesCallerBase(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        AtomicReference<ChannelRuntimeContextRequest> resolved = new AtomicReference<>();
        HarnessGateway gateway = HarnessGateway.create();
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, capturingAgent(seen), null);
        Tenant tenant = new Tenant("resolved");
        RuntimeContext resolverContext =
                RuntimeContext.builder()
                        .sessionId("resolver-session")
                        .userId("resolver-user")
                        .put(Tenant.class, tenant)
                        .build();
        gateway.setRuntimeContextResolver(
                request -> {
                    resolved.set(request);
                    return resolverContext;
                });
        RuntimeContext caller = RuntimeContext.builder().put("callerOnly", true).build();

        send(
                ChatUiChannel.create(gateway),
                new ChatUiRequest("alice", null, id, MESSAGES),
                caller,
                streaming);

        assertEquals("chatui", resolved.get().channelId());
        assertSame(caller, resolved.get().callerContext());
        assertSame(caller, resolved.get().inboundMessage().runtimeContext());
        assertEquals(MESSAGES, resolved.get().inboundMessage().messages());
        assertEquals("alice", resolved.get().inboundMessage().peer().id());
        assertSame(resolved.get().msgContext(), seen.get().get("msgContext"));
        assertSame(resolved.get().outboundAddress(), seen.get().get("outboundAddress"));
        assertNull(seen.get().get("callerOnly"));
        assertSame(tenant, seen.get().get(Tenant.class));
        assertEquals(CHILD_SESSION, seen.get().getSessionId());
        assertEquals("alice", seen.get().getUserId());
        assertEquals("subagent:" + id, seen.get().get("gateKey"));
        assertEquals("resolver-session", resolverContext.getSessionId());
        assertEquals("resolver-user", resolverContext.getUserId());
        assertNull(resolverContext.get("msgContext"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullResolverResultKeepsCallerAndDoesNotLeakToNextTurn(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        HarnessGateway gateway = HarnessGateway.create();
        gateway.setRuntimeContextResolver(request -> null);
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, capturingAgent(seen), null);
        ChatUiChannel chat = ChatUiChannel.create(gateway);
        send(
                chat,
                new ChatUiRequest("alice", null, id, MESSAGES),
                RuntimeContext.builder().put("tenant", "acme").build(),
                streaming);
        assertEquals("acme", seen.get().get("tenant"));

        send(chat, new ChatUiRequest("bob", null, id, MESSAGES), null, streaming);
        assertEquals("bob", seen.get().getUserId());
        assertNull(seen.get().get("tenant"));
        assertEquals(CHILD_SESSION, seen.get().getSessionId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void peerlessRequestPreservesCallerUserWithoutSyntheticAnonymousIdentity(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        HarnessGateway gateway = HarnessGateway.create();
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, capturingAgent(seen), null);
        RuntimeContext caller = RuntimeContext.builder().userId("alice").build();

        send(ChatUiChannel.create(gateway), ChatUiRequest.toSubagent(id, "hi"), caller, streaming);

        assertEquals("alice", seen.get().getUserId());
        assertEquals(CHILD_SESSION, seen.get().getSessionId());
        assertNull(seen.get().get("msgContext"));
        assertNull(seen.get().get("outboundAddress"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyContextFreeCallsRemainAnonymousAndDoNotInvokeChannelResolver(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        HarnessGateway gateway = HarnessGateway.create();
        gateway.setRuntimeContextResolver(
                request -> {
                    throw new AssertionError("Context-free calls must not invoke the resolver");
                });
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, capturingAgent(seen), null);
        ChatUiChannel chat = ChatUiChannel.create(gateway);

        if (streaming) {
            gateway.runSubagentStream(id, MESSAGES).blockLast(Duration.ofSeconds(5));
            assertAnonymous(seen.get());
            chat.sendToSubagentStream(id, MESSAGES).blockLast(Duration.ofSeconds(5));
        } else {
            gateway.runSubagent(id, MESSAGES).block(Duration.ofSeconds(5));
            assertAnonymous(seen.get());
            chat.sendToSubagent(id, MESSAGES).block(Duration.ofSeconds(5));
        }
        assertAnonymous(seen.get());
        send(chat, ChatUiRequest.toSubagent(id, "hi"), null, streaming);
        assertAnonymous(seen.get());
        send(chat, new ChatUiRequest("  ", null, id, MESSAGES), null, streaming);
        assertAnonymous(seen.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultOverloadsDelegateToExistingGatewayImplementations(boolean streaming) {
        Gateway gateway = mock(Gateway.class, CALLS_REAL_METHODS);
        when(gateway.runSubagent("sub", MESSAGES)).thenReturn(Mono.just(REPLY));
        when(gateway.runSubagentStream("sub", MESSAGES))
                .thenReturn(Flux.just(new AgentResultEvent(REPLY)));

        send(
                ChatUiChannel.create(gateway),
                new ChatUiRequest("alice", null, "sub", MESSAGES),
                RuntimeContext.builder().put("tenant", "acme").build(),
                streaming);

        if (streaming) {
            verify(gateway).runSubagentStream("sub", MESSAGES);
        } else {
            verify(gateway).runSubagent("sub", MESSAGES);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mainAgentStillReceivesContextFromStructuredRequest(boolean streaming) {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        HarnessGateway gateway = HarnessGateway.create();
        gateway.bindMainAgent(capturingAgent(seen));

        send(
                ChatUiChannel.create(gateway),
                ChatUiRequest.withPeer("alice", MESSAGES),
                RuntimeContext.builder().put("tenant", "acme").build(),
                streaming);

        assertEquals("alice", seen.get().getUserId());
        assertEquals("acme", seen.get().get("tenant"));
        assertTrue(seen.get().getSessionId().startsWith("gw-"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directGatewayContextAlsoReachesReActSubagent(boolean streaming) {
        ReActAgent child = mock(ReActAgent.class);
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        when(child.call(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            seen.set(invocation.getArgument(1));
                            return Mono.just(REPLY);
                        });
        when(child.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            seen.set(invocation.getArgument(1));
                            return Flux.just(new AgentResultEvent(REPLY));
                        });
        HarnessGateway gateway = HarnessGateway.create();
        String id = gateway.exposeSubagent("worker", CHILD_SESSION, child, null);
        MsgContext context = new MsgContext("chatui", null, null, null, null, Map.of(), "alice");
        RuntimeContext caller = RuntimeContext.builder().put("tenant", "acme").build();
        OutboundAddress outbound = OutboundAddress.direct("chatui", "alice");
        InboundMessage inbound = InboundMessage.dm("chatui", "alice", MESSAGES);

        if (streaming) {
            gateway.runSubagentStream(id, context, MESSAGES, outbound, caller, inbound)
                    .blockLast(Duration.ofSeconds(5));
        } else {
            gateway.runSubagent(id, context, MESSAGES, outbound, caller, inbound)
                    .block(Duration.ofSeconds(5));
        }
        assertEquals(CHILD_SESSION, seen.get().getSessionId());
        assertEquals("alice", seen.get().getUserId());
        assertEquals("acme", seen.get().get("tenant"));
        assertSame(context, seen.get().get("msgContext"));
        assertSame(outbound, seen.get().get("outboundAddress"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownAndRevokedSubagentsStillFail(boolean streaming) {
        HarnessGateway gateway = HarnessGateway.create();
        String revoked =
                gateway.exposeSubagent("worker", CHILD_SESSION, mock(HarnessAgent.class), null);
        gateway.revokeSubagent(revoked);
        ChatUiChannel chat = ChatUiChannel.create(gateway);
        for (String id : List.of("missing", revoked)) {
            ChatUiRequest request = new ChatUiRequest("alice", null, id, MESSAGES);
            StepVerifier.create(streaming ? chat.sendStream(request) : chat.send(request))
                    .expectErrorMatches(
                            error ->
                                    error instanceof IllegalArgumentException
                                            && error.getMessage()
                                                    .equals("Unknown subagentId: " + id))
                    .verify(Duration.ofSeconds(5));
        }
    }

    private static void send(
            ChatUiChannel chat, ChatUiRequest request, RuntimeContext caller, boolean streaming) {
        if (streaming) {
            StepVerifier.create(chat.sendStream(request, caller))
                    .expectNextMatches(
                            event ->
                                    event instanceof AgentResultEvent result
                                            && REPLY.equals(result.getResult()))
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        } else {
            StepVerifier.create(chat.send(request, caller))
                    .expectNext(REPLY)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        }
    }

    private static void assertAnonymous(RuntimeContext context) {
        assertEquals(CHILD_SESSION, context.getSessionId());
        assertNull(context.getUserId());
        assertNull(context.get("msgContext"));
        assertNull(context.get("outboundAddress"));
    }

    private record Tenant(String name) {}

    private static HarnessAgent capturingAgent(AtomicReference<RuntimeContext> seen) {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            seen.set(invocation.getArgument(1));
                            return Mono.just(REPLY);
                        });
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            seen.set(invocation.getArgument(1));
                            return Flux.just(new AgentResultEvent(REPLY));
                        });
        return agent;
    }
}
