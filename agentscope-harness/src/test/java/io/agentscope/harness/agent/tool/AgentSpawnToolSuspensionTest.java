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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AgentSpawnToolSuspensionTest {

    private final RuntimeContext context =
            RuntimeContext.builder().sessionId("parent").userId("user").build();
    private final TaskRepository repository = mock(TaskRepository.class);
    private final Agent child = mock(Agent.class);
    private final AgentSpawnTool tool =
            new AgentSpawnTool(
                    new DefaultAgentManager(
                            List.of(
                                    new SubagentEntry(
                                            "worker",
                                            "Worker",
                                            rc -> child,
                                            SubagentDeclaration.builder()
                                                    .name("worker")
                                                    .description("Worker")
                                                    .inlineAgentsBody("Worker")
                                                    .persistSession(true)
                                                    .build())),
                            null),
                    repository,
                    0);

    @ParameterizedTest
    @ValueSource(strings = {"spawn", "send", "reuse"})
    void suspendedChildReportsError(String operation) {
        when(child.call(anyList())).thenReturn(Mono.just(suspended()));
        String result = invoke(operation, 30).block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.contains("status: error"), result);
        assertTrue(result.contains("choose_x"), result);
        assertFalse(result.contains("status: ok"), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spawn", "send", "reuse"})
    void suspendedBackgroundChildFailsTask(String operation) {
        when(child.call(anyList())).thenReturn(Mono.just(suspended()));
        String result = invoke(operation, 0).block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.contains("task_id:"), result);
        TaskRunSpec.LocalTaskRunSpec spec =
                assertInstanceOf(TaskRunSpec.LocalTaskRunSpec.class, capturedTask());
        IllegalStateException error =
                assertThrows(IllegalStateException.class, spec.execution()::get);
        assertTrue(error.getMessage().contains("choose_x"));
    }

    @Test
    void suspendedPromotedChildFailsFuture() throws Exception {
        Sinks.One<Msg> completion = Sinks.one();
        when(child.call(anyList())).thenReturn(completion.asMono());
        String result = invoke("spawn", 1).block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.contains("status: timeout_promoted"), result);
        TaskRunSpec.AdoptedTaskRunSpec spec =
                assertInstanceOf(TaskRunSpec.AdoptedTaskRunSpec.class, capturedTask());
        completion.tryEmitValue(suspended());
        ExecutionException error =
                assertThrows(
                        ExecutionException.class, () -> spec.future().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("choose_x"));
    }

    private Mono<String> invoke(String operation, int timeout) {
        if (!"spawn".equals(operation)) {
            tool.agentSpawn(context, null, "worker", null, "existing", 30, null)
                    .block(Duration.ofSeconds(5));
        }
        if ("send".equals(operation)) {
            return tool.agentSend(context, null, null, "existing", "go", timeout);
        }
        return tool.agentSpawn(
                context,
                null,
                "worker",
                "go",
                "reuse".equals(operation) ? "existing" : null,
                timeout,
                null);
    }

    private TaskRunSpec capturedTask() {
        ArgumentCaptor<TaskRunSpec> captor = ArgumentCaptor.forClass(TaskRunSpec.class);
        verify(repository).putTask(any(), anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static Msg suspended() {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .generateReason(GenerateReason.TOOL_SUSPENDED)
                .content(
                        ToolUseBlock.builder()
                                .id("choice")
                                .name("choose_x")
                                .input(Map.of())
                                .build())
                .build();
    }
}
