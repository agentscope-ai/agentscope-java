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
package io.agentscope.extensions.aistio.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.aistio.FrameworkAdapter;
import io.agentscope.extensions.aistio.StubAgent;
import io.agentscope.extensions.aistio.model.ContextSnapshot;
import io.agentscope.extensions.aistio.model.MessagePage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentScopeAdapterTest {

    private static final String SESSION = "s-1";

    private static Msg text(MsgRole role, String content) {
        return Msg.builder()
                .role(role)
                .name(role.toString().toLowerCase())
                .content(TextBlock.builder().text(content).build())
                .build();
    }

    private static AgentState stateWith(List<Msg> context) {
        return AgentState.builder().sessionId(SESSION).userId("u-1").context(context).build();
    }

    private static StubAgent agentWith(List<Msg> context) {
        return new StubAgent("agent-1", stateWith(context));
    }

    @Test
    void handlesAgentsAndNothingElse() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();

        assertEquals("agentscope-java", adapter.frameworkName());
        assertTrue(adapter.canHandle(agentWith(List.of())));
        assertFalse(adapter.canHandle("not an agent"));
        assertNotNull(adapter.middleware());
    }

    @Test
    void advertisesContextMessageCommandAbortAndTaskCapabilities() {
        assertEquals(
                java.util.Set.of(
                        FrameworkAdapter.CAP_CONTEXT_QUERY,
                        FrameworkAdapter.CAP_MESSAGE_QUERY,
                        FrameworkAdapter.CAP_SESSION_COMMAND,
                        FrameworkAdapter.CAP_SESSION_ABORT,
                        FrameworkAdapter.CAP_TASK_QUERY,
                        FrameworkAdapter.CAP_PLAN_MODE),
                new AgentScopeAdapter().capabilities());
    }

    @Test
    void extractsContextFromLiveAgentState() {
        StubAgent agent =
                agentWith(List.of(text(MsgRole.USER, "what is 2+2"), text(MsgRole.ASSISTANT, "4")));
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agent, event -> {});

        ContextSnapshot snapshot = adapter.extractContext(SESSION).block();

        assertNotNull(snapshot);
        assertEquals(SESSION, snapshot.getSessionId());
        assertEquals("agentscope-java", snapshot.getFramework());
        assertEquals(2, snapshot.getMessages().size());
        assertEquals("user", snapshot.getMessages().get(0).role());
        assertEquals("what is 2+2", snapshot.getMessages().get(0).content());
        assertEquals("assistant", snapshot.getMessages().get(1).role());
        assertFalse(snapshot.isCompacted());
    }

    @Test
    void contextIsEmptyWhenTheSessionHasNoState() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(new StubAgent("agent-1", null), event -> {});

        ContextSnapshot snapshot = adapter.extractContext("unknown").block();

        assertNotNull(snapshot);
        assertTrue(snapshot.getMessages().isEmpty());
    }

    @Test
    void compactionSummaryInStateMarksTheContextCompacted() {
        Msg summary =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("__compaction_summary__")
                        .content(TextBlock.builder().text("earlier turns, summarized").build())
                        .build();
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(List.of(summary, text(MsgRole.USER, "and now?"))), event -> {});

        ContextSnapshot snapshot = adapter.extractContext(SESSION).block();

        assertNotNull(snapshot);
        assertTrue(snapshot.isCompacted());
        assertEquals("earlier turns, summarized", snapshot.getCompactionSummary());
        assertTrue(snapshot.getMessages().get(0).isCompaction());
        assertFalse(snapshot.getMessages().get(1).isCompaction());
    }

    @Test
    void contextTokensUseLatestInputAsWindowOccupancy() {
        Msg first =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(TextBlock.builder().text("hi").build())
                        .usage(ChatUsage.builder().inputTokens(100).outputTokens(20).build())
                        .build();
        Msg second =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(TextBlock.builder().text("again").build())
                        .usage(ChatUsage.builder().inputTokens(250).outputTokens(30).build())
                        .build();
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(
                agentWith(
                        List.of(
                                text(MsgRole.USER, "hi"),
                                first,
                                text(MsgRole.USER, "again"),
                                second)),
                event -> {});

        // Must NOT sum usages (100+20+250+30); window ≈ latest inputTokens.
        assertEquals(250, adapter.extractContext(SESSION).block().getTotalTokens());
    }

    @Test
    void toolTrafficBecomesToolRoleAndToolFieldsInHistory() {
        Msg call =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("search")
                                        .input(Map.of("q", "agentscope"))
                                        .build())
                        .build();
        // AgentScope carries tool results on ASSISTANT messages; the adapter normalizes the
        // reported role to "tool".
        Msg result =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("tool")
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("search")
                                        .output(TextBlock.builder().text("3 hits").build())
                                        .build())
                        .build();
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(List.of(call, result)), event -> {});

        MessagePage page = adapter.listMessages(SESSION, 0, 100).block();

        assertNotNull(page);
        assertEquals(2, page.total());
        assertEquals("search", page.messages().get(0).toolName());
        assertEquals(Map.of("q", "agentscope"), page.messages().get(0).toolInput());
        assertEquals("tool", page.messages().get(1).role());
        assertEquals("3 hits", page.messages().get(1).toolOutput());
    }

    @Test
    void historyPaginates() {
        List<Msg> context = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            context.add(text(MsgRole.USER, "m" + i));
        }
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(context), event -> {});

        MessagePage page = adapter.listMessages(SESSION, 4, 3).block();

        assertNotNull(page);
        assertEquals(10, page.total());
        assertEquals(4, page.offset());
        assertEquals(3, page.messages().size());
        assertEquals("m4", page.messages().get(0).content());
        assertEquals("m6", page.messages().get(2).content());
    }

    @Test
    void terminateInterruptsTheAgent() {
        StubAgent agent = agentWith(List.of());
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agent, event -> {});

        adapter.handleCommand(SESSION, FrameworkAdapter.COMMAND_TERMINATE, null).block();

        assertEquals(1, agent.interruptCount());
    }

    @Test
    void abortInterruptsTheAgentLikeTerminate() {
        StubAgent agent = agentWith(List.of());
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agent, event -> {});

        adapter.handleCommand(SESSION, FrameworkAdapter.COMMAND_ABORT, null).block();

        assertEquals(1, agent.interruptCount());
    }

    @Test
    void listTasksMapsAgentStateTasks() {
        io.agentscope.core.state.Task task =
                io.agentscope.core.state.Task.builder()
                        .id("task-1")
                        .subject("look up order")
                        .description("fetch order details")
                        .state(io.agentscope.core.state.Task.State.IN_PROGRESS)
                        .owner("main")
                        .blockedBy(List.of())
                        .metadata(Map.of("node", "lookup"))
                        .createdAt("2026-06-26T10:35:00Z")
                        .build();
        AgentState state =
                AgentState.builder()
                        .sessionId(SESSION)
                        .userId("u-1")
                        .context(List.of())
                        .tasksContext(new io.agentscope.core.state.TaskContextState(List.of(task)))
                        .build();
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(new StubAgent("agent-1", state), event -> {});

        List<Map<String, Object>> tasks = adapter.listTasks(SESSION).block();

        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        assertEquals("task-1", tasks.get(0).get("id"));
        assertEquals("look up order", tasks.get(0).get("subject"));
        assertEquals("in_progress", tasks.get(0).get("state"));
        assertEquals("main", tasks.get(0).get("owner"));
        assertEquals(List.of(), tasks.get(0).get("blockedBy"));
        assertEquals("fetch order details", tasks.get(0).get("description"));
        assertEquals("2026-06-26T10:35:00Z", tasks.get(0).get("updatedAt"));
        assertEquals(Map.of("node", "lookup"), tasks.get(0).get("frameworkMeta"));
    }

    @Test
    void listTasksIsEmptyWhenStateHasNoTasks() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(List.of()), event -> {});

        List<Map<String, Object>> tasks = adapter.listTasks(SESSION).block();

        assertNotNull(tasks);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void compressIsUnsupportedWithoutACompactor() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(List.of()), event -> {});

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        adapter.handleCommand(SESSION, FrameworkAdapter.COMMAND_COMPRESS, null)
                                .block());
    }

    @Test
    void compressDelegatesToTheCompactorAndReportsIt() {
        AtomicReference<String> compacted = new AtomicReference<>();
        Msg summary =
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("__compaction_summary__")
                        .content(TextBlock.builder().text("summarized").build())
                        .build();
        StubAgent agent = agentWith(List.of(summary));
        AgentScopeAdapter adapter =
                new AgentScopeAdapter(
                        (sessionId, state) -> {
                            compacted.set(sessionId);
                            return Mono.empty();
                        });
        List<SessionEvent> events = new ArrayList<>();
        adapter.attach(agent, events::add);

        adapter.handleCommand(SESSION, FrameworkAdapter.COMMAND_COMPRESS, null).block();

        assertEquals(SESSION, compacted.get());
        assertEquals(1, events.size());
        assertEquals(SessionEvent.COMPACTION, events.get(0).getEventType());
        assertEquals("summarized", events.get(0).getContent());
    }

    @Test
    void unknownCommandsAreRejected() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(List.of()), event -> {});

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.handleCommand(SESSION, "reboot", null).block());
    }

    @Test
    void commandsFailClearlyBeforeAttach() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();

        assertThrows(
                IllegalStateException.class,
                () ->
                        adapter.handleCommand(SESSION, FrameworkAdapter.COMMAND_TERMINATE, null)
                                .block());
    }

    @Test
    void detachStopsEventDelivery() {
        List<SessionEvent> events = new ArrayList<>();
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(agentWith(List.of()), events::add);

        adapter.publish(SessionEvent.builder(SESSION, SessionEvent.MESSAGE).build());
        adapter.detach();
        adapter.publish(SessionEvent.builder(SESSION, SessionEvent.MESSAGE).build());

        assertEquals(1, events.size());
    }

    @Test
    void aFailingSinkNeverEscapesIntoTheAgent() {
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.attach(
                agentWith(List.of()),
                event -> {
                    throw new IllegalStateException("control plane is down");
                });

        // Bypass principle: reporting failures stay inside the adapter.
        adapter.publish(SessionEvent.builder(SESSION, SessionEvent.MESSAGE).build());
    }
}
