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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Behavior regression for the follow-up lifecycle the subagent prompts describe: a spawn runs one
 * invocation and returns that invocation's result; {@code agent_send} follow-ups continue the
 * same child conversation, and a task-less spawn creates the session without running anything.
 */
class AgentSpawnToolFollowUpSessionTest {

    /** A spawn tool over one "worker" child whose model records every message list it sees. */
    private record Fixture(RecordingModel model, AgentSpawnTool tool, RuntimeContext ctx) {}

    private static Fixture fixture() {
        RecordingModel model = new RecordingModel();
        ReActAgent child =
                ReActAgent.builder().name("child").sysPrompt("child").model(model).build();
        AgentSpawnTool tool =
                new AgentSpawnTool(
                        new DefaultAgentManager(
                                List.of(new SubagentEntry("worker", "worker", rc -> child)), null),
                        new RecordingTaskRepository(),
                        0);
        RuntimeContext ctx = RuntimeContext.builder().userId("u1").sessionId("parent-s1").build();
        return new Fixture(model, tool, ctx);
    }

    /** The agent_key reported on the first line of a spawn result. */
    private static String agentKey(String spawnResult) {
        return spawnResult.lines().findFirst().orElseThrow().substring("agent_key: ".length());
    }

    /**
     * Model that records every message list it is shown and echoes the last user text back,
     * so the test can assert both the reply channel and the child's accumulated memory.
     */
    private static final class RecordingModel implements Model {
        final List<List<Msg>> calls = new ArrayList<>();

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            String lastUser = "";
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).getRole() == MsgRole.USER) {
                    lastUser = messages.get(i).getTextContent();
                    break;
                }
            }
            return Flux.just(
                    new ChatResponse(
                            "test-model",
                            List.of(TextBlock.builder().text("ack: " + lastUser).build()),
                            null,
                            Map.of(),
                            "stop"));
        }
    }

    private static final class RecordingTaskRepository implements TaskRepository {
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
            throw new UnsupportedOperationException("sync follow-ups must not create tasks");
        }

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
    void spawnThenSend_continuesChildConversationHistory() {
        Fixture f = fixture();

        String spawn =
                assertText(
                        f.tool.agentSpawn(f.ctx, null, "worker", "first task", null, 5, null)
                                .block(),
                        ToolResultState.SUCCESS);
        assertTrue(spawn.contains("status: ok"), spawn);
        assertTrue(spawn.contains("ack: first task"), spawn);

        String send =
                assertText(
                        f.tool.agentSend(f.ctx, null, agentKey(spawn), null, "second question", 5)
                                .block(),
                        ToolResultState.SUCCESS);
        assertTrue(send.contains("ack: second question"), send);

        assertEquals(2, f.model.calls.size(), "each invocation is exactly one child run");
        List<Msg> secondRun = f.model.calls.get(1);
        assertTrue(
                secondRun.stream()
                        .anyMatch(
                                m ->
                                        m.getRole() == MsgRole.USER
                                                && "first task".equals(m.getTextContent())),
                "follow-up must see the first round's task: " + secondRun);
        assertTrue(
                secondRun.stream()
                        .anyMatch(
                                m ->
                                        m.getRole() == MsgRole.ASSISTANT
                                                && "ack: first task".equals(m.getTextContent())),
                "follow-up must see the first round's reply: " + secondRun);
    }

    @Test
    void spawnWithoutTask_createsSessionWithoutRunningIt() {
        Fixture f = fixture();

        String spawn =
                assertText(
                        f.tool.agentSpawn(f.ctx, null, "worker", null, null, 5, null).block(),
                        ToolResultState.SUCCESS);
        assertTrue(spawn.contains("status: accepted"), spawn);
        assertEquals(0, f.model.calls.size(), "task-less spawn must not invoke the child");

        String send =
                assertText(
                        f.tool.agentSend(f.ctx, null, agentKey(spawn), null, "start now", 5)
                                .block(),
                        ToolResultState.SUCCESS);
        assertTrue(send.contains("ack: start now"), send);
        assertEquals(1, f.model.calls.size());
        assertEquals(
                1,
                f.model.calls.get(0).stream().filter(m -> m.getRole() == MsgRole.USER).count(),
                "the first send starts the child's conversation from scratch");
    }
}
