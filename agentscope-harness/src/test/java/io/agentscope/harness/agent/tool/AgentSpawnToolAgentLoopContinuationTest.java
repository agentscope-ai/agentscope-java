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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.RemoteSubmitContext;
import io.agentscope.harness.agent.subagent.task.RemoteTarget;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * The two-agent turn loop, driven by the parent agent itself.
 *
 * <p>The other continuation tests call {@link AgentSpawnTool#agentSpawn} / {@link
 * AgentSpawnTool#agentSend} directly, which proves the tool behaviour but says nothing about whether
 * an agent reaches it: the model has to choose {@code agent_spawn}, the runtime has to execute it,
 * the result has to come back into the conversation, and the next turn's {@code agent_send} has to
 * reuse the anchor that first call produced. This test drives that loop through a real {@link
 * ReActAgent}: the model is scripted (no provider call), but every hop around it is production code.
 *
 * <p>The same agent instance serves both turns, so this covers the single-instance path; the
 * cross-replica path is covered by the persisted {@code SpawnEntry} tests and by the cluster tests.
 */
@DisplayName("Parent agent loop drives agent_spawn then agent_send")
class AgentSpawnToolAgentLoopContinuationTest {

    private static final String SESSION = "sess-parent";
    private static final Pattern AGENT_KEY = Pattern.compile("agent_key: (\\S+)");

    @TempDir Path workspace;

    private final Agent childAgent = mock(Agent.class);
    private final TaskRepository repository = mock(TaskRepository.class);
    private final List<TaskRunSpec.RemoteTaskRunSpec> submitted = new CopyOnWriteArrayList<>();
    private final List<String> toolSchemasSeen = new CopyOnWriteArrayList<>();

    @Test
    @Timeout(30)
    void modelCallsSpawnThenSendAndTheSecondSubmissionContinuesTheFirstTask() {
        stubSubmissions();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(remoteSpawnTool());

        HarnessAgent parent =
                HarnessAgent.builder()
                        .name("planner")
                        .agentId("agent-planner")
                        .sysPrompt("delegate work to subagents")
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .model(new ToolScriptModel(toolSchemasSeen))
                        .toolkit(toolkit)
                        .disableShellTool()
                        .disableFilesystemTools()
                        .disableWebTools()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableToolsConfig()
                        .disableWorkspaceContext()
                        .build();

        RuntimeContext context = RuntimeContext.builder().sessionId(SESSION).userId("u-1").build();

        Msg first =
                parent.call(List.of(user("spawn the worker")), context)
                        .block(Duration.ofSeconds(20));
        assertNotNull(first, "the parent turn must produce a reply");
        assertEquals(1, submitted.size(), "the model's agent_spawn must reach the transport once");

        TaskRunSpec.RemoteTaskRunSpec spawn = submitted.get(0);
        assertTrue(
                toolSchemasSeen.stream().anyMatch(names -> names.contains("agent_spawn")),
                "the parent model must be offered agent_spawn, saw " + toolSchemasSeen);
        // HarnessAgent resolves its journal namespace as agentId ?? name, so an explicit agentId
        // wins; a name-only agent (the common configuration) reports its name.
        assertEquals(
                "agent-planner",
                spawn.context().parentAgentId(),
                "the submission must report the caller's journal namespace");
        assertNotNull(
                spawn.context().sessionId(),
                "the spawn names the child session so a follow-up can repeat it");

        Msg second =
                parent.call(List.of(user("now continue it")), context)
                        .block(Duration.ofSeconds(20));
        assertNotNull(second, "the second parent turn must produce a reply");
        assertEquals(
                2,
                submitted.size(),
                "the model's agent_send must reach the transport once, got " + submitted.size());

        TaskRunSpec.RemoteTaskRunSpec send = submitted.get(1);
        assertEquals("now continue it", send.input());
        assertEquals(
                spawn.context().sessionId(),
                send.context().sessionId(),
                "the follow-up must repeat the session the spawn named, not open a new one");
    }

    /** Answers with one tool call per turn, then a final text; records the tool schemas offered. */
    private static final class ToolScriptModel implements io.agentscope.core.model.Model {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> schemasSeen;
        private volatile String agentKey;

        ToolScriptModel(List<String> schemasSeen) {
            this.schemasSeen = schemasSeen;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                io.agentscope.core.model.GenerateOptions options) {
            schemasSeen.add(
                    tools == null
                            ? ""
                            : tools.stream()
                                    .map(ToolSchema::getName)
                                    .reduce("", (a, b) -> a + "|" + b));
            agentKey = agentKey != null ? agentKey : findAgentKey(messages);

            return Flux.just(
                    switch (calls.getAndIncrement()) {
                        case 0 ->
                                toolCall(
                                        "agent_spawn",
                                        "call-spawn",
                                        Map.of(
                                                "agent_id",
                                                "worker",
                                                "task",
                                                "first turn",
                                                "reusable",
                                                true,
                                                "timeout_seconds",
                                                30));
                        case 1 -> reply("spawned");
                        case 2 ->
                                toolCall(
                                        "agent_send",
                                        "call-send",
                                        Map.of(
                                                "agent_key",
                                                agentKey != null ? agentKey : "missing",
                                                "message",
                                                "now continue it",
                                                "timeout_seconds",
                                                30));
                        default -> reply("continued");
                    });
        }

        private static String findAgentKey(List<Msg> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                for (ToolResultBlock block :
                        messages.get(i).getContentBlocks(ToolResultBlock.class)) {
                    for (ContentBlock output : block.getOutput()) {
                        if (output instanceof TextBlock text) {
                            Matcher matcher = AGENT_KEY.matcher(text.getText());
                            if (matcher.find()) {
                                return matcher.group(1);
                            }
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public String getModelName() {
            return "tool-script-model";
        }

        private static ChatResponse toolCall(String name, String id, Map<String, Object> input) {
            return ChatResponse.builder()
                    .content(
                            List.<ContentBlock>of(
                                    ToolUseBlock.builder()
                                            .name(name)
                                            .id(id)
                                            .input(input)
                                            .content(JsonUtils.getJsonCodec().toJson(input))
                                            .build()))
                    .usage(new ChatUsage(1, 1, 0))
                    .build();
        }

        private static ChatResponse reply(String text) {
            return ChatResponse.builder()
                    .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                    .usage(new ChatUsage(1, 1, 0))
                    .build();
        }
    }

    private AgentSpawnTool remoteSpawnTool() {
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker",
                                        "remote worker",
                                        rc -> childAgent,
                                        SubagentDeclaration.builder()
                                                .name("worker")
                                                .description("remote worker")
                                                .url("http://unused.invalid")
                                                .build())),
                        null);
        AgentSpawnTool tool = new AgentSpawnTool(manager, repository, 0);
        tool.setRemoteTransport(
                new RemoteSubagentTransport() {
                    @Override
                    public String transportType() {
                        return "agent-protocol";
                    }

                    @Override
                    public void submit(
                            RemoteTarget target,
                            String taskId,
                            String agentId,
                            String input,
                            RemoteSubmitContext context) {}

                    @Override
                    public RemoteTaskStatus getStatus(RemoteTarget target, String taskId) {
                        return new RemoteTaskStatus("success", null);
                    }

                    @Override
                    public String waitForResult(
                            RemoteTarget target, String taskId, long timeoutSeconds) {
                        return "ok";
                    }

                    @Override
                    public void cancel(RemoteTarget target, String taskId) {}

                    @Override
                    public void resume(
                            RemoteTarget target,
                            String taskId,
                            List<RemoteConfirmDecision> decisions) {}
                });
        return tool;
    }

    private void stubSubmissions() {
        when(repository.putTask(
                        any(RuntimeContext.class),
                        anyString(),
                        anyString(),
                        eq(SESSION),
                        any(TaskRunSpec.class)))
                .thenAnswer(
                        invocation -> {
                            TaskRunSpec spec = invocation.getArgument(4);
                            if (spec instanceof TaskRunSpec.RemoteTaskRunSpec remote) {
                                submitted.add(remote);
                            }
                            return new BackgroundTask(
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    CompletableFuture.completedFuture("ok"));
                        });
    }

    private static Msg user(String text) {
        return Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER)
                .textContent(text)
                .build();
    }
}
