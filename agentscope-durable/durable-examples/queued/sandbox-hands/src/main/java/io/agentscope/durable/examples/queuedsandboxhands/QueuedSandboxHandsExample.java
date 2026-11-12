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
package io.agentscope.durable.examples.queuedsandboxhands;

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
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.Executor;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.extensions.durable.rocketmq.RocketMqRemoteExecutor;
import io.agentscope.extensions.durable.rocketmq.RocketMqWorker;
import io.agentscope.extensions.durable.sandbox.LocalSandboxClient;
import io.agentscope.extensions.durable.sandbox.SandboxBindings;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** A real RocketMQ Brain/Hands split; only the Worker can reach the local Sandbox. */
@Component
public final class QueuedSandboxHandsExample implements ApplicationRunner {
    static final String HARNESS = "queued-sandbox-demo";
    static final String COMMAND = "printf once >> count; printf recovered";
    static final String FINAL_ANSWER = "Queued sandbox result applied: recovered";
    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    private final QueuedSandboxHandsExampleProperties properties;

    public QueuedSandboxHandsExample(QueuedSandboxHandsExampleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateConfiguration();
        Path directory = Files.createDirectories(properties.getDataDirectory().toAbsolutePath());
        var config =
                ClientConfiguration.newBuilder().setEndpoints(properties.getEndpoint()).build();
        if (properties.getRole() == QueuedSandboxHandsExampleProperties.Role.WORKER) {
            try (var records = JdbcRecordStore.sqlite(directory.resolve("hands.db"))) {
                runWorker(config, records, directory);
            }
        } else {
            try (var backend = LocalBackend.open(directory)) {
                runBrain(config, backend);
            }
        }
    }

