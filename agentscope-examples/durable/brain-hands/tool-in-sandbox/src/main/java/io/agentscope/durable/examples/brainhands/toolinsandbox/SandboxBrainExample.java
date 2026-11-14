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
package io.agentscope.durable.examples.brainhands.toolinsandbox;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import reactor.core.publisher.Flux;

/** Brain assembles the agent; only remote Hands can reach the Sandbox. */
public final class SandboxBrainExample implements ApplicationRunner {
    static final String HARNESS = "brain-hands-sandbox-demo";
    private final SandboxToolProperties properties;

    public SandboxBrainExample(SandboxToolProperties properties) {
        this.properties = properties;
    }

    Path dataDirectory() {
        return properties.getDataDirectory() == null
                ? Path.of("durable-brain-hands-tool-in-sandbox-data", "brain")
                : properties.getDataDirectory();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        properties.validate();
        var directory = Files.createDirectories(dataDirectory().toAbsolutePath());
        var config =
                ClientConfiguration.newBuilder().setEndpoints(properties.getEndpoint()).build();
        var remote =
                RocketMqRemoteExecutor.builder()
                        .clientConfiguration(config)
                        .namespace(properties.getMqNamespace())
                        .build();
        try (var backend = LocalBackend.open(directory);
                var runtime = runtime(backend, remote)) {
            var key = new SessionKey(properties.getNamespace(), properties.getSessionId());
            var session = runtime.createSession(key, HARNESS);
            var turn = session.submit(properties.getRequestId(), "Run the sandbox command");
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
                            "Sandbox Turn did not settle",
                            runtime.lastFailure(new ThreadKey(key, "main")));
                var result = turn.result();
                if (result.status() != TurnStatus.SUCCEEDED)
                    throw new IllegalStateException("Sandbox Turn failed: " + result);
                String answer = result.output().path("content").path(0).path("text").asText();
                if (!SandboxTool.FINAL_ANSWER.equals(answer))
                    throw new IllegalStateException("Sandbox Turn failed verification: " + result);
                System.out.printf(
                        "tool-in-sandbox brain verified turn=%s status=%s cursor=%s answer=%s%n",
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
                        List.of(SandboxTool.tool()));
        return DurableRuntime.builder()
                .backend(backend)
                .harness(HARNESS, "v1", adapter)
                .executor("hands", executor)
                .build();
    }

    static Model offlineModel(String turnId) {
        String callId = "sandbox-" + turnId;
        return new Model() {
            @Override
            public String getModelName() {
                return "offline-brain-hands-sandbox-demo";
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
                    var arguments = SandboxTool.commandArguments();
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
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(SandboxTool.FINAL_ANSWER)
                                                        .build()))
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
}
