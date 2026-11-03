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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/** Regression coverage for tracing across the local background-task executor boundary. */
class WorkspaceTaskRepositoryTracingTest {

    @TempDir Path workspace;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk telemetry =
            OpenTelemetrySdk.builder()
                    .setTracerProvider(
                            SdkTracerProvider.builder()
                                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                    .build())
                    .build();
    private final List<ReActAgent> agents = new ArrayList<>();
    private WorkspaceTaskRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        // Start the worker before any trace exists; inheriting a caller thread would hide the bug.
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        repository =
                WorkspaceTaskRepository.forTests(
                        new WorkspaceManager(workspace), "parent", executor);
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.shutdown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        agents.forEach(ReActAgent::close);
        telemetry.close();
    }

    @ParameterizedTest
    @CsvSource({"spawn,0", "send,0", "reuse,0", "spawn,30", "send,30", "reuse,30"})
    void subagentSpansKeepTheCallingToolAsParent(String operation, int timeoutSeconds)
            throws Exception {
        OtelTracingMiddleware tracing = new OtelTracingMiddleware(telemetry);
        ReActAgent child =
                ReActAgent.builder()
                        .name("child")
                        .model(new MockModel("done"))
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(tracing)
                        .build();
        agents.add(child);
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("worker")
                        .inlineAgentsBody("worker")
                        .persistSession(true)
                        .build();
        AgentSpawnTool spawnTool =
                new AgentSpawnTool(
                        new DefaultAgentManager(
                                List.of(
                                        new SubagentEntry(
                                                "worker", "worker", rc -> child, declaration)),
                                null),
                        repository,
                        0);
        RuntimeContext rc =
                RuntimeContext.builder().userId("user").sessionId("parent-session").build();
        if (!operation.equals("spawn")) {
            // Seed the persistent instance without starting a traced child invocation.
            spawnTool
                    .agentSpawn(rc, null, "worker", null, "worker", 0, null)
                    .block(Duration.ofSeconds(5));
        }
        String toolName = operation.equals("send") ? "agent_send" : "agent_spawn";
        Map<String, Object> arguments =
                operation.equals("send")
                        ? Map.of(
                                "label",
                                "worker",
                                "message",
                                "work",
                                "timeout_seconds",
                                timeoutSeconds)
                        : Map.of(
                                "agent_id",
                                "worker",
                                "label",
                                "worker",
                                "task",
                                "work",
                                "timeout_seconds",
                                timeoutSeconds);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("delegate")
                        .name(toolName)
                        .input(arguments)
                        .content(JsonUtils.getJsonCodec().toJson(arguments))
                        .build();
        Model parentModel = mock(Model.class);
        when(parentModel.getModelName()).thenReturn("parent-model");
        when(parentModel.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "call", List.of(toolCall), null, Map.of(), "tool_use")),
                        Flux.just(
                                new ChatResponse(
                                        "reply",
                                        List.of(TextBlock.builder().text("submitted").build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(spawnTool);
        ReActAgent parent =
                ReActAgent.builder()
                        .name("parent")
                        .model(parentModel)
                        .toolkit(toolkit)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(tracing)
                        .build();
        agents.add(parent);

        CountDownLatch releaseWorker = new CountDownLatch(1);
        if (timeoutSeconds == 0) {
            executor.submit(
                    () -> {
                        releaseWorker.await();
                        return null;
                    });
        }
        try {
            parent.call(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("delegate")
                                            .build()),
                            rc)
                    .block(Duration.ofSeconds(10));
            if (timeoutSeconds == 0) {
                // The task must retain the tool context even after its parent span has ended.
                assertEquals(
                        0,
                        exporter.getFinishedSpanItems().stream()
                                .filter(span -> span.getName().equals("invoke_agent child"))
                                .count());
            }
        } finally {
            releaseWorker.countDown();
        }
        if (timeoutSeconds == 0) {
            var tasks = repository.listTasks(rc, rc.getSessionId(), null);
            assertEquals(1, tasks.size());
            BackgroundTask task = tasks.iterator().next();
            assertTrue(task.waitForCompletion(10_000));
            assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
            assertEquals("done", task.getResult());
        }

        SpanData parentSpan = span("invoke_agent parent");
        SpanData toolSpan = span("execute_tool " + toolName);
        SpanData childSpan = span("invoke_agent child");
        SpanData modelSpan = span("chat mock-model");
        assertEquals(parentSpan.getTraceId(), toolSpan.getTraceId());
        assertEquals(parentSpan.getSpanId(), toolSpan.getParentSpanId());
        assertEquals(toolSpan.getTraceId(), childSpan.getTraceId());
        assertEquals(toolSpan.getSpanId(), childSpan.getParentSpanId());
        assertEquals(childSpan.getTraceId(), modelSpan.getTraceId());
        assertEquals(childSpan.getSpanId(), modelSpan.getParentSpanId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachSubmissionCapturesItsContextAndRestoresTheWorker(boolean fail) throws Exception {
        ContextKey<String> key = ContextKey.named("request-marker");
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.submit(
                () -> {
                    releaseWorker.await();
                    return null;
                });
        List<BackgroundTask> tasks = new ArrayList<>();
        List<Span> parents = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String id = "request-" + i;
                Span parent = telemetry.getTracer("test").spanBuilder(id).setNoParent().startSpan();
                parents.add(parent);
                try (Scope ignored = Context.root().with(parent).with(key, id).makeCurrent()) {
                    tasks.add(
                            repository.putTask(
                                    RuntimeContext.empty(),
                                    id,
                                    "child",
                                    "session",
                                    new TaskRunSpec.LocalTaskRunSpec(
                                            () -> {
                                                assertEquals(id, Context.current().get(key));
                                                Span child =
                                                        telemetry
                                                                .getTracer("test")
                                                                .spanBuilder("child-" + id)
                                                                .startSpan();
                                                child.end();
                                                if (fail) {
                                                    throw new IllegalStateException(
                                                            "expected failure");
                                                }
                                                return id;
                                            })));
                } finally {
                    parent.end();
                }
            }
        } finally {
            releaseWorker.countDown();
        }
        for (int i = 0; i < tasks.size(); i++) {
            BackgroundTask task = tasks.get(i);
            assertTrue(task.waitForCompletion(5_000));
            assertEquals(fail ? TaskStatus.FAILED : TaskStatus.COMPLETED, task.getTaskStatus());
            if (fail) {
                assertEquals("expected failure", task.getError().getMessage());
            } else {
                assertEquals("request-" + i, task.getResult());
            }
            SpanData child = span("child-request-" + i);
            assertEquals(parents.get(i).getSpanContext().getTraceId(), child.getTraceId());
            assertEquals(parents.get(i).getSpanContext().getSpanId(), child.getParentSpanId());
        }
        assertNotEquals(
                parents.get(0).getSpanContext().getTraceId(),
                parents.get(1).getSpanContext().getTraceId());
        Context workerContext = executor.submit(Context::current).get(5, TimeUnit.SECONDS);
        assertNull(workerContext.get(key));
        assertFalse(Span.fromContext(workerContext).getSpanContext().isValid());
    }

    private SpanData span(String name) {
        List<SpanData> matches =
                exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().equals(name))
                        .toList();
        assertEquals(1, matches.size(), "Expected one span named " + name);
        return matches.get(0);
    }
}
