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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies that synchronous local subagent invocations forward child {@link AgentEvent}s into the
 * parent's {@link HarnessAgent#streamEvents} pipeline, tagged with a non-null
 * {@link AgentEvent#getSource() source} path containing the child's agent ID.
 *
 * <p>Mirrors {@link HarnessAgentSubagentStreamTest} but exercises the {@code streamEvents()}
 * ({@code Flux<AgentEvent>}) path instead of the deprecated {@code stream()} ({@code Flux<Event>})
 * path.
 */
class HarnessAgentSubagentStreamEventsTest {

    @TempDir Path workspace;
    @TempDir Path stateHome;

    private String previousStateHome;
    private HarnessAgent parent;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void tearDown() {
        try {
            if (parent != null) {
                parent.close();
            }
        } finally {
            if (previousStateHome != null) {
                System.setProperty("agentscope.state.home", previousStateHome);
            } else {
                System.clearProperty("agentscope.state.home");
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static ChatResponse stopChunk(String id, String text) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static ChatResponse toolCallChunk(String id, String toolName, Map<String, Object> in) {
        String contentJson = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(in);
        ToolUseBlock tc =
                ToolUseBlock.builder()
                        .id("tc-" + id)
                        .name(toolName)
                        .input(in)
                        .content(contentJson)
                        .build();
        return new ChatResponse(id, List.of(tc), null, Map.of(), "tool_use");
    }

    private void writeSubagentSpec(String childId, String description, String body)
            throws Exception {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                "---\ndescription: " + description + "\n---\n" + body + "\n");
    }

    // -----------------------------------------------------------------
    // 1. streamEvents() — child AgentEvents forwarded with source tag
    // -----------------------------------------------------------------

    @Test
    void streamEvents_childEventsForwardedWithSource() throws Exception {
        String childId = "researcher";
        writeSubagentSpec(childId, "Research specialist", "You are a researcher.");

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "research X",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "c1",
                                        List.of(TextBlock.builder().text("researching…").build()),
                                        null,
                                        Map.of(),
                                        null),
                                stopChunk("c1", "research complete")))
                .thenReturn(Flux.just(stopChunk("p2", "summary done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-stream-events").build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("start")
                                                .build()),
                                ctx)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertFalse(events.isEmpty());

        // Child events: source != null, contains childId
        List<AgentEvent> childEvents =
                events.stream().filter(e -> e.getSource() != null).collect(Collectors.toList());
        assertFalse(
                childEvents.isEmpty(),
                "expected child events with source tag; got types: "
                        + events.stream()
                                .map(e -> e.getType() + "(src=" + e.getSource() + ")")
                                .collect(Collectors.joining(", ")));

        for (AgentEvent childEvent : childEvents) {
            assertTrue(
                    childEvent.getSource().contains(childId),
                    "source should contain childId; got: " + childEvent.getSource());
        }

        // Parent events: source == null
        assertTrue(
                events.stream().anyMatch(e -> e.getSource() == null),
                "expected at least one parent event with source == null");
    }

    // -----------------------------------------------------------------
    // 2. Child AGENT_START / AGENT_END bookends present with source
    // -----------------------------------------------------------------

    @Test
    void streamEvents_childAgentStartAndEndEmittedWithSource() throws Exception {
        String childId = "formatter";
        writeSubagentSpec(childId, "Text formatter", "Format everything.");

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "format data",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "formatted")))
                .thenReturn(Flux.just(stopChunk("p2", "all done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-bookends").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        // Child AGENT_START with source
        List<AgentEvent> childStarts =
                events.stream()
                        .filter(
                                e ->
                                        e.getType() == AgentEventType.AGENT_START
                                                && e.getSource() != null)
                        .collect(Collectors.toList());
        assertFalse(childStarts.isEmpty(), "expected child AGENT_START with source");
        assertTrue(childStarts.get(0).getSource().contains(childId));

        // Child AGENT_END with source
        List<AgentEvent> childEnds =
                events.stream()
                        .filter(
                                e ->
                                        e.getType() == AgentEventType.AGENT_END
                                                && e.getSource() != null)
                        .collect(Collectors.toList());
        assertFalse(childEnds.isEmpty(), "expected child AGENT_END with source");
        assertTrue(childEnds.get(0).getSource().contains(childId));
    }

    // -----------------------------------------------------------------
    // 3. Event ordering: parent AGENT_START → child events → parent AGENT_END
    // -----------------------------------------------------------------

    @Test
    void streamEvents_eventOrdering_childBetweenParentStartAndEnd() throws Exception {
        String childId = "analyst";
        writeSubagentSpec(childId, "Data analyst", "You are an analyst.");

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "analyse data",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "analysis complete")))
                .thenReturn(Flux.just(stopChunk("p2", "result obtained")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-order").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        // First event should be parent AGENT_START (source == null)
        assertEquals(AgentEventType.AGENT_START, events.get(0).getType());
        assertNull(events.get(0).getSource(), "first event should be parent AGENT_START");

        // Last event should be parent AGENT_END (source == null)
        AgentEvent lastEvent = events.get(events.size() - 1);
        assertEquals(AgentEventType.AGENT_END, lastEvent.getType());
        assertNull(lastEvent.getSource(), "last event should be parent AGENT_END");

        // Child events should appear between first and last
        int firstChildIdx =
                events.stream()
                        .filter(e -> e.getSource() != null)
                        .mapToInt(events::indexOf)
                        .min()
                        .orElse(-1);
        assertTrue(firstChildIdx > 0, "child events should appear after parent AGENT_START");
        assertTrue(
                firstChildIdx < events.size() - 1,
                "child events should appear before parent AGENT_END");
    }

    // -----------------------------------------------------------------
    // 4. Parent-only events have source == null
    // -----------------------------------------------------------------

    @Test
    void streamEvents_parentEventsHaveNullSource() throws Exception {
        String childId = "helper";
        writeSubagentSpec(childId, "Helper", "You are a helper.");

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "help with X",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "helped")))
                .thenReturn(Flux.just(stopChunk("p2", "done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-null-source").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        List<AgentEvent> parentEvents =
                events.stream().filter(e -> e.getSource() == null).collect(Collectors.toList());
        assertFalse(parentEvents.isEmpty());

        // Parent AGENT_START and AGENT_END should both have null source
        assertTrue(
                parentEvents.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_START),
                "parent AGENT_START should have null source");
        assertTrue(
                parentEvents.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_END),
                "parent AGENT_END should have null source");
    }
}
