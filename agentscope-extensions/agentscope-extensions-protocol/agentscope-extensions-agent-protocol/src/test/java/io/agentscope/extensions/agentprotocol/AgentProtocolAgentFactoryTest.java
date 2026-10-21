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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * {@link AgentFactory} routing: the factory sees the submission context (including custom keys)
 * and decides which {@link HarnessAgent} runs each task.
 */
class AgentProtocolAgentFactoryTest {

    @TempDir Path tempDir;

    private ProtocolTaskRepository taskRepository;
    private HarnessAgent researcher;
    private HarnessAgent writer;
    private final List<AgentRequest> seenRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        taskRepository = new WorkspaceProtocolTaskRepository(tempDir);
        researcher = agentReplying("researched");
        writer = agentReplying("written");
    }

    @Test
    void factoryRoutesByAgentIdAndSeesCustomContextKeys() throws Exception {
        AgentProtocolTaskStore store = storeWithRoutingFactory();

        Map<String, Object> context = new HashMap<>();
        context.put("user_id", "u-1");
        context.put("parent_session_id", "sess-parent");
        context.put("tenant", "acme");
        context.put("ticket_id", "INC-1001");

        store.submit("t-writer", "writer", "draft the summary", context);
        awaitCondition(() -> "success".equals(store.snapshot("t-writer").get("status")), 5_000);

        assertEquals("written", store.snapshot("t-writer").get("result"));
        assertEquals(1, seenRequests.size());

        AgentRequest req = seenRequests.get(0);
        assertEquals("t-writer", req.taskId());
        assertEquals("writer", req.agentId());
        assertEquals("draft the summary", req.input());
        assertEquals("u-1", req.userId());
        assertEquals("sess-parent", req.parentSessionId());
        assertFalse(req.resume());
        assertEquals("acme", req.contextString("tenant"));
        assertEquals("INC-1001", req.contextValue("ticket_id"));
    }

    @Test
    void factoryPicksDifferentAgentsPerTask() throws Exception {
        AgentProtocolTaskStore store = storeWithRoutingFactory();

        store.submit("t-a", "researcher", "dig in", Map.of());
        awaitCondition(() -> "success".equals(store.snapshot("t-a").get("status")), 5_000);
        store.submit("t-b", "writer", "write up", Map.of());
        awaitCondition(() -> "success".equals(store.snapshot("t-b").get("status")), 5_000);

        assertEquals("researched", store.snapshot("t-a").get("result"));
        assertEquals("written", store.snapshot("t-b").get("result"));
    }

    @Test
    void fixedFactoryIgnoresRequest() {
        AgentFactory factory = AgentFactory.fixed(researcher);
        assertSame(
                researcher,
                factory.create(
                        new AgentRequest("t", "anything", "in", null, null, false, Map.of())));
    }

    @Test
    void agentRequestCopiesContextDefensively() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("tenant", "acme");
        AgentRequest req = new AgentRequest("t", "a", "in", null, null, false, mutable);

        mutable.put("tenant", "changed");

        assertEquals("acme", req.contextString("tenant"));
        assertTrue(new AgentRequest("t", "a", "in", null, null, false, null).context().isEmpty());
    }

    private AgentProtocolTaskStore storeWithRoutingFactory() {
        AgentFactory factory =
                request -> {
                    seenRequests.add(request);
                    return "writer".equals(request.agentId()) ? writer : researcher;
                };
        return new AgentProtocolTaskStore(
                factory,
                taskRepository,
                new AgentProtocolTaskEventBus(),
                new AgentProtocolProperties());
    }

    private static HarnessAgent agentReplying(String text) {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new AgentStartEvent("sess", null, "worker"),
                                new AgentResultEvent(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .textContent(text)
                                                .build()),
                                new AgentEndEvent(null)));
        return agent;
    }

    private static void awaitCondition(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(25);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }
}
