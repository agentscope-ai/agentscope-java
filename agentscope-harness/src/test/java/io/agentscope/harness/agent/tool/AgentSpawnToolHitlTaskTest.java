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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskRunOutcome;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskSuspension;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentSpawnToolHitlTaskTest {

    @TempDir Path workspace;

    private WorkspaceManager workspaceManager;
    private WorkspaceTaskRepository taskRepository;
    private DefaultAgentManager manager;
    private AgentSpawnTool spawnTool;
    private RuntimeContext parentContext;
    private AgentState parentState;
    private AtomicInteger toolExecutions;

    @BeforeEach
    void setUp() {
        workspaceManager = new WorkspaceManager(workspace);
        taskRepository = new WorkspaceTaskRepository(workspaceManager, "parent-agent");
        toolExecutions = new AtomicInteger();
        ScriptedModel sharedModel = scriptedModel();
        manager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker",
                                        "permission worker",
                                        rc ->
                                                childAgent(
                                                        toolExecutions,
                                                        sharedModel,
                                                        workspace.resolve("child-state")),
                                        SubagentDeclaration.builder()
                                                .name("worker")
                                                .description("permission worker")
                                                .inlineAgentsBody("Use the guarded tool")
                                                .persistSession(true)
                                                .build())),
                        workspaceManager);
        spawnTool = new AgentSpawnTool(manager, taskRepository, 0);
        parentContext =
                RuntimeContext.builder().userId("user-a").sessionId("parent-session").build();
        parentState = AgentState.builder().sessionId("parent-session").build();
    }

    @AfterEach
    void tearDown() {
        taskRepository.shutdown();
    }

    @Test
    void managerTypedInvocationCapturesPermissionSuspension() {
        DefaultAgentManager manager = new DefaultAgentManager(List.of(), workspaceManager);
        AtomicInteger executions = new AtomicInteger();
        ReActAgent child = childAgent(executions);

        TaskRunOutcome outcome =
                manager.invokeAgentTask(
                        child, "child-session", "user-a", "perform guarded work", parentContext);

        assertTrue(outcome instanceof TaskRunOutcome.WaitingForApproval, outcome.toString());
        TaskSuspension suspension = ((TaskRunOutcome.WaitingForApproval) outcome).suspension();
        TaskRunOutcome resumed =
                manager.invokeAgentTask(
                        child,
                        "child-session",
                        "user-a",
                        List.of(confirmationMessage(true)),
                        parentContext);
        assertTrue(resumed instanceof TaskRunOutcome.Completed, resumed.toString());
        assertEquals(
                1,
                executions.get(),
                "approved continuation must execute the pending tool; context="
                        + describeContext(child.getAgentState("user-a", "child-session")));
        assertFalse(suspension.pendingToolCalls().isEmpty());
    }

    @Test
    void asyncPermissionPauseResumesSameTaskToCompletion() throws Exception {
        String taskId = spawnAsyncTask();
        TaskSuspension suspension = awaitSuspension(taskId);
        ConfirmResult approved = new ConfirmResult(true, askingToolCall());
        RuntimeContext wrongUser = RuntimeContext.builder(parentContext).userId("user-b").build();

        assertFalse(
                spawnTool.resumeSuspendedTask(
                        wrongUser, parentState, taskId, suspension.replyId(), List.of(approved)));
        assertFalse(
                spawnTool.resumeSuspendedTask(
                        parentContext, parentState, taskId, "wrong-reply", List.of(approved)));
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, readRecord(taskId).getStatus());
        assertTrue(
                spawnTool.resumeSuspendedTask(
                        parentContext,
                        parentState,
                        taskId,
                        suspension.replyId(),
                        List.of(approved)));

        TaskRecord completed = awaitStatus(taskId, TaskStatus.COMPLETED);
        assertEquals("finished", completed.getResult());
        assertEquals(1, toolExecutions.get());
    }

    @Test
    void deniedPermissionBecomesDistinctTerminalTaskWithoutExecutingTool() throws Exception {
        String taskId = spawnAsyncTask();
        TaskSuspension suspension = awaitSuspension(taskId);

        assertTrue(
                spawnTool.resumeSuspendedTask(
                        parentContext,
                        parentState,
                        taskId,
                        suspension.replyId(),
                        List.of(new ConfirmResult(false, askingToolCall()))));

        TaskRecord denied = awaitStatus(taskId, TaskStatus.DENIED);
        assertEquals("finished", denied.getResult());
        assertEquals(0, toolExecutions.get());
    }

    @Test
    void suspendedTaskResumesAfterRepositoryAndSpawnToolRestart() throws Exception {
        String taskId = spawnAsyncTask();
        TaskSuspension suspension = awaitSuspension(taskId);
        taskRepository.shutdown();
        taskRepository = new WorkspaceTaskRepository(workspaceManager, "parent-agent");
        spawnTool = new AgentSpawnTool(manager, taskRepository, 0);

        assertTrue(
                spawnTool.resumeSuspendedTask(
                        parentContext,
                        parentState,
                        taskId,
                        suspension.replyId(),
                        List.of(new ConfirmResult(true, askingToolCall()))));

        TaskRecord completed = awaitStatus(taskId, TaskStatus.COMPLETED);
        assertEquals("finished", completed.getResult());
        assertEquals(1, toolExecutions.get());
    }

    private String spawnAsyncTask() {
        String response =
                spawnTool
                        .agentSpawn(
                                parentContext,
                                parentState,
                                "worker",
                                "perform guarded work",
                                "review",
                                0,
                                false)
                        .block();
        for (String line : response.split("\\R")) {
            if (line.startsWith("task_id: ")) {
                return line.substring("task_id: ".length()).trim();
            }
        }
        throw new AssertionError("Spawn response has no task_id: " + response);
    }

    private TaskSuspension awaitSuspension(String taskId) throws Exception {
        TaskRecord record = awaitStatus(taskId, TaskStatus.WAITING_FOR_APPROVAL);
        assertFalse(taskRepository.getTask(parentContext, "parent-session", taskId).isCompleted());
        return record.getSuspension();
    }

    private TaskRecord awaitStatus(String taskId, TaskStatus status) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskRecord> record = readRecordOptional(taskId);
            if (record.isPresent() && record.get().getStatus() == status) {
                return record.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Task "
                        + taskId
                        + " did not reach "
                        + status
                        + "; final record="
                        + readRecordOptional(taskId)
                                .map(record -> record.getStatus() + ":" + record.getErrorMessage())
                                .orElse("missing"));
    }

    private TaskRecord readRecord(String taskId) {
        return readRecordOptional(taskId).orElseThrow();
    }

    private Optional<TaskRecord> readRecordOptional(String taskId) {
        return workspaceManager.readTaskRecord(
                parentContext, "parent-agent", "parent-session", taskId);
    }

    private static ReActAgent childAgent(AtomicInteger executions) {
        return childAgent(executions, scriptedModel(), null);
    }

    private static ReActAgent childAgent(
            AtomicInteger executions, ChatModelBase model, Path stateRoot) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AskingTool(executions));
        ReActAgent.Builder builder =
                ReActAgent.builder().name("worker").model(model).toolkit(toolkit);
        if (stateRoot != null) {
            builder.stateStore(new JsonFileAgentStateStore(stateRoot));
        }
        return builder.build();
    }

    private static ScriptedModel scriptedModel() {
        return new ScriptedModel(
                List.of(
                        () -> Flux.just(toolUseResponse()),
                        () -> Flux.just(textResponse("finished"))));
    }

    private static ToolUseBlock askingToolCall() {
        return ToolUseBlock.builder()
                .id("tool-1")
                .name("guarded")
                .input(Map.of("query", "work"))
                .content("{\"query\":\"work\"}")
                .build();
    }

    private static io.agentscope.core.message.Msg confirmationMessage(boolean confirmed) {
        return io.agentscope.core.message.Msg.builder()
                .name("user")
                .role(io.agentscope.core.message.MsgRole.USER)
                .textContent("[confirm]")
                .metadata(
                        Map.of(
                                io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS,
                                List.of(new ConfirmResult(confirmed, askingToolCall()))))
                .build();
    }

    private static String describeContext(AgentState state) {
        StringBuilder description = new StringBuilder();
        for (io.agentscope.core.message.Msg message : state.getContext()) {
            description.append(message.getRole()).append('[');
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolUseBlock toolUse) {
                    description
                            .append("use:")
                            .append(toolUse.getId())
                            .append(':')
                            .append(toolUse.getState());
                } else if (block instanceof ToolResultBlock toolResult) {
                    description
                            .append("result:")
                            .append(toolResult.getId())
                            .append(':')
                            .append(toolResult.getState())
                            .append(':')
                            .append(
                                    toolResult.getOutput().stream()
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .map(TextBlock::getText)
                                            .toList());
                } else if (block instanceof TextBlock text) {
                    description.append("text:").append(text.getText());
                }
                description.append(',');
            }
            description.append(']');
        }
        return description.toString();
    }

    private static ChatResponse toolUseResponse() {
        return ChatResponse.builder().content(List.<ContentBlock>of(askingToolCall())).build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return scripts.get(index.getAndIncrement()).get();
        }
    }

    private static final class AskingTool extends ToolBase {
        private final AtomicInteger executions;

        private AskingTool(AtomicInteger executions) {
            super("guarded", "guarded tool", schema(), false, true, false, null, false, false);
            this.executions = executions;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("approval required"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executions.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed"));
        }

        private static Map<String, Object> schema() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("query", Map.of("type", "string")));
            return schema;
        }
    }
}
