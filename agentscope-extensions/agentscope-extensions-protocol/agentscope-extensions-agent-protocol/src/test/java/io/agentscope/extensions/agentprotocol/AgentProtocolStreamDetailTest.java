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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventCodec;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * What a subscriber receives per {@code context.detail} level, end to end through the task store's
 * event bus.
 */
class AgentProtocolStreamDetailTest {

    @TempDir Path tempDir;

    private ProtocolTaskRepository taskRepository;
    private HarnessAgent agent;

    @BeforeEach
    void setUp() {
        taskRepository = new WorkspaceProtocolTaskRepository(tempDir);
        agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.fromIterable(agentRun()));
    }

    /** A run touching a wire-typed event, a delta, and several passthrough-only events. */
    private static List<AgentEvent> agentRun() {
        return List.of(
                new AgentStartEvent("sess", "reply", "worker"),
                new TextBlockStartEvent("reply", "b1"),
                new TextBlockDeltaEvent("reply", "b1", "hello"),
                new TextBlockEndEvent("reply", "b1"),
                new ToolResultTextDeltaEvent("reply", "call-1", "read_file", "file contents"),
                new ModelCallEndEvent("reply", new ChatUsage(10, 20, 0, 0.5)),
                new AgentResultEvent(
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build()),
                new AgentEndEvent("reply"));
    }

    @Test
    void statusLevelCarriesLifecycleOnly() {
        Set<RemoteEventType> types = typesFor("status", "t-status");

        assertTrue(types.contains(RemoteEventType.RUN_STARTED));
        assertTrue(types.contains(RemoteEventType.RUN_FINISHED));
        assertFalse(types.contains(RemoteEventType.TEXT_DELTA));
        assertFalse(types.contains(RemoteEventType.AGENT_EVENT));
    }

    @Test
    void fullLevelAddsDeltasButNotPassthrough() {
        Set<RemoteEventType> types = typesFor("full", "t-full");

        assertTrue(types.contains(RemoteEventType.TEXT_DELTA));
        assertFalse(
                types.contains(RemoteEventType.AGENT_EVENT),
                "full keeps the pre-existing volume for deployments that never asked for more");
    }

    @Test
    void verboseLevelForwardsEveryEvent() {
        List<RemoteAgentEvent> events = collect("verbose", "t-verbose");

        Set<String> eventTypes =
                events.stream()
                        .map(RemoteAgentEvent::getEventType)
                        .filter(Objects::nonNull) // the server's own STATUS event has no source
                        // type
                        .collect(Collectors.toUnmodifiableSet());
        assertTrue(eventTypes.contains("TEXT_BLOCK_START"), eventTypes.toString());
        assertTrue(eventTypes.contains("TEXT_BLOCK_END"), eventTypes.toString());
        assertTrue(eventTypes.contains("TOOL_RESULT_TEXT_DELTA"), eventTypes.toString());
        assertTrue(eventTypes.contains("MODEL_CALL_END"), eventTypes.toString());
        assertTrue(eventTypes.contains("AGENT_RESULT"), eventTypes.toString());

        AgentEvent toolOutput =
                events.stream()
                        .filter(e -> "TOOL_RESULT_TEXT_DELTA".equals(e.getEventType()))
                        .findFirst()
                        .flatMap(RemoteEventCodec::toAgentEvent)
                        .orElseThrow();
        assertEquals("file contents", ((ToolResultTextDeltaEvent) toolOutput).getDelta());
    }

    private Set<RemoteEventType> typesFor(String detail, String taskId) {
        return collect(detail, taskId).stream()
                .map(RemoteAgentEvent::getType)
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<RemoteAgentEvent> collect(String detail, String taskId) {
        AgentProtocolTaskEventBus bus = new AgentProtocolTaskEventBus();
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent),
                        taskRepository,
                        bus,
                        new AgentProtocolProperties());

        Flux<RemoteAgentEvent> subscription = bus.subscribe(taskId, 0L);
        store.submit(taskId, "worker", "go", Map.of("detail", detail));
        return subscription.take(Duration.ofSeconds(5)).collectList().block();
    }
}
