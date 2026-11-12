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

package io.agentscope.durable.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.durable.worker.WorkerRuntime;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class AgentScopeToolExecutorTest {
    @TempDir Path directory;

    ToolBase tool(AtomicInteger calls, boolean uncertain) {
        return new ToolBase(
                ToolBase.builder()
                        .name("job")
                        .description("Native Hands fixture")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("value", Map.of("type", "integer"))))) {
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                calls.incrementAndGet();
                assertEquals("tenant", param.getRuntimeContext().getUserId());
                assertEquals("session", param.getRuntimeContext().getSessionId());
                var origin = param.getRuntimeContext().get("durable.origin", RunStepRef.class);
                if (origin != null)
                    assertEquals(origin.runId(), param.getRuntimeContext().getRunId());
                assertEquals(7, param.getInput().get("value"));
                if (uncertain)
                    return Mono.error(new IllegalStateException("Effect committed, reply lost"));
                return Mono.just(
                        ToolResultBlock.of(
                                List.of(TextBlock.builder().text("native result").build()),
                                Map.of("structured", Map.of("value", 7))));
            }
        };
    }

    @Test
    void nativeToolkitPresetsAreAppliedAndInvalidArgumentsNeverInvokeTheTool() {
        var calls = new AtomicInteger();
        var tool = tool(calls, false);
        var toolkit = new Toolkit();
        toolkit.registration()
                .tool(tool)
                .presetParameters(Map.of("job", Map.of("value", 7)))
                .apply();
        var binding =
                AgentScopeToolExecutor.binding(tool, "v1", "hands", DurableJson.value(Map.of()));
        var executor = new AgentScopeToolExecutor(toolkit, List.of(binding), Duration.ofSeconds(1));
        var session = new SessionKey("tenant", "session");
        assertEquals(
                ExecutionState.SUCCEEDED,
                executor.startExecution(
                                new ExecutionRequest(
                                        "execution",
                                        new ThreadKey(session, "main"),
                                        "turn",
                                        binding.call(
                                                "call", DurableJson.value(Map.of("value", 99)))))
                        .state());
        var invalid =
                new ExecutionRequest(
                        "invalid",
                        new ThreadKey(session, "main"),
                        "turn",
                        binding.call(
                                "invalid-call", DurableJson.value(Map.of("value", "wrong type"))));
        assertEquals(
                DurableException.Code.INVALID,
                assertThrows(DurableException.class, () -> executor.validateStart(invalid)).code());
        assertEquals(1, calls.get());
    }

    @Test
    void nativeResultRetainsStructuredMetadata() {
        var calls = new AtomicInteger();
        var tool = tool(calls, false);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        var binding =
                AgentScopeToolExecutor.binding(tool, "v1", "hands", DurableJson.value(Map.of()));
        var executor = new AgentScopeToolExecutor(toolkit, List.of(binding), Duration.ofSeconds(1));
        var request =
                new ExecutionRequest(
                        "execution",
                        new ThreadKey(new SessionKey("tenant", "session"), "main"),
                        "turn",
                        binding.call("call", DurableJson.value(Map.of("value", 7)))
                                .withOrigin(new RunStepRef("brain-run", "brain-step")));
        var result = executor.startExecution(request);
        assertEquals(ExecutionState.SUCCEEDED, result.state());
        assertEquals(
                7,
                result.result()
                        .path("result")
                        .path("metadata")
                        .path("structured")
                        .path("value")
                        .asInt());
        assertEquals(1, calls.get());
    }

    @Test
    void oneFrozenDefinitionDrivesBrainSchemasHandsAndNativeResultApplication() throws Exception {
        var calls = new AtomicInteger();
        var nativeTool = tool(calls, false);
        var tools = new Toolkit();
        tools.registerAgentTool(nativeTool);
        var binding =
                AgentScopeToolExecutor.binding(
                        nativeTool, "v1", "hands", DurableJson.value(Map.of("kind", "native")));
        var model = new AgentScopeAdapterTest.ScriptedModel(true);
        var adapter =
                AgentScopeAdapter.forReAct(
                        context ->
                                ReActAgent.builder()
                                        .name("brain")
                                        .model(model)
                                        .toolkit(new Toolkit())
                                        .permissionContext(
                                                PermissionContextState.builder()
                                                        .mode(PermissionMode.BYPASS)
                                                        .build()),
                        List.of(binding));
        try (var records = JdbcRecordStore.sqlite(directory.resolve("native.db"));
                var worker =
                        WorkerRuntime.builder()
                                .recordStore(records)
                                .executor(
                                        "hands",
                                        new AgentScopeToolExecutor(
                                                tools, List.of(binding), Duration.ofSeconds(5)))
                                .pollInterval(Duration.ofMillis(1))
                                .build();
                var runtime =
                        DurableRuntime.builder()
                                .backend(new TransactionalSessionBackend(records))
                                .harness("native", "v1", adapter)
                                .executor("hands", worker.localExecutor())
                                .pollInterval(Duration.ofMillis(1))
                                .build()) {
            var session = new SessionKey("tenant", "session");
            runtime.createSession(session, "native").mainThread().view().definition();
            var turn = runtime.getSession(session).submit("input", "turn job");
            for (int i = 0;
                    i < 30
                            && !runtime.getSession(session)
                                    .snapshot()
                                    .turns()
                                    .get(0)
                                    .status()
                                    .terminal();
                    i++) {
                runtime.tick(new ThreadKey(session, "main"));
                worker.tick();
                Thread.sleep(5);
            }
            assertNull(runtime.lastFailure(new ThreadKey(session, "main")));
            assertEquals(0, worker.statistics(100).unknown());
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    runtime.getSession(session).snapshot().turns().get(0).status());
            assertEquals(1, calls.get());
            assertEquals(2, model.inputs.size());
            var nativeResult =
                    model.inputs.get(1).stream()
                            .flatMap(
                                    message ->
                                            message
                                                    .getContentBlocks(ToolResultBlock.class)
                                                    .stream())
                            .filter(result -> !result.isSuspended())
                            .findFirst()
                            .orElseThrow();
            assertEquals(Map.of("value", 7), nativeResult.getMetadata().get("structured"));
            var executionId =
                    TransactionalSessionBackend.executionId(
                            new ThreadKey(session, "main"), turn.turnId(), "call-1");
            var execution =
                    runtime.backend().execution(new ThreadKey(session, "main"), executionId);
            assertEquals(binding.digest(), execution.request().call().binding());
            assertTrue(execution.applied());
        }
    }

    @Test
    void bindingDriftIsRejectedBeforeDispatchAndUncertainNativeCallsAreNeverRepeated()
            throws Exception {
        var calls = new AtomicInteger();
        var nativeTool = tool(calls, true);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(nativeTool);
        var binding =
                AgentScopeToolExecutor.binding(
                        nativeTool, "v1", "hands", DurableJson.value(Map.of()));
        var executor = new AgentScopeToolExecutor(toolkit, List.of(binding), Duration.ofSeconds(2));
        var request =
                new ExecutionRequest(
                        "execution",
                        new ThreadKey(new SessionKey("tenant", "session"), "main"),
                        "turn",
                        binding.call("call", DurableJson.value(Map.of("value", 7))));
        var changed =
                new ToolBinding(
                        binding.name(),
                        "v2",
                        binding.executor(),
                        binding.schema(),
                        binding.target());
        var invalid =
                new ExecutionRequest(
                        "invalid",
                        request.thread(),
                        request.turnId(),
                        changed.call("call", request.call().arguments()));
        try (var records = JdbcRecordStore.sqlite(directory.resolve("uncertain.db"))) {
            var store = new TransactionalExecutionStore(records);
            try (var worker =
                    WorkerRuntime.builder()
                            .executionStore(store)
                            .executor("hands", executor)
                            .pollInterval(Duration.ofMillis(1))
                            .build()) {
                worker.localExecutor().startExecution(invalid);
                assertEquals(
                        DurableException.Code.INCOMPATIBLE,
                        assertThrows(DurableException.class, worker::tick).code());
                Thread.sleep(5);
                var work = store.claim(invalid, "inspect", Duration.ofSeconds(5)).orElseThrow();
                assertFalse(work.attempted());
                store.cancel(invalid);
                store.release(work, Duration.ZERO);
                worker.localExecutor().startExecution(request);
                worker.tick();
                assertEquals(
                        ExecutionState.UNKNOWN,
                        worker.localExecutor().query(request, null).state());
            }
            Thread.sleep(5);
            try (var replacement =
                    WorkerRuntime.builder()
                            .executionStore(store)
                            .executor("hands", executor)
                            .pollInterval(Duration.ofMillis(1))
                            .build()) {
                replacement.tick();
                assertEquals(
                        ExecutionState.UNKNOWN,
                        replacement.localExecutor().query(request, null).state());
                assertEquals(1, calls.get());
            }
        }
    }
}
