/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.aistio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.agentscope.aistio.proto.ConversationTurnCommand;
import io.agentscope.aistio.proto.ConversationTurnReport;
import io.agentscope.aistio.proto.Upstream;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.transport.GrpcTransport;
import io.grpc.stub.StreamObserver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class SessionBridgeConversationTest {
    @Test
    void sendsTheActualTerminalReplyAfterAgentCompletes() throws Exception {
        Sinks.One<Msg> reply = Sinks.one();
        List<Msg> inputs = new ArrayList<>();
        try (Fixture fixture = new Fixture(reply.asMono(), inputs)) {
            fixture.dispatch();
            assertEquals(List.of("accepted", "started"), fixture.actions());
            assertEquals("测试", inputs.get(0).getTextContent());
            reply.tryEmitValue(
                    Msg.builder().role(MsgRole.ASSISTANT).textContent("测试成功\n\"回答\"").build());
            assertEquals(List.of("accepted", "started", "completed"), fixture.actions());
            ConversationTurnReport report = fixture.reports.get(2);
            assertEquals(
                    "测试成功\n\"回答\"",
                    new ObjectMapper()
                            .readTree(report.getPayload().toByteArray())
                            .path("content")
                            .asText());
            assertEquals("chat-session", report.getSessionId());
            assertEquals("turn-one", report.getTurnId());
            assertEquals("invocation-one", report.getInvocationId());
            assertEquals("conversation-one", report.getConversationId());
            assertEquals(7, report.getGeneration());
        }
    }

    @Test
    void agentFailureProducesAFailedReport() throws Exception {
        try (Fixture fixture =
                new Fixture(
                        Mono.error(new IllegalStateException("provider unavailable")),
                        new ArrayList<>())) {
            fixture.dispatch();
            assertEquals(List.of("accepted", "started", "failed"), fixture.actions());
            assertTrue(fixture.reports.get(2).getErrorMessage().contains("provider unavailable"));
        }
    }

    @Test
    void emptyCompletionIsNotReportedAsSuccessfulAcceptance() throws Exception {
        try (Fixture fixture = new Fixture(Mono.empty(), new ArrayList<>())) {
            fixture.dispatch();
            assertEquals(List.of("accepted", "started", "failed"), fixture.actions());
            assertTrue(fixture.reports.get(2).getErrorMessage().contains("reply"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final List<ConversationTurnReport> reports = new ArrayList<>();
        final SessionBridge bridge;
        final GrpcTransport transport;

        @SuppressWarnings("unchecked")
        Fixture(Mono<Msg> reply, List<Msg> inputs) throws Exception {
            bridge =
                    new SessionBridge(
                            AistioConfig.builder("test-agent")
                                    .enableEvents(false)
                                    .startGrpc(false)
                                    .startHttp(false)
                                    .build());
            bridge.attach(
                    new StubAgent("agent", null) {
                        @Override
                        public Mono<Msg> call(List<Msg> messages) {
                            inputs.addAll(messages);
                            return reply;
                        }
                    },
                    new AgentScopeAdapter());
            transport =
                    new GrpcTransport(
                            "unused",
                            "",
                            "agent",
                            "agent",
                            "binding",
                            "default",
                            "default",
                            "instance",
                            7,
                            "agentscope-java",
                            "test",
                            List.of(),
                            "");
            ((AtomicReference<StreamObserver<Upstream>>) field(transport, "stream"))
                    .set(
                            new StreamObserver<>() {
                                @Override
                                public void onNext(Upstream value) {
                                    if (value.hasConversationTurn())
                                        reports.add(value.getConversationTurn());
                                }

                                @Override
                                public void onError(Throwable error) {}

                                @Override
                                public void onCompleted() {}
                            });
            ((AtomicBoolean) field(transport, "connected")).set(true);
            Field grpc = SessionBridge.class.getDeclaredField("grpc");
            grpc.setAccessible(true);
            grpc.set(bridge, transport);
        }

        void dispatch() throws Exception {
            Method method =
                    SessionBridge.class.getDeclaredMethod(
                            "onConversationTurn", ConversationTurnCommand.class);
            method.setAccessible(true);
            method.invoke(
                    bridge,
                    ConversationTurnCommand.newBuilder()
                            .setInvocationId("invocation-one")
                            .setConversationId("conversation-one")
                            .setTurnId("turn-one")
                            .setSessionId("chat-session")
                            .setGeneration(7)
                            .setDeadline(System.currentTimeMillis() + 60000)
                            .setInput(ByteString.copyFromUtf8("{\"message\":\"测试\"}"))
                            .build());
        }

        List<String> actions() {
            return reports.stream().map(ConversationTurnReport::getAction).toList();
        }

        private static Object field(Object instance, String name) throws Exception {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        }

        @Override
        public void close() {
            bridge.close();
            transport.close();
        }
    }
}
