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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link AgentSpawnTool#deterministicHash} — the deterministic key derivation used when
 * {@code SubagentDeclaration.persistSession = true}.
 */
class AgentSpawnToolKeyTest {

    @Test
    void runtimeContextManagerIsUsedForSpawnAndSend() {
        ReActAgent child =
                ReActAgent.builder()
                        .name("child")
                        .sysPrompt("child")
                        .model(replyingModel())
                        .build();

        DefaultAgentManager defaultManager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("default-agent", "default", rc -> child)), null);
        SubagentDeclaration scopedDeclaration =
                SubagentDeclaration.builder()
                        .name("scoped-agent")
                        .description("scoped")
                        .inlineAgentsBody("scoped")
                        .persistSession(true)
                        .build();
        DefaultAgentManager scopedManager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "scoped-agent", "scoped", rc -> child, scopedDeclaration)),
                        null);
        NoopTaskRepository repository = new NoopTaskRepository();
        AgentSpawnTool tool = new AgentSpawnTool(defaultManager, repository, 0);

        String defaultSpawn =
                tool.agentSpawn(
                                RuntimeContext.empty(),
                                null,
                                "default-agent",
                                "ignored",
                                null,
                                1,
                                null)
                        .block();
        assertTrue(defaultSpawn.contains("agent_id: default-agent"));

        RuntimeContext scopedContext =
                RuntimeContext.builder().sessionId("s1").userId("u1").build();
        scopedContext.put(AgentSpawnTool.CTX_AGENT_MANAGER, scopedManager);
        String persistentSpawn =
                tool.agentSpawn(
                                scopedContext,
                                null,
                                "scoped-agent",
                                "first task",
                                "scoped",
                                0,
                                null)
                        .block();
        assertTrue(persistentSpawn.contains("agent_id: scoped-agent"));
        assertEquals("done", repository.runLatestLocalTask());

        String key =
                persistentSpawn.lines().findFirst().orElseThrow().substring("agent_key: ".length());
        String sent = tool.agentSend(scopedContext, null, key, null, "follow-up", 1).block();
        assertTrue(sent.contains("done"));

        String backgroundSend =
                tool.agentSend(scopedContext, null, key, null, "background", 0).block();
        assertTrue(backgroundSend.contains("task_id:"));
        assertEquals("done", repository.runLatestLocalTask());

        AgentState restoredState = AgentState.builder().build();
        String restoredKey = "agent:scoped-agent:restored";
        restoredState
                .getToolContext()
                .putSpawnEntry(
                        restoredKey,
                        new ToolContextState.SpawnEntry(
                                restoredKey, "scoped-agent", "sub-restored", null, 1));
        AgentSpawnTool restoreTool = new AgentSpawnTool(defaultManager, repository, 0);
        String restored =
                restoreTool
                        .agentSend(scopedContext, restoredState, restoredKey, null, "restore", 1)
                        .block();
        assertTrue(restored.contains("done"));
    }

    private static Model replyingModel() {
        return new Model() {
            @Override
            public String getModelName() {
                return "test-model";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        new ChatResponse(
                                "test-model",
                                List.of(TextBlock.builder().text("done").build()),
                                null,
                                java.util.Map.of(),
                                "stop"));
            }
        };
    }

    private static final class NoopTaskRepository implements TaskRepository {

        private TaskRunSpec latestSpec;

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return null;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            latestSpec = spec;
            return null;
        }

        String runLatestLocalTask() {
            return ((TaskRunSpec.LocalTaskRunSpec) latestSpec).execution().get();
        }

        @Override
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }

    @Test
    void sameInputs_produceSameHash() {
        String h1 = AgentSpawnTool.deterministicHash("session-1", "code-reviewer", "review");
        String h2 = AgentSpawnTool.deterministicHash("session-1", "code-reviewer", "review");
        assertEquals(h1, h2);
    }

    @Test
    void hashIs12HexChars() {
        String h = AgentSpawnTool.deterministicHash("s1", "worker", null);
        assertEquals(12, h.length());
        assertTrue(h.matches("[0-9a-f]{12}"));
    }

    @Test
    void differentParentSession_differentHash() {
        String h1 = AgentSpawnTool.deterministicHash("session-A", "worker", null);
        String h2 = AgentSpawnTool.deterministicHash("session-B", "worker", null);
        assertNotEquals(h1, h2);
    }

    @Test
    void differentAgentId_differentHash() {
        String h1 = AgentSpawnTool.deterministicHash("s1", "code-reviewer", null);
        String h2 = AgentSpawnTool.deterministicHash("s1", "test-runner", null);
        assertNotEquals(h1, h2);
    }

    @Test
    void differentLabel_differentHash() {
        String h1 = AgentSpawnTool.deterministicHash("s1", "worker", "alpha");
        String h2 = AgentSpawnTool.deterministicHash("s1", "worker", "beta");
        assertNotEquals(h1, h2);
    }

    @Test
    void nullLabel_differentFromNonNullLabel() {
        String h1 = AgentSpawnTool.deterministicHash("s1", "worker", null);
        String h2 = AgentSpawnTool.deterministicHash("s1", "worker", "label");
        assertNotEquals(h1, h2);
    }

    @Test
    void nullParentSession_usesAnonFallback() {
        String h1 = AgentSpawnTool.deterministicHash(null, "worker", null);
        String h2 = AgentSpawnTool.deterministicHash(null, "worker", null);
        assertEquals(h1, h2);

        // "anon" seed differs from a real session id
        String h3 = AgentSpawnTool.deterministicHash("anon", "worker", null);
        // null → "anon" so these are the same
        assertEquals(h1, h3);
    }

    @Test
    void declarationPersistSession_defaultsFalse() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .build();
        assertFalse(decl.isPersistSession());
    }

    @Test
    void declarationPersistSession_canBeEnabled() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .persistSession(true)
                        .build();
        assertTrue(decl.isPersistSession());
    }

    @Test
    void declarationInheritParentPermissions_defaultsTrue() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .build();
        assertTrue(decl.isInheritParentPermissions());
    }

    @Test
    void declarationInheritParentPermissions_canBeDisabled() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("test")
                        .inlineAgentsBody("body")
                        .inheritParentPermissions(false)
                        .build();
        assertFalse(decl.isInheritParentPermissions());
    }
}
