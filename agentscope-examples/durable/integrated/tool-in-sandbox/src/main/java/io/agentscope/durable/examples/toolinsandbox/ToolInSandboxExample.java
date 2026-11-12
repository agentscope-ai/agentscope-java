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
package io.agentscope.durable.examples.toolinsandbox;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.durable.worker.WorkerRuntime;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.extensions.durable.sandbox.LocalSandboxClient;
import io.agentscope.extensions.durable.sandbox.SandboxBindings;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Complete Brain-to-Sandbox-Hands recovery across two Spring Boot processes. */
@Component
public final class ToolInSandboxExample implements ApplicationRunner {
    private static final String HARNESS = "sandbox-demo";
    private static final String TOOL_CALL_ID = "sandbox-1";
    private static final String FINAL_ANSWER = "Sandbox result applied: recovered";

    public record Result(
            ToolInSandboxExampleProperties.Phase phase,
            String turnId,
            TurnStatus turnStatus,
            ExecutionState executionState,
            String resourceId,
            boolean deleted,
            int modelInvocations,
            String answer) {}

    private final ToolInSandboxExampleProperties properties;

    public ToolInSandboxExample(ToolInSandboxExampleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var result = execute(properties.getPhase());
        System.out.printf(
                "tool-in-sandbox verified phase=%s turn=%s turnStatus=%s executionState=%s"
                        + " resource=%s deleted=%s modelInvocations=%d answer=%s%n",
                result.phase(),
                result.turnId(),
                result.turnStatus(),
                result.executionState(),
                result.resourceId(),
                result.deleted(),
                result.modelInvocations(),
                result.answer());
    }

    Result execute(ToolInSandboxExampleProperties.Phase phase) throws Exception {
        Path data = Files.createDirectories(properties.getDataDirectory().toAbsolutePath());
        Path modelCalls = data.resolve("model-calls");
        var local = new LocalSandboxClient(data.resolve("sandboxes"));
        var binding =
                new SandboxBindings.Binding(
                        properties.getNamespace(), "workspace", "v1", DurableJson.value(Map.of()));
        var tool = sandboxTool();
        var executionsSettled = new AtomicBoolean();
        try (var backend = LocalBackend.open(data.resolve("brain"));
                var handsRecords = JdbcRecordStore.sqlite(data.resolve("hands.db"));
                var sandboxes =
                        SandboxBindings.builder()
                                .stateStore(handsRecords)
                                .resolver(ignored -> local.target(new WorkspaceSpec()))
                                .canDelete(ignored -> executionsSettled.get())
                                .build();
                var worker =
                        WorkerRuntime.builder()
                                .recordStore(handsRecords)
                                .executor("hands", sandboxes.executor(binding, tool))
                                .build();
                var runtime = runtime(backend, worker, tool, modelCalls)) {
            var key = new SessionKey(properties.getNamespace(), properties.getSessionId());
            var thread = new ThreadKey(key, "main");
            var session =
                    phase == ToolInSandboxExampleProperties.Phase.START
                            ? runtime.createSession(key, HARNESS)
                            : runtime.getSession(key);
            var turn = session.submit(properties.getRequestId(), "Run the sandbox command");
            if (phase == ToolInSandboxExampleProperties.Phase.START) {
                awaitWaitingTurn(runtime, turn, thread, properties.getTimeout());
                runtime.tick(thread);
                worker.tick();
                String resourceId = sandboxes.status(binding).resourceId();
                Path original = data.resolve("sandboxes").resolve(resourceId);
                awaitFile(original.resolve("launched"), properties.getTimeout());
                if (Files.exists(original.resolve("count")))
                    throw new IllegalStateException("Sandbox gate was bypassed");
                var view = turn.status();
                if (view.status() != TurnStatus.WAITING || worker.statistics(10).pending() != 1)
                    throw new IllegalStateException(
                            "Brain or Hands did not suspend with the operation running");
                return new Result(
                        phase,
                        turn.turnId(),
                        view.status(),
                        ExecutionState.RUNNING,
                        resourceId,
                        false,
                        modelInvocationCount(modelCalls),
                        null);
            }

            String resourceId = sandboxes.status(binding).resourceId();
            Path original = data.resolve("sandboxes").resolve(resourceId);
            Files.writeString(original.resolve("continue"), "continue");
            long deadline = System.nanoTime() + properties.getTimeout().toNanos();
            while (!turn.status().status().terminal()) {
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("Recovered Brain Turn did not settle");
                worker.tick();
                runtime.tick(thread);
                Thread.sleep(20);
            }
            var result = turn.result();
            if (result.status() != TurnStatus.SUCCEEDED
                    || worker.statistics(10).pending() != 0
                    || !"once".equals(Files.readString(original.resolve("count"))))
                throw new IllegalStateException(
                        "Brain or Sandbox effect was not completed exactly once");
            String answer = result.output().path("content").get(0).path("text").asText();
            if (!FINAL_ANSWER.equals(answer) || modelInvocationCount(modelCalls) != 2)
                throw new IllegalStateException("Brain did not resume from the Hands result");
            executionsSettled.set(true);
            sandboxes.requestDeletion(binding);
            sandboxes.tick();
            boolean deleted = sandboxes.status(binding).deleted() && !Files.exists(original);
            if (!deleted) throw new IllegalStateException("Sandbox workspace was not cleaned");
            return new Result(
                    phase,
                    turn.turnId(),
                    result.status(),
                    ExecutionState.SUCCEEDED,
                    resourceId,
                    true,
                    modelInvocationCount(modelCalls),
                    answer);
        }
    }

