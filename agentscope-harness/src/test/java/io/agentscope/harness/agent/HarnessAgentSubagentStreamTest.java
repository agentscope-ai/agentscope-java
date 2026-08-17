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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
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
 * Verifies that synchronous local subagent invocations forward child {@link Event}s into the
 * parent {@link HarnessAgent#stream(List, StreamOptions, RuntimeContext)} pipeline, tagged with a
 * non-null {@link EventSource} carrying the correct {@code agentId} and {@code path}.
 *
 * <p>Also verifies that the non-streaming {@code call()} path is unaffected: the final reply is
 * returned as a single {@link Msg} with no change in behaviour.
 *
 * <p><b>Model call sequencing:</b> Both parent and child agents share the same {@code Model} mock.
 * The mock is configured with {@code thenReturn} chains so each successive call (parent turn 1 →
 * child → parent turn 2) yields the appropriate {@link ChatResponse}. This mirrors how
 * {@code buildDeclaredFactory} captures {@code this.model} for child agents.
 */
class HarnessAgentSubagentStreamTest {

    @TempDir Path workspace;

    // Each @Test gets a fresh JsonFileAgentStateStore root, preventing the
    // "parent" agent's persisted AgentState from leaking between cases.
    @TempDir Path stateHome;

    private String previousStateHome;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (previousStateHome != null) {
            System.setProperty("agentscope.state.home", previousStateHome);
        } else {
            System.clearProperty("agentscope.state.home");
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** ChatResponse with no tool calls (stop reason). */
    private static ChatResponse stopChunk(String id, String text) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    /** ChatResponse that requests a single tool call and uses "tool_use" stop reason. */
    private static ChatResponse toolCallChunk(String id, String toolName, Map<String, Object> in) {
        // ToolValidator.validateInput uses toolCall.getContent() (raw JSON string) for schema
        // validation. Without a content string the validator sees null input and rejects the call.
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

    // -----------------------------------------------------------------
    // 1. stream() — child events forwarded, tagged with EventSource
    // -----------------------------------------------------------------

    /**
     * Scenario:
     *
     * <ol>
     *   <li>Parent model turn 1 → {@code agent_spawn(researcher, …, timeout_seconds=60)}.
     *   <li>Child (researcher) model → produces a REASONING chunk then a final reply.
     *   <li>Parent model turn 2 → final reply "summary done".
     * </ol>
     *
     * <p>Expected outcome in the collected {@code Flux<Event>}:
     *
     * <ul>
     *   <li>Total events > 1 (child events merged in).
     *   <li>Some events have {@code source.agentId == "researcher"}.
     *   <li>The {@code source.path} contains {@code "researcher"}.
     *   <li>Events without {@code source} exist (parent's own events).
     * </ul>
     */
    @Test
    void stream_localSubagentEventsForwardedToParentFlux() throws Exception {
        String childId = "researcher";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Research specialist
                ---
                You are a researcher.
                """);

        // Shared mock model (parent + child both use it).
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                // Turn 1 (parent): spawn researcher
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
                // Turn 2 (child invocation): reasoning chunk + stop
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "c1",
                                        List.of(TextBlock.builder().text("researching…").build()),
                                        null,
                                        Map.of(),
                                        null),
                                stopChunk("c1", "research complete")))
                // Turn 3 (parent): final summary
                .thenReturn(Flux.just(stopChunk("p2", "summary done")));

        HarnessAgent parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-stream").build();

        List<Event> events =
                parent.stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("start")
                                                .build()),
                                StreamOptions.defaults(),
                                ctx)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertFalse(events.isEmpty(), "at least one event expected");

        // Child events: source != null
        List<Event> childEvents =
                events.stream().filter(e -> e.getSource() != null).collect(Collectors.toList());
        assertFalse(
                childEvents.isEmpty(),
                "expected child events forwarded to parent Flux; got: "
                        + events.stream().map(Event::toString).collect(Collectors.joining(", ")));

        Event firstChild = childEvents.get(0);
        assertEquals(childId, firstChild.getSource().getAgentId(), "agentId mismatch");
        assertTrue(
                firstChild.getSource().getPath().contains(childId),
                "path should contain childId; got: " + firstChild.getSource().getPath());
        assertTrue(firstChild.getSource().getDepth() >= 1, "depth must be >= 1");
        assertNotNull(firstChild.getSource().getAgentKey(), "agentKey must not be null");
        assertNotNull(firstChild.getSource().getSessionId(), "sessionId must not be null");

        // Parent events: source == null
        assertTrue(
                events.stream().anyMatch(e -> e.getSource() == null),
                "expected at least one parent event with source == null");

        // Total > parent-only chunks (parent got 1 stop chunk → 1 REASONING + 1 AGENT_RESULT)
        assertTrue(events.size() > 2, "total events should exceed parent-only count");
    }

    // -----------------------------------------------------------------
    // 2. Event ordering: parent → child → parent
    // -----------------------------------------------------------------

    @Test
    void stream_eventOrdering_parentChildParent() throws Exception {
        String childId = "formatter";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Text formatter
                ---
                Format everything.
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        Map<String, Object> spawnInput =
                Map.of("agent_id", childId, "task", "format data", "timeout_seconds", 60);
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                // Parent first turn has reasoning text AND a tool call
                                new ChatResponse(
                                        "p1",
                                        List.of(
                                                TextBlock.builder().text("will format").build(),
                                                ToolUseBlock.builder()
                                                        .id("tc-p1")
                                                        .name("agent_spawn")
                                                        .input(spawnInput)
                                                        .content(
                                                                io.agentscope.core.util.JsonUtils
                                                                        .getJsonCodec()
                                                                        .toJson(spawnInput))
                                                        .build()),
                                        null,
                                        Map.of(),
                                        "tool_use")))
                // child turn
                .thenReturn(Flux.just(stopChunk("c1", "formatted")))
                // parent final
                .thenReturn(Flux.just(stopChunk("p2", "all done")));

        HarnessAgent parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<Event> events =
                parent.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                StreamOptions.defaults(),
                                RuntimeContext.builder().sessionId("sess-order").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        // Child events must exist
        assertTrue(
                events.stream().anyMatch(e -> e.getSource() != null),
                "child events should be present");

        // Verify interleaving: at least one child event appears before the final AGENT_RESULT
        int firstChildIdx =
                events.stream()
                        .filter(e -> e.getSource() != null)
                        .mapToInt(events::indexOf)
                        .min()
                        .orElse(-1);
        int lastAgentResultIdx =
                events.stream()
                        .filter(e -> e.getType() == EventType.AGENT_RESULT && e.isLast())
                        .mapToInt(events::indexOf)
                        .max()
                        .orElse(-1);

        assertTrue(firstChildIdx >= 0, "child event index should be found");
        assertTrue(lastAgentResultIdx >= 0, "AGENT_RESULT should be found");
        assertTrue(
                firstChildIdx < lastAgentResultIdx,
                "child events should appear before final AGENT_RESULT");
    }

    // -----------------------------------------------------------------
    // 3. call() path — no bus, non-streaming behaviour unchanged
    // -----------------------------------------------------------------

    /**
     * When {@code call()} is used, there is no {@link io.agentscope.core.agent.SubagentEventBus}
     * in the Reactor Context. The subagent executes via the plain blocking path and the parent
     * returns a single final {@link Msg}.
     */
    @Test
    void call_localSubagent_returnsReplyWithoutStreaming() throws Exception {
        String childId = "analyst";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Data analyst
                ---
                You are an analyst.
                """);

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

        HarnessAgent parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        Msg reply =
                parent.call(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-call").build())
                        .block();

        assertNotNull(reply, "reply must not be null");
        assertTrue(
                reply.getTextContent().contains("result obtained"),
                "final reply text mismatch; got: " + reply.getTextContent());
    }

    // -----------------------------------------------------------------
    // 4. EventSource unit tests (no HarnessAgent needed)
    // -----------------------------------------------------------------

    @Test
    void eventSource_withAppendedPath_incrementsDepthAndPath() {
        EventSource root =
                EventSource.builder().agentId("planner").path("main/planner").depth(1).build();

        EventSource child = root.withAppendedPath("executor");

        assertEquals("main/planner/executor", child.getPath());
        assertEquals(2, child.getDepth());
        assertEquals("planner", child.getAgentId()); // agentId copied, not changed
    }

    @Test
    void eventSource_withAppendedPath_whenPathNull_usesSegmentOnly() {
        EventSource root = EventSource.builder().agentId("root").depth(0).build();
        EventSource child = root.withAppendedPath("child");

        assertEquals("child", child.getPath());
        assertEquals(1, child.getDepth());
    }

    @Test
    void event_withSource_preservesOriginalAndIsImmutable() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("hi").build();
        Event original = new Event(EventType.REASONING, msg, true);
        assertNull(original.getSource(), "new Event should have source == null");

        EventSource src = EventSource.builder().agentId("sub").path("main/sub").depth(1).build();
        Event tagged = original.withSource(src);

        // original is not mutated
        assertNull(original.getSource(), "original should remain untouched");
        // tagged carries the source
        assertNotNull(tagged.getSource());
        assertEquals("sub", tagged.getSource().getAgentId());
        assertEquals("main/sub", tagged.getSource().getPath());
        // type / message / isLast are preserved
        assertEquals(EventType.REASONING, tagged.getType());
        assertEquals(msg, tagged.getMessage());
        assertTrue(tagged.isLast());
    }

    @Test
    void event_legacyThreeArgConstructor_sourceSetsNull() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("x").build();
        Event e = new Event(EventType.TOOL_RESULT, msg, false);
        assertNull(e.getSource(), "3-arg constructor should leave source null");
    }

    // -----------------------------------------------------------------
    // 5. Diagnostic: verify agent_spawn appears in the parent toolkit
    // -----------------------------------------------------------------

    @Test
    void toolRegistration_agentSpawnToolIsRegistered() throws Exception {
        String childId = "diag-child";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Diagnostic child
                ---
                Diagnostic subagent.
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("diag-parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        // After the HarnessAgent → ReActAgent unification the inner agent is exposed via
        // getDelegate(); toolkit is reachable through the public getToolkit() accessor.
        io.agentscope.core.tool.Toolkit toolkit = agent.getDelegate().getToolkit();

        List<String> toolNames =
                toolkit.getToolSchemas().stream()
                        .map(io.agentscope.core.model.ToolSchema::getName)
                        .collect(Collectors.toList());
        System.err.println("[DIAG] Registered tool names: " + toolNames);
        assertTrue(
                toolNames.contains("agent_spawn"),
                "agent_spawn must be in toolkit; got: " + toolNames);
    }
}
