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
package io.agentscope.durable.examples.brainhands.echo;

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
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.Executor;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.SessionStreamEvent;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.extensions.durable.rocketmq.RocketMqRemoteExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import reactor.core.publisher.Flux;

/** Brain assembles the agent and routes echo calls to remote Hands only. */
public final class EchoBrainExample implements ApplicationRunner {
    static final String HARNESS = "echo-demo";
    static final String FINAL_ANSWER = "Hands result applied";
    private final EchoToolProperties properties;

    public EchoBrainExample(EchoToolProperties properties) {
        this.properties = properties;
    }

    Path dataDirectory() {
        return properties.getDataDirectory() == null
                ? Path.of("durable-echo-tool-data", "brain")
                : properties.getDataDirectory();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        properties.validate();
        Files.createDirectories(dataDirectory());
        var config =
                ClientConfiguration.newBuilder().setEndpoints(properties.getEndpoint()).build();
        var executor = RocketMqRemoteExecutor.builder().clientConfiguration(config).build();
        try (var backend = LocalBackend.open(dataDirectory());
                var runtime = runtime(backend, executor)) {
            var key = new SessionKey(properties.getNamespace(), properties.getSessionId());
            var session = runtime.createSession(key, HARNESS);
            var turn = session.submit(properties.getRequestId(), "Call echo");
            var completed = new CountDownLatch(1);
            try (var subscription =
                    turn.subscribeEvents(
                            EventStreamOptions.defaults(),
                            event -> {
                                if (event instanceof SessionStreamEvent.SnapshotReset reset
                                        && reset.snapshot().turns().stream()
                                                .anyMatch(
                                                        value ->
                                                                turn.turnId().equals(value.turnId())
                                                                        && value.status()
                                                                                .terminal())) {
                                    completed.countDown();
                                } else if (event instanceof SessionStreamEvent.Committed committed
                                        && terminalEvent(committed.event().type())) {
                                    completed.countDown();
                                }
                            })) {
                runtime.start();
                if (!completed.await(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS))
                    throw new IllegalStateException(
                            "Echo Turn did not complete",
                            runtime.lastFailure(new ThreadKey(key, "main")));
                var result = turn.result();
                if (result.status() != TurnStatus.SUCCEEDED)
                    throw new IllegalStateException("Echo Turn failed: " + result);
                if (!FINAL_ANSWER.equals(
                        result.output().path("content").path(0).path("text").asText()))
                    throw new IllegalStateException("Echo Turn failed verification: " + result);
                System.out.printf(
                        "echo-tool brain verified turn=%s status=%s cursor=%s%n",
                        turn.turnId(), result.status(), subscription.cursor());
            }
        }
    }

    static DurableRuntime runtime(SessionBackend backend, Executor executor) {
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
                                    .name(HARNESS)
                                    .model(offlineModel(binding.context().getTurnId()))
                                    .stateStore(new InMemoryAgentStateStore())
                                    .toolkit(toolkit)
                                    .permissionContext(
                                            PermissionContextState.builder()
                                                    .mode(PermissionMode.BYPASS)
                                                    .build());
                        },
                        Map.of("echo", "hands"));
        return DurableRuntime.builder()
                .backend(backend)
                .harness(HARNESS, "v1", adapter)
                .executor("hands", executor)
                .build();
    }

    static Model offlineModel(String turnId) {
        String callId = "echo-" + turnId;
        return new Model() {
            @Override
            public String getModelName() {
                return "offline-echo-demo";
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
                                                        && "echo".equals(result.getName())
                                                        && !result.isSuspended())
                                .findFirst();
                if (applied.isEmpty())
                    return Flux.just(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id(callId)
                                                            .name("echo")
                                                            .input(Map.of())
                                                            .build()))
                                    .build());
                if (applied.get().getState() != ToolResultState.SUCCESS)
                    return Flux.error(
                            new IllegalStateException("Echo Hands did not return success"));
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text(FINAL_ANSWER).build()))
                                .build());
            }
        };
    }

    static boolean terminalEvent(String type) {
        return "turn.succeeded".equals(type)
                || "turn.failed".equals(type)
                || "turn.cancelled".equals(type);
    }
}
