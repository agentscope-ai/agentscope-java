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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Prefix-cache contract: the volatile subagent state (agent catalog, async task statuses) rides a
 * synthetic tail message, so the SYSTEM head stays byte-identical across reasoning steps while
 * tasks change underneath.
 */
class SubagentsMiddlewareHeadStabilityTest {

    /** Minimal TaskRepository stub whose visible task set and statuses the test mutates. */
    private static final class TasksRepo implements TaskRepository {
        final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return tasks.get(taskId);
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.copyOf(tasks.values());
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }

    private static SubagentEntry entry(String name, String description) {
        SubagentFactory factory = (rc) -> (Agent) null;
        return new SubagentEntry(name, description, factory);
    }

    private static SubagentEntry entry(SubagentDeclaration declaration) {
        SubagentFactory factory = (rc) -> (Agent) null;
        return new SubagentEntry(
                declaration.getName(), declaration.getDescription(), factory, declaration);
    }

    @Test
    void taskStatusChangesKeepHeadByteStableAndRideTheTailStateMessage() {
        TasksRepo repo = new TasksRepo();
        CompletableFuture<String> future = new CompletableFuture<>();
        repo.tasks.put("task-1", new BackgroundTask("task-1", "worker", future));
        SubagentsMiddleware mw =
                new SubagentsMiddleware(
                        List.of(entry("worker", "Test worker"), entry("searcher", "Searches")),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);

        ReasoningInput running = runRound(mw);
        future.complete("done"); // status flip running -> completed between steps
        ReasoningInput completed = runRound(mw);

        String head1 = running.messages().get(0).getTextContent();
        String head2 = completed.messages().get(0).getTextContent();
        assertEquals(head1, head2, "system head must stay byte-stable while task state changes");
        assertTrue(head1.contains("## Subagents"));
        assertFalse(
                head1.contains("Available agent ids"),
                "the agent catalog is runtime state and must not sit in the head");

        Msg tail1 = tailOf(running);
        Msg tail2 = tailOf(completed);
        assertEquals("subagent_state", tail1.getMetadata().get(Msg.METADATA_REMINDER_KIND));
        assertEquals(Boolean.TRUE, tail1.getMetadata().get(Msg.METADATA_SYNTHETIC));
        assertEquals(MsgRole.USER, tail1.getRole());
        assertTrue(tail1.getTextContent().contains("<RUNTIME_STATE>"));
        assertTrue(tail1.getTextContent().contains("### Available agent ids"));
        assertTrue(tail1.getTextContent().contains("- `worker`: Test worker"));
        assertTrue(tail1.getTextContent().contains("- `searcher`: Searches"));
        assertTrue(tail1.getTextContent().contains("task-1"));
        assertTrue(tail1.getTextContent().contains("status: running"));
        assertTrue(tail2.getTextContent().contains("status: completed"));
    }

    @Test
    void stateMessageSkippedWhenCatalogAndTasksAreEmpty() {
        TasksRepo repo = new TasksRepo();
        SubagentsMiddleware mw =
                new SubagentsMiddleware(
                        List.of(),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);
        ReasoningInput round = runRound(mw);
        // Guidance still prepended (tool semantics do not depend on entries), but no tail state.
        assertTrue(round.messages().get(0).getTextContent().contains("## Subagents"));
        assertNull(
                round.messages().stream()
                        .map(SubagentsMiddlewareHeadStabilityTest::kindOf)
                        .filter("subagent_state"::equals)
                        .findFirst()
                        .orElse(null));
    }

