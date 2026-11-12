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
package io.agentscope.durable.examples.queuedhands;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.Executor;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.extensions.durable.rocketmq.RocketMqRemoteExecutor;
import io.agentscope.extensions.durable.rocketmq.RocketMqWorker;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Spring-configured Brain or Hands process connected through RocketMQ 5. */
@Component
public final class QueuedHandsExample implements ApplicationRunner {
    private final QueuedHandsExampleProperties properties;

    public QueuedHandsExample(QueuedHandsExampleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateConfiguration();
        Files.createDirectories(properties.getDataDirectory());
        boolean workerRole = properties.getRole() == QueuedHandsExampleProperties.Role.WORKER;
        var config =
                ClientConfiguration.newBuilder().setEndpoints(properties.getEndpoint()).build();
        if (workerRole)
            try (var records =
                    JdbcRecordStore.sqlite(properties.getDataDirectory().resolve("durable.db"))) {
                runWorker(config, records);
            }
        else
            try (var backend = LocalBackend.open(properties.getDataDirectory())) {
                runBrain(config, backend);
            }
    }

    private void runWorker(ClientConfiguration config, JdbcRecordStore records) throws Exception {
        try (var worker =
                RocketMqWorker.builder()
                        .executor("hands", echoExecutor())
                        .recordStore(records)
                        .clientConfiguration(config)
                        .authorize(
                                command -> {
                                    if (!properties
                                                    .getNamespace()
                                                    .equals(command.request().session().namespace())
                                            || !"echo".equals(command.request().call().name()))
                                        throw new SecurityException(
                                                "Example permits only its namespace/echo");
                                })
                        .build()) {
            var stopped = new CountDownLatch(1);
            var hook =
                    new Thread(
                            () -> {
                                worker.close();
                                stopped.countDown();
                            },
                            "queued-hands-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            worker.start();
            System.out.println("queued-hands worker ready");
            try {
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
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding -> {
                            var toolkit = new Toolkit();
                            toolkit.registerSchema(
                                    ToolSchema.builder()
                                            .name("echo")
                                            .description("Remote echo")
                                            .parameters(
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of()))
                                            .build());
                            return ReActAgent.builder()
                                    .name("queued-demo")
                                    .model(offlineModel())
                                    .stateStore(new InMemoryAgentStateStore())
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of("echo", "hands"));
        var executor = RocketMqRemoteExecutor.builder().clientConfiguration(config).build();
        try (var runtime =
                DurableRuntime.builder()
                        .backend(backend)
                        .harness("queued-demo", "v1", adapter)
                        .executor("hands", executor)
                        .build()) {
            var key = new SessionKey(properties.getNamespace(), properties.getSessionId());
            var session = runtime.createSession(key, "queued-demo");
            var turn = session.submit(properties.getRequestId(), "Call echo");
            var completed = new CountDownLatch(1);
            try (var subscription =
                    turn.observe(
                            null,
                            snapshot -> {
                                if (snapshot.turns().stream()
                                        .anyMatch(value -> value.status() == TurnStatus.SUCCEEDED))
                                    completed.countDown();
                            },
                            event -> {
                                if ("turn.succeeded".equals(event.type())) completed.countDown();
                            })) {
                runtime.start();
                if (!completed.await(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS))
                    throw new IllegalStateException(
                            "Queued Turn did not complete",
                            runtime.lastFailure(new ThreadKey(key, "main")));
                var result = turn.result();
                if (result.status() != TurnStatus.SUCCEEDED)
                    throw new IllegalStateException("Queued Turn failed: " + result);
                System.out.printf(
                        "queued-hands brain verified turn=%s status=%s cursor=%s%n",
                        turn.turnId(), result.status(), subscription.cursor());
            }
        }
    }

    void validateConfiguration() {
        if (properties.getTimeout().isZero()
                || properties.getTimeout().isNegative()
                || blank(properties.getEndpoint()))
            throw new IllegalArgumentException("Complete RocketMQ configuration is required");
    }

    static Executor echoExecutor() {
        return new Executor() {
            @Override
            public Capabilities capabilities() {
                return new Capabilities(true, true, true);
            }

            @Override
            public ExecutionResult startExecution(ExecutionRequest request) {
                return query(request, null);
            }

            @Override
            public ExecutionResult query(ExecutionRequest request, String handle) {
                return new ExecutionResult(
                        ExecutionState.SUCCEEDED,
                        request.executionId(),
                        DurableJson.value("echo: " + request.call().arguments()),
                        null);
            }

            @Override
            public ExecutionResult cancel(ExecutionRequest request, String handle) {
                return new ExecutionResult(
                        ExecutionState.CANCELLED, request.executionId(), null, null);
            }
        };
    }

    static Model offlineModel() {
        return new Model() {
            @Override
            public String getModelName() {
                return "offline-queued-demo";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                boolean applied =
                        messages.stream()
                                .flatMap(
                                        message ->
                                                message
                                                        .getContentBlocks(ToolResultBlock.class)
                                                        .stream())
                                .anyMatch(result -> !result.isSuspended());
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        applied
                                                ? List.of(
                                                        TextBlock.builder()
                                                                .text("Hands result applied")
                                                                .build())
                                                : List.of(
                                                        ToolUseBlock.builder()
                                                                .id("echo-1")
                                                                .name("echo")
                                                                .input(Map.of())
                                                                .build()))
                                .build());
            }
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
