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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionExecution;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@HarnessQuiescence
class HarnessGatewaySubagentSessionContextTest {

    @TempDir Path workspace;

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void parentSessionExecutionIsNotReusedByExposedSubagent(
            boolean streaming, boolean fromResolver) {
        AtomicReference<RuntimeContext> parentContext = new AtomicReference<>();
        AtomicReference<RuntimeContext> childContext = new AtomicReference<>();
        Tenant tenant = new Tenant("acme");
        SessionExportSink parentSink =
                new SessionExportSink() {
                    @Override
                    public String name() {
                        return "parent";
                    }

                    @Override
                    public void accept(SessionEvent event) {}
                };
        RuntimeContext initial =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("parent-session")
                        .put("tenant", "acme")
                        .put(Tenant.class, tenant)
                        .put(SessionExportSink.CONTEXT_KEY, parentSink)
                        .build();

        try (HarnessAgent parent = agent("parent", parentContext);
                HarnessAgent child = agent("child", childContext);
                var parentSession = parent.session(initial)) {
            var task = parentSession.submit("parent input");
            assertNotNull(parentSession.await(task).block(Duration.ofSeconds(5)));
            RuntimeContext source = parentContext.get();
            assertNotNull(SessionExecution.from(source));
            assertNotNull(SessionRecorder.from(source));
            assertNotNull(source.getAgentState());
            assertSame(parentSink, source.get(SessionExportSink.CONTEXT_KEY));
            source.put(SessionRecorder.PARENT_KEY, Map.of("parentSessionId", "upstream"));
            Map<String, Object> original = new HashMap<>();
            for (String key :
                    List.of(
                            SessionRecorder.CONTEXT_KEY,
                            SessionRecorder.TURN_ID_KEY,
                            SessionRecorder.PARENT_KEY,
                            SessionExecution.CONTEXT_KEY,
                            SessionExportSink.CONTEXT_KEY,
                            ExecutionIdentity.CONTEXT_KEY)) {
                original.put(key, source.get(key));
            }
            AgentState parentState = source.getAgentState();

            HarnessGateway gateway = HarnessGateway.create();
            String id = gateway.exposeSubagent("child", "child-session", child, null);
            if (fromResolver) {
                gateway.setRuntimeContextResolver(request -> source);
            }
            ChatUiChannel chat = ChatUiChannel.create(gateway);
            ChatUiRequest request =
                    new ChatUiRequest(
                            "alice",
                            null,
                            id,
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("child input")
                                            .build()));
            RuntimeContext caller = fromResolver ? null : source;
            Msg result =
                    streaming
                            ? chat.sendStream(request, caller)
                                    .ofType(AgentResultEvent.class)
                                    .map(AgentResultEvent::getResult)
                                    .blockLast(Duration.ofSeconds(5))
                            : chat.send(request, caller).block(Duration.ofSeconds(5));

            assertNotNull(result);
            assertEquals("child-ok", result.getTextContent());
            RuntimeContext effective = childContext.get();
            assertEquals("child-session", effective.getSessionId());
            assertEquals("alice", effective.getUserId());
            assertEquals("acme", effective.get("tenant"));
            assertSame(tenant, effective.get(Tenant.class));
            assertEquals(source.getRunId(), effective.getRunId());
            assertNull(SessionExecution.from(effective));
            assertNull(effective.get(SessionExportSink.CONTEXT_KEY));
            assertNull(effective.get(SessionRecorder.PARENT_KEY));
            assertNotSame(SessionRecorder.from(source), SessionRecorder.from(effective));
            assertNotEquals(
                    source.get(SessionRecorder.TURN_ID_KEY, String.class),
                    effective.get(SessionRecorder.TURN_ID_KEY, String.class));
            assertEquals(
                    "child-session",
                    ((ExecutionIdentity) effective.get(ExecutionIdentity.CONTEXT_KEY)).sessionId());
            assertNotSame(parentState, effective.getAgentState());
            assertEquals("parent-session", source.getSessionId());
            assertSame(parentState, source.getAgentState());
            original.forEach((key, value) -> assertSame(value, source.get(key), key));

            var nextTask = parentSession.submit("continue parent");
            assertNotNull(parentSession.await(nextTask).block(Duration.ofSeconds(5)));
        }
    }

    private HarnessAgent agent(String name, AtomicReference<RuntimeContext> seen) {
        Path agentWorkspace = workspace.resolve(name);
        return HarnessAgent.builder()
                .name(name)
                .model(new MockModel(name + "-ok"))
                .workspace(agentWorkspace)
                .abstractFilesystem(new LocalFilesystem(agentWorkspace))
                .sessionLogStore(new InMemorySessionLogStore())
                .middleware(
                        new MiddlewareBase() {
                            @Override
                            public void onAgentStateReady(
                                    Agent agent,
                                    RuntimeContext context,
                                    AgentState state,
                                    List<Msg> messages) {
                                seen.set(context);
                            }
                        })
                .build();
    }

    private record Tenant(String name) {}
}