    @Test
    void dynamicDirectoryToggleKeepsHeadByteStable(@TempDir Path workspace) throws Exception {
        DynamicSubagentsMiddleware mw =
                new DynamicSubagentsMiddleware(
                        List.of(),
                        null,
                        workspace,
                        decl -> (rc) -> (Agent) null,
                        new DefaultAgentManager(List.of(), null),
                        null,
                        new TasksRepo());

        ReasoningInput emptyDir = runRound(mw);
        String head = emptyDir.messages().get(0).getTextContent();
        assertTrue(head.contains("## Subagents"));

        Path spec = workspace.resolve("subagents/researcher.md");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "---\ndescription: Does research.\n---\nYou research.\n");
        ReasoningInput populated = runRound(mw);
        assertEquals(
                head,
                populated.messages().get(0).getTextContent(),
                "directory toggle empty -> populated must not change the system head");
        Msg tail = tailOf(populated);
        assertEquals("subagent_state", tail.getMetadata().get(Msg.METADATA_REMINDER_KIND));
        assertTrue(tail.getTextContent().contains("- `researcher`: Does research."));

        Files.delete(spec);
        ReasoningInput emptied = runRound(mw);
        assertEquals(
                head,
                emptied.messages().get(0).getTextContent(),
                "directory toggle populated -> empty must not change the system head");
        assertNull(
                emptied.messages().stream()
                        .map(SubagentsMiddlewareHeadStabilityTest::kindOf)
                        .filter("subagent_state"::equals)
                        .findFirst()
                        .orElse(null));
    }

    @Test
    void stateMessageIncludesTasksWithoutCatalog() {
        Msg state =
                SubagentsMiddleware.buildSubagentStateMsg(
                        List.of(), "\n### Async tasks (current session)\n- task_id: t\n");
        assertNotNull(state);
        assertTrue(state.getTextContent().contains("task_id: t"));
        assertFalse(state.getTextContent().contains("### Available agent ids"));
    }

    @Test
    void tailCatalog_hidesHiddenAndPrimaryOnlyEntries() {
        SubagentDeclaration plain =
                SubagentDeclaration.builder()
                        .name("plain")
                        .description("public agent")
                        .inlineAgentsBody("b")
                        .build();
        SubagentDeclaration hidden =
                SubagentDeclaration.builder()
                        .name("secret")
                        .description("internal use only")
                        .inlineAgentsBody("b")
                        .hidden(true)
                        .build();
        SubagentDeclaration primaryOnly =
                SubagentDeclaration.builder()
                        .name("compaction")
                        .description("Compacts conversations")
                        .inlineAgentsBody("b")
                        .mode(SubagentDeclaration.Mode.PRIMARY)
                        .build();
        List<SubagentEntry> entries = List.of(entry(plain), entry(hidden), entry(primaryOnly));

        Msg state = SubagentsMiddleware.buildSubagentStateMsg(entries, null);

        assertNotNull(state);
        String text = state.getTextContent();
        // Line-prefix matching so a description quoting another entry's name cannot
        // false-match.
        assertTrue(text.contains("- `plain`:"));
        assertFalse(text.contains("- `secret`:"), "hidden agent must not ride the tail catalog");
        assertFalse(
                text.contains("- `compaction`:"),
                "PRIMARY-only declaration must not appear in the spawnable catalog");
        assertFalse(
                SubagentsMiddleware.renderSubagentSection(entries, false)
                        .contains("### Available agent ids"),
                "the guidance section must stay catalog-free");
    }

    private static Object kindOf(Msg msg) {
        return msg.getMetadata() != null ? msg.getMetadata().get(Msg.METADATA_REMINDER_KIND) : null;
    }

    private static Msg tailOf(ReasoningInput round) {
        return round.messages().get(round.messages().size() - 1);
    }

    private static ReasoningInput runRound(HarnessRuntimeMiddleware mw) {
        List<Msg> base =
                List.of(
                        Msg.builder().role(MsgRole.SYSTEM).textContent("Base.").build(),
                        Msg.builder().role(MsgRole.USER).textContent("hello").build());
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        mw.onReasoning(
                        null,
                        null,
                        new ReasoningInput(new ArrayList<>(base), List.of(), null),
                        in -> {
                            forwarded.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        return forwarded.get();
    }
}
