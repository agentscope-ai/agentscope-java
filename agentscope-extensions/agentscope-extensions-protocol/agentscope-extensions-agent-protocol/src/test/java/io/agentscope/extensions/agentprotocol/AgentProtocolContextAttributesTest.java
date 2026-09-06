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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * How caller-supplied {@code context.attributes} reach the agent's {@link RuntimeContext}:
 * namespaced by default, promoted to top-level keys only through a {@link
 * RuntimeContextCustomizer}.
 */
class AgentProtocolContextAttributesTest {

    @TempDir Path tempDir;

    private ProtocolTaskRepository taskRepository;
    private final AtomicReference<RuntimeContext> executed = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        taskRepository = new WorkspaceProtocolTaskRepository(tempDir);
    }

    @Test
    void attributesAreNamespacedAndNeverFlattenedByDefault() throws Exception {
        AgentProtocolTaskStore store = store(List.of());

        store.submit("t-1", "worker", "go", contextWithAttributes());
        RuntimeContext ctx = awaitRun(store, "t-1");

        Map<String, Object> attributes =
                ctx.get(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY);
        assertEquals("acme", attributes.get("tenant"));
        assertEquals("INC-1001", attributes.get("ticket_id"));
        assertEquals("hijacked", attributes.get("agentId"));

        assertNull(ctx.get("tenant"));
        assertNull(ctx.get("ticket_id"));
        assertNull(ctx.get("agentId"), "framework keys must not be writable by callers");
    }

    @Test
    void noAttributesLeavesTheKeyUnset() throws Exception {
        AgentProtocolTaskStore store = store(List.of());

        store.submit("t-2", "worker", "go", Map.of("user_id", "u-1"));
        RuntimeContext ctx = awaitRun(store, "t-2");

        assertNull(ctx.get(AgentProtocolConstants.RUNTIME_CONTEXT_ATTRIBUTES_KEY));
        assertEquals("u-1", ctx.getUserId());
        assertEquals("t-2", ctx.getSessionId());
    }

    @Test
    void flattenPromotesAllowedKeysAndRefusesReservedOnes() throws Exception {
        AgentProtocolTaskStore store =
                store(List.of(RuntimeContextCustomizer.flatten("tenant", "agentId")));

        store.submit("t-3", "worker", "go", contextWithAttributes());
        RuntimeContext ctx = awaitRun(store, "t-3");

        assertEquals("acme", ctx.get("tenant"));
        assertNull(ctx.get("ticket_id"), "keys outside the allow-list stay namespaced");
        assertNull(ctx.get("agentId"), "reserved keys are dropped from the allow-list");
    }

    @Test
    void customizersRunInRegistrationOrderAndSeeTheRequest() throws Exception {
        RuntimeContextCustomizer first =
                (request, builder) -> {
                    builder.put("route", "first");
                    builder.put("task", request.taskId());
                };
        RuntimeContextCustomizer second =
                (request, builder) -> builder.put("route", request.attributeString("tenant"));
        AgentProtocolTaskStore store = store(List.of(first, second));

        store.submit("t-4", "worker", "go", contextWithAttributes());
        RuntimeContext ctx = awaitRun(store, "t-4");

        assertEquals("acme", ctx.get("route"), "the later customizer wins");
        assertEquals("t-4", ctx.get("task"));
    }

    @Test
    void agentRequestExposesNestedAttributesOnly() {
        Map<String, Object> context = new HashMap<>();
        context.put("user_id", "u-1");
        context.put("attributes", Map.of("tenant", "acme"));
        AgentRequest request = new AgentRequest("t", "a", "in", "u-1", null, false, context);

        assertEquals(Map.of("tenant", "acme"), request.attributes());
        assertEquals("acme", request.attributeString("tenant"));
        assertNull(request.attributeString("missing"));
        assertEquals("u-1", request.contextString("user_id"));
    }

    @Test
    void agentRequestToleratesMissingOrMalformedAttributes() {
        assertTrue(
                new AgentRequest("t", "a", "in", null, null, false, Map.of("attributes", "oops"))
                        .attributes()
                        .isEmpty());
        assertTrue(
                new AgentRequest("t", "a", "in", null, null, false, null).attributes().isEmpty());
    }

    private static Map<String, Object> contextWithAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tenant", "acme");
        attributes.put("ticket_id", "INC-1001");
        attributes.put("agentId", "hijacked");
        Map<String, Object> context = new HashMap<>();
        context.put("user_id", "u-1");
        context.put("attributes", attributes);
        return context;
    }

    private AgentProtocolTaskStore store(List<RuntimeContextCustomizer> customizers) {
        return new AgentProtocolTaskStore(
                AgentFactory.fixed(recordingAgent()),
                taskRepository,
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties(),
                customizers);
    }

    private HarnessAgent recordingAgent() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            executed.set(invocation.getArgument(1));
                            return Flux.just(
                                    new AgentStartEvent("sess", null, "worker"),
                                    new AgentResultEvent(
                                            Msg.builder()
                                                    .role(MsgRole.ASSISTANT)
                                                    .textContent("done")
                                                    .build()),
                                    new AgentEndEvent(null));
                        });
        return agent;
    }

    private RuntimeContext awaitRun(AgentProtocolTaskStore store, String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!"success".equals(store.snapshot(taskId).get("status"))) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Task " + taskId + " did not finish in time");
            }
            Thread.sleep(25);
        }
        RuntimeContext ctx = executed.get();
        if (ctx == null) {
            throw new AssertionError("Agent was never invoked for task " + taskId);
        }
        return ctx;
    }
}
