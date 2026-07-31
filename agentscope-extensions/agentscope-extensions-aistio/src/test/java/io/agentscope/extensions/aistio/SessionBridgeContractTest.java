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
package io.agentscope.extensions.aistio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.model.SessionEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionBridgeContractTest {

    private static final String SESSION = "sess-1";

    private SessionBridge bridgeWith(AgentScopeAdapter adapter, StubAgent agent) {
        SessionBridge bridge =
                new SessionBridge(
                        AistioConfig.builder("test-agent")
                                .startHttp(false)
                                .startGrpc(false)
                                .build());
        bridge.attach(agent, adapter);
        return bridge;
    }

    @Test
    void sessionsDeriveBusyFromPhaseAndIncludeModelMaxTokens() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        SessionBridge bridge = bridgeWith(adapter, new StubAgent("a1", null));

        bridge.describeSession(SESSION, "sys", List.of(), 128000, "qwen-max");

        Map<String, Object> session = bridge.sessions().get(0);
        assertEquals(SESSION, session.get("id"));
        assertEquals("idle", session.get("phase"));
        assertEquals(false, session.get("busy"));
        assertEquals("qwen-max", session.get("model"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tokenUsage = (Map<String, Object>) session.get("tokenUsage");
        assertEquals(128000, tokenUsage.get("maxTokens"));

        bridge.onEvent(SessionEvent.builder(SESSION, SessionEvent.SESSION_START).build());
        assertEquals("active", bridge.sessions().get(0).get("phase"));
        assertEquals(true, bridge.sessions().get(0).get("busy"));

        bridge.setBusy(SESSION, false);
        assertEquals("idle", bridge.sessions().get(0).get("phase"));
        assertEquals(false, bridge.sessions().get(0).get("busy"));
    }

    @Test
    void sessionStateIncludesBusyModelPhaseAndTaskSummary() {
        Task task =
                Task.builder()
                        .id("t1")
                        .subject("do work")
                        .description("desc")
                        .state(Task.State.PENDING)
                        .build();
        AgentState state =
                AgentState.builder()
                        .sessionId(SESSION)
                        .userId("u1")
                        .context(List.of())
                        .tasksContext(new TaskContextState(List.of(task)))
                        .build();
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        SessionBridge bridge = bridgeWith(adapter, new StubAgent("a1", state));
        bridge.describeSession(SESSION, null, null, 8000, "gpt-test");
        bridge.onEvent(SessionEvent.builder(SESSION, SessionEvent.SESSION_START).build());

        Map<String, Object> stateJson = bridge.sessionState(SESSION);

        assertEquals(SESSION, stateJson.get("id"));
        assertEquals("active", stateJson.get("phase"));
        assertEquals(true, stateJson.get("busy"));
        assertEquals("gpt-test", stateJson.get("model"));
        assertNotNull(stateJson.get("startedAt"));
        assertNotNull(stateJson.get("lastActiveAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tokenUsage = (Map<String, Object>) stateJson.get("tokenUsage");
        assertEquals(8000, tokenUsage.get("maxTokens"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) stateJson.get("taskSummary");
        assertEquals(1, summary.get("total"));
        assertEquals(1, summary.get("pending"));
    }

    @Test
    void abortClearsBusyAndTasksEndpointReturnsMappedTasks() {
        Task task =
                Task.builder()
                        .id("t1")
                        .subject("ship it")
                        .description("desc")
                        .state(Task.State.COMPLETED)
                        .owner("main")
                        .build();
        AgentState state =
                AgentState.builder()
                        .sessionId(SESSION)
                        .userId("u1")
                        .context(List.of())
                        .tasksContext(new TaskContextState(List.of(task)))
                        .build();
        StubAgent agent = new StubAgent("a1", state);
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        SessionBridge bridge = bridgeWith(adapter, agent);
        bridge.describeSession(SESSION, null, null, 0, null);
        bridge.setBusy(SESSION, true);

        bridge.abort(SESSION);

        assertEquals(1, agent.interruptCount());
        assertEquals(false, bridge.sessions().get(0).get("busy"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks =
                (List<Map<String, Object>>) bridge.tasks(SESSION).get("tasks");
        assertEquals(1, tasks.size());
        assertEquals("t1", tasks.get(0).get("id"));
        assertEquals("completed", tasks.get(0).get("state"));
        assertEquals(List.of(), tasks.get(0).get("blockedBy"));
    }

    @Test
    void capabilitiesIncludeAbortAndTaskQuery() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        SessionBridge bridge = bridgeWith(adapter, new StubAgent("a1", null));

        assertTrue(bridge.capabilities().contains(FrameworkAdapter.CAP_SESSION_ABORT));
        assertTrue(bridge.capabilities().contains(FrameworkAdapter.CAP_TASK_QUERY));
        assertTrue(bridge.capabilities().contains(FrameworkAdapter.CAP_PLAN_MODE));
    }
}
