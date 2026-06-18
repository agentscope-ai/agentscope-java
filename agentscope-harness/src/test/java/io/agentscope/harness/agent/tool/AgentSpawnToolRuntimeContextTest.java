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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentSpawnToolRuntimeContextTest {

    private static final class CapturingTaskRepository implements TaskRepository {
        final List<RuntimeContext> contexts = new ArrayList<>();
        final List<String> taskIds = new ArrayList<>();
        final List<String> agentIds = new ArrayList<>();
        final List<String> sessionIds = new ArrayList<>();
        final List<TaskRunSpec> specs = new ArrayList<>();

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
            contexts.add(rc);
            taskIds.add(taskId);
            agentIds.add(subAgentId);
            sessionIds.add(sessionId);
            specs.add(spec);
            return null;
        }

        @Override
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc,
                String sessionId,
                io.agentscope.harness.agent.subagent.task.TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }

    private static RuntimeContext parentContext() {
        return RuntimeContext.builder()
                .sessionId("parent-session")
                .userId("user-1")
                .put("traceId", "trace-123")
                .build();
    }

    private static Msg assistantReply(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String extractLineValue(String text, String prefix) {
        return text.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing line: " + prefix + text));
    }

    @Test
    void agentSpawn_asyncLocalTaskUsesParentRuntimeContext() {
        CapturingTaskRepository repo = new CapturingTaskRepository();
        DefaultAgentManager manager = mock(DefaultAgentManager.class);
        Agent child = mock(Agent.class);
        RuntimeContext parent = parentContext();

        when(manager.createAgentIfPresent(eq("worker"), same(parent)))
                .thenReturn(Optional.of(child));
        when(manager.getDeclaration("worker")).thenReturn(Optional.empty());
        when(manager.invokeAgent(
                        eq(child),
                        any(RuntimeContext.class),
                        anyString(),
                        eq("user-1"),
                        eq("build something")))
                .thenReturn(Mono.just(assistantReply("spawn-result")));

        AgentSpawnTool tool = new AgentSpawnTool(manager, repo, 0);

        String response =
                tool.agentSpawn(parent, null, "worker", "build something", null, 0, false).block();

        assertNotNull(response);
        assertTrue(response.contains("status: accepted"));
        assertEquals(1, repo.specs.size());
        assertSame(parent, repo.contexts.get(0));
        assertEquals("parent-session", repo.sessionIds.get(0));

        TaskRunSpec.LocalTaskRunSpec spec = (TaskRunSpec.LocalTaskRunSpec) repo.specs.get(0);
        assertEquals("spawn-result", spec.execution().get());

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(RuntimeContext.class);
        var sessionCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(manager)
                .invokeAgent(
                        eq(child),
                        ctxCaptor.capture(),
                        sessionCaptor.capture(),
                        eq("user-1"),
                        eq("build something"));
        assertSame(parent, ctxCaptor.getValue());
        assertEquals("trace-123", ctxCaptor.getValue().get("traceId"));
        assertTrue(sessionCaptor.getValue().startsWith("sub-"));
    }

    @Test
    void agentSend_asyncLocalTaskUsesParentRuntimeContext() {
        CapturingTaskRepository repo = new CapturingTaskRepository();
        DefaultAgentManager manager = mock(DefaultAgentManager.class);
        Agent child = mock(Agent.class);
        RuntimeContext parent = parentContext();

        when(manager.createAgentIfPresent(eq("worker"), same(parent)))
                .thenReturn(Optional.of(child));
        when(manager.getDeclaration("worker")).thenReturn(Optional.empty());
        when(manager.invokeAgent(
                        eq(child),
                        any(RuntimeContext.class),
                        anyString(),
                        eq("user-1"),
                        eq("follow-up")))
                .thenReturn(Mono.just(assistantReply("send-result")));

        AgentSpawnTool tool = new AgentSpawnTool(manager, repo, 0);

        String spawnReply =
                tool.agentSpawn(parent, null, "worker", null, "stable", null, false).block();
        String agentKey = extractLineValue(spawnReply, "agent_key: ");

        assertNotNull(agentKey);
        assertTrue(repo.specs.isEmpty());

        String sendReply = tool.agentSend(parent, null, agentKey, null, "follow-up", 0).block();
        assertNotNull(sendReply);
        assertTrue(sendReply.startsWith("status: accepted"));
        assertEquals(1, repo.specs.size());
        assertSame(parent, repo.contexts.get(0));

        TaskRunSpec.LocalTaskRunSpec spec = (TaskRunSpec.LocalTaskRunSpec) repo.specs.get(0);
        assertEquals("send-result", spec.execution().get());

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(RuntimeContext.class);
        var sessionCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(manager)
                .invokeAgent(
                        eq(child),
                        ctxCaptor.capture(),
                        sessionCaptor.capture(),
                        eq("user-1"),
                        eq("follow-up"));
        assertSame(parent, ctxCaptor.getValue());
        assertEquals("trace-123", ctxCaptor.getValue().get("traceId"));
        assertTrue(sessionCaptor.getValue().startsWith("sub-"));
    }

    @Test
    void agentSpawn_reusedPersistentAgentAsyncTaskUsesParentRuntimeContext() {
        CapturingTaskRepository repo = new CapturingTaskRepository();
        DefaultAgentManager manager = mock(DefaultAgentManager.class);
        Agent child = mock(Agent.class);
        RuntimeContext parent = parentContext();
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("persistent worker")
                        .inlineAgentsBody("worker body")
                        .persistSession(true)
                        .build();

        when(manager.createAgentIfPresent(eq("worker"), same(parent)))
                .thenReturn(Optional.of(child));
        when(manager.getDeclaration("worker")).thenReturn(Optional.of(decl));
        when(manager.invokeAgent(
                        eq(child),
                        any(RuntimeContext.class),
                        anyString(),
                        eq("user-1"),
                        eq("reused task")))
                .thenReturn(Mono.just(assistantReply("reused-result")));

        AgentSpawnTool tool = new AgentSpawnTool(manager, repo, 0);

        String initialReply =
                tool.agentSpawn(parent, null, "worker", null, "stable", null, false).block();
        assertNotNull(initialReply);
        assertTrue(repo.specs.isEmpty(), "spawn-without-task should not create a task spec");

        String reusedReply =
                tool.agentSpawn(parent, null, "worker", "reused task", "stable", 0, false).block();

        assertNotNull(reusedReply);
        assertTrue(reusedReply.contains("status: accepted"));
        assertEquals(1, repo.specs.size());
        assertSame(parent, repo.contexts.get(0));

        TaskRunSpec.LocalTaskRunSpec spec = (TaskRunSpec.LocalTaskRunSpec) repo.specs.get(0);
        assertEquals("reused-result", spec.execution().get());

        var ctxCaptor = org.mockito.ArgumentCaptor.forClass(RuntimeContext.class);
        var sessionCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(manager)
                .invokeAgent(
                        eq(child),
                        ctxCaptor.capture(),
                        sessionCaptor.capture(),
                        eq("user-1"),
                        eq("reused task"));
        assertSame(parent, ctxCaptor.getValue());
        assertEquals("trace-123", ctxCaptor.getValue().get("traceId"));
        assertTrue(sessionCaptor.getValue().startsWith("sub-"));
    }
}