    private void runWorker(ClientConfiguration config, RecordStore records, Path directory)
            throws Exception {
        try (var bindings = sandboxBindings(records, directory);
                var worker =
                        RocketMqWorker.builder()
                                .executor("hands", bindings.executor(resourceBinding(), tool()))
                                .recordStore(records)
                                .clientConfiguration(config)
                                .namespace(properties.getMqNamespace())
                                .authorize(this::authorize)
                                .build()) {
            var stopped = new CountDownLatch(1);
            var hook =
                    new Thread(
                            () -> {
                                try {
                                    worker.close();
                                } finally {
                                    bindings.close();
                                    stopped.countDown();
                                }
                            },
                            "queued-sandbox-hands-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                bindings.start();
                worker.start();
                System.out.println("queued-sandbox-hands worker ready");
                stopped.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shuttingDown) {
                    // JVM shutdown already owns the hook.
                }
            }
        }
    }

    private void runBrain(ClientConfiguration config, SessionBackend backend) throws Exception {
        var remote =
                RocketMqRemoteExecutor.builder()
                        .clientConfiguration(config)
                        .namespace(properties.getMqNamespace())
                        .build();
        try (var runtime = runtime(backend, remote)) {
            var key = new SessionKey(properties.getNamespace(), properties.getSessionId());
            var session = runtime.createSession(key, HARNESS);
            var turn = session.submit(properties.getRequestId(), "Run the sandbox command");
            var completed = new CountDownLatch(1);
            try (var subscription =
                    turn.observe(
                            null,
                            snapshot -> {
                                if (snapshot.turns().stream()
                                        .anyMatch(
                                                value ->
                                                        turn.turnId().equals(value.turnId())
                                                                && value.status().terminal()))
                                    completed.countDown();
                            },
                            event -> {
                                if (terminalEvent(event.type())) completed.countDown();
                            })) {
                runtime.start();
                if (!completed.await(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS))
                    throw new IllegalStateException(
                            "Queued Sandbox Turn did not settle",
                            runtime.lastFailure(new ThreadKey(key, "main")));
                var result = turn.result();
                if (result.status() != TurnStatus.SUCCEEDED)
                    throw new IllegalStateException("Queued Sandbox Turn failed: " + result);
                String answer = result.output().path("content").path(0).path("text").asText();
                if (!FINAL_ANSWER.equals(answer))
                    throw new IllegalStateException(
                            "Queued Sandbox Turn failed verification: " + result);
                System.out.printf(
                        "queued-sandbox-hands brain verified turn=%s status=%s cursor=%s"
                                + " answer=%s%n",
                        turn.turnId(), result.status(), subscription.cursor(), answer);
            }
        }
    }

    static DurableRuntime runtime(SessionBackend backend, Executor executor) {
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding ->
                                ReActAgent.builder()
                                        .name(HARNESS)
                                        .model(offlineModel(binding.context().getTurnId()))
                                        .permissionContext(
                                                PermissionContextState.builder()
                                                        .mode(PermissionMode.BYPASS)
                                                        .build()),
                        List.of(tool()));
        return DurableRuntime.builder()
                .backend(backend)
                .harness(HARNESS, "v1", adapter)
                .executor("hands", executor)
                .build();
    }

    static SandboxBindings sandboxBindings(RecordStore records, Path workerDirectory) {
        return SandboxBindings.builder()
                .stateStore(records)
                .resolver(
                        binding ->
                                new LocalSandboxClient(workerDirectory.resolve("sandboxes"))
                                        .target(new WorkspaceSpec()))
                .build();
    }

    SandboxBindings.Binding resourceBinding() {
        return new SandboxBindings.Binding(
                properties.getNamespace(),
                properties.getSessionId(),
                "v1",
                DurableJson.value(Map.of()));
    }

    void authorize(Command command) {
        ExecutionMessages.validate(command);
        var request = command.request();
        if (!properties.getNamespace().equals(request.session().namespace())
                || !properties.getSessionId().equals(request.session().sessionId()))
            throw new SecurityException("Example permits only its configured namespace/session");
        tool().verify(request);
        if (!DurableJson.value(commandArguments()).equals(request.call().arguments()))
            throw new SecurityException(
                    "Example permits only its fixed Sandbox command and timeout");
    }

    static ToolBinding tool() {
        return new ToolBinding(
                "sandbox.exec",
                "v1",
                "hands",
                DurableJson.value(
                        Map.of(
                                "name",
                                "sandbox.exec",
                                "description",
                                "Run the fixed demonstration command in the durable Sandbox",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "command", Map.of("type", "string"),
                                                "timeout_seconds", Map.of("type", "integer")),
                                        "required",
                                        List.of("command", "timeout_seconds"),
                                        "additionalProperties",
                                        false))),
                DurableJson.value(Map.of("operation", "sandbox.exec")));
    }

    static Map<String, Object> commandArguments() {
        return Map.of("command", COMMAND, "timeout_seconds", COMMAND_TIMEOUT_SECONDS);
    }

    static Model offlineModel(String turnId) {
        String callId = "sandbox-" + turnId;
        return new Model() {
            @Override
            public String getModelName() {
                return "offline-queued-sandbox-demo";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                var applied =
                        messages.stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .filter(
                                        result ->
                                                callId.equals(result.getId())
                                                        && "sandbox.exec".equals(result.getName())
                                                        && !result.isSuspended())
                                .findFirst();
                if (applied.isEmpty()) {
                    var arguments = commandArguments();
                    return Flux.just(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id(callId)
                                                            .name("sandbox.exec")
                                                            .input(arguments)
                                                            .content(
                                                                    DurableJson.value(arguments)
                                                                            .toString())
                                                            .build()))
                                    .build());
                }
                boolean recovered =
                        applied
                                .filter(result -> result.getState() == ToolResultState.SUCCESS)
                                .stream()
                                .flatMap(result -> result.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .anyMatch(text -> recoveredOutput(text.getText()));
                if (!recovered)
                    return Flux.error(
                            new IllegalStateException(
                                    "Sandbox Hands did not return a successful recovered result"));
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text(FINAL_ANSWER).build()))
                                .build());
            }
        };
    }

    private static boolean recoveredOutput(String text) {
        try {
            var output = DurableJson.parse(text).path("output");
            return output.isTextual() && "recovered".equals(output.textValue());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static boolean terminalEvent(String type) {
        return "turn.succeeded".equals(type)
                || "turn.failed".equals(type)
                || "turn.cancelled".equals(type);
    }

    void validateConfiguration() {
        if (properties.getRole() == null
                || blank(properties.getEndpoint())
                || blank(properties.getNamespace())
                || blank(properties.getSessionId())
                || blank(properties.getRequestId())
                || blank(properties.getMqNamespace())
                || !properties.getMqNamespace().matches("[A-Za-z0-9_-]+")
                || properties.getTimeout() == null
                || properties.getTimeout().toMillis() < 1)
            throw new IllegalArgumentException(
                    "Complete RocketMQ and session configuration is required");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
