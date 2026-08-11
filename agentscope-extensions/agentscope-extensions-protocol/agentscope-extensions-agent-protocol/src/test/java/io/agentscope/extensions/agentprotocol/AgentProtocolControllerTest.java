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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;

class AgentProtocolControllerTest {

    private AgentProtocolTaskStore store;
    private AgentProtocolProperties properties;
    private AgentProtocolController controller;

    @BeforeEach
    void setUp() {
        store = mock(AgentProtocolTaskStore.class);
        properties = new AgentProtocolProperties();
        properties.setStreamingEnabled(true);
        properties.setSseTimeoutMs(2_000L);
        controller = new AgentProtocolController(store, properties);
    }

    @Test
    void submit_passesContextToStore() {
        Map<String, Object> context = Map.of("user_id", "u1", "detail", "full");
        Map<String, Object> body =
                Map.of("task_id", "t1", "agent_id", "worker", "input", "hello", "context", context);

        ResponseEntity<Map<String, Object>> resp = controller.submit(body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("t1", resp.getBody().get("task_id"));
        assertEquals("pending", resp.getBody().get("status"));
        verify(store).submit(eq("t1"), eq("worker"), eq("hello"), eq(context));
    }

    @Test
    void submit_rejectsMissingTaskId() {
        ResponseEntity<Map<String, Object>> resp =
                controller.submit(Map.of("agent_id", "a", "input", "x"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody().get("error")).contains("task_id"));
    }

    @Test
    void resume_parsesCamelAndSnakeCaseDecisions() {
        Map<String, Object> body =
                Map.of(
                        "decisions",
                        List.of(
                                Map.of("toolCallId", "tc1", "approved", true),
                                Map.of("tool_call_id", "tc2", "approved", "false")));

        ResponseEntity<Map<String, Object>> resp = controller.resume("t-hitl", body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("running", resp.getBody().get("status"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RemoteConfirmDecision>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).resume(eq("t-hitl"), captor.capture());

        List<RemoteConfirmDecision> decisions = captor.getValue();
        assertEquals(2, decisions.size());
        assertEquals("tc1", decisions.get(0).getToolCallId());
        assertTrue(decisions.get(0).isApproved());
        assertEquals("tc2", decisions.get(1).getToolCallId());
        assertFalse(decisions.get(1).isApproved());
    }

    @Test
    void resume_returnsConflictWhenStoreRejects() {
        doThrow(new IllegalStateException("task is not awaiting confirmation: t1"))
                .when(store)
                .resume(anyString(), anyList());

        ResponseEntity<Map<String, Object>> resp =
                controller.resume("t1", Map.of("decisions", List.of()));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody().get("error"));
    }

    @Test
    void events_returnsSseFromEventBus() {
        AgentProtocolTaskEventBus bus = new AgentProtocolTaskEventBus();
        when(store.eventBus()).thenReturn(bus);

        RemoteAgentEvent published = new RemoteAgentEvent();
        published.setType(RemoteEventType.TEXT_DELTA);
        published.setText("hi");
        bus.publish("t-sse", published);

        List<ServerSentEvent<String>> events =
                controller
                        .events("t-sse", null, null)
                        .take(1)
                        .collectList()
                        .block(Duration.ofSeconds(3));

        assertEquals(1, events.size());
        assertEquals("1", events.get(0).id());
        assertEquals(RemoteEventType.TEXT_DELTA.name(), events.get(0).event());
        assertTrue(events.get(0).data().contains("\"text\":\"hi\""));
    }

    @Test
    void events_honorsFromSeqQueryParam() {
        AgentProtocolTaskEventBus bus = new AgentProtocolTaskEventBus();
        when(store.eventBus()).thenReturn(bus);

        bus.publish("t-from", event(RemoteEventType.RUN_STARTED));
        bus.publish("t-from", event(RemoteEventType.TEXT_DELTA));
        bus.publish("t-from", event(RemoteEventType.STATUS));

        List<ServerSentEvent<String>> events =
                controller
                        .events("t-from", 1L, null)
                        .take(2)
                        .collectList()
                        .block(Duration.ofSeconds(3));

        assertEquals(2, events.size());
        assertEquals("2", events.get(0).id());
        assertEquals("3", events.get(1).id());
    }

    @Test
    void events_errorsWhenStreamingDisabled() {
        properties.setStreamingEnabled(false);
        controller = new AgentProtocolController(store, properties);

        AtomicReference<Throwable> error = new AtomicReference<>();
        controller
                .events("t1", null, null)
                .collectList()
                .doOnError(error::set)
                .onErrorComplete()
                .block(Duration.ofSeconds(2));

        assertNotNull(error.get());
        assertInstanceOf(IllegalStateException.class, error.get());
        assertTrue(error.get().getMessage().contains("streaming is disabled"));
    }

    private static RemoteAgentEvent event(RemoteEventType type) {
        RemoteAgentEvent e = new RemoteAgentEvent();
        e.setType(type);
        return e;
    }
}