    private DurableRuntime runtime(
            SessionBackend backend, WorkerRuntime worker, ToolBinding tool, Path modelCalls) {
        Model model = offlineModel(modelCalls);
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding ->
                                ReActAgent.builder()
                                        .name(HARNESS)
                                        .model(model)
                                        .permissionContext(
                                                PermissionContextState.builder()
                                                        .mode(PermissionMode.BYPASS)
                                                        .build()),
                        List.of(tool));
        return DurableRuntime.builder()
                .backend(backend)
                .harness(HARNESS, "v1", adapter)
                .executor("hands", worker.localExecutor())
                .build();
    }

    private ToolBinding sandboxTool() {
        return new ToolBinding(
                "sandbox.exec",
                "v1",
                "hands",
                DurableJson.value(
                        Map.of(
                                "name",
                                "sandbox.exec",
                                "description",
                                "Run a command in the durable sandbox",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "command",
                                                Map.of("type", "string"),
                                                "timeout_seconds",
                                                Map.of("type", "integer")),
                                        "required",
                                        List.of("command")))),
                DurableJson.value(Map.of("operation", "sandbox.exec")));
    }

    private Model offlineModel(Path calls) {
        return new Model() {
            @Override
            public String getModelName() {
                return "offline-sandbox-demo";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                try {
                    Files.writeString(
                            calls, "call\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception error) {
                    return Flux.error(error);
                }
                Optional<ToolResultBlock> applied =
                        messages.stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .filter(
                                        result ->
                                                TOOL_CALL_ID.equals(result.getId())
                                                        && !result.isSuspended())
                                .findFirst();
                boolean recovered =
                        applied
                                .filter(result -> result.getState() == ToolResultState.SUCCESS)
                                .stream()
                                .flatMap(result -> result.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .anyMatch(text -> recoveredOutput(text.getText()));
                var arguments =
                        Map.<String, Object>of(
                                "command",
                                "printf ready > launched; while [ ! -f continue ]; do sleep"
                                        + " 0.05; done; printf once >> count; printf recovered",
                                "timeout_seconds",
                                Math.max(1, properties.getTimeout().toSeconds()));
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        recovered
                                                ? List.of(
                                                        TextBlock.builder()
                                                                .text(FINAL_ANSWER)
                                                                .build())
                                                : applied.isEmpty()
                                                        ? List.of(
                                                                ToolUseBlock.builder()
                                                                        .id(TOOL_CALL_ID)
                                                                        .name("sandbox.exec")
                                                                        .input(arguments)
                                                                        .content(
                                                                                DurableJson.value(
                                                                                                arguments)
                                                                                        .toString())
                                                                        .build())
                                                        : List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "Sandbox Hands did"
                                                                                    + " not return"
                                                                                    + " recovered")
                                                                        .build()))
                                .build());
            }
        };
    }

    private static boolean recoveredOutput(String text) {
        try {
            return "recovered".equals(DurableJson.parse(text).path("output").asText());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void awaitWaitingTurn(
            DurableRuntime runtime, DurableTurn turn, ThreadKey thread, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var status = turn.status().status();
            if (status == TurnStatus.WAITING) return;
            if (status.terminal())
                throw new IllegalStateException(
                        "Brain finished before producing the Sandbox tool call: " + status);
            runtime.tick(thread);
            if (System.nanoTime() > deadline)
                throw new IllegalStateException(
                        "Brain did not suspend for the Sandbox tool call",
                        runtime.lastFailure(thread));
            Thread.sleep(20);
        }
    }

    private static int modelInvocationCount(Path calls) throws Exception {
        return Files.exists(calls) ? Files.readAllLines(calls).size() : 0;
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() > deadline)
                throw new IllegalStateException("Timed out waiting for " + path);
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}
