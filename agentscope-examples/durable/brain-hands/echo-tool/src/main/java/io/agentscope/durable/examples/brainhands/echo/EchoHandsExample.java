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

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.Executor;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.rocketmq.RocketMqWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Hands owns the echo executor and execution ledger, without constructing an agent. */
public final class EchoHandsExample implements ApplicationRunner {
    private final EchoToolProperties properties;

    public EchoHandsExample(EchoToolProperties properties) {
        this.properties = properties;
    }

    Path dataDirectory() {
        return properties.getDataDirectory() == null
                ? Path.of("durable-echo-tool-data", "hands")
                : properties.getDataDirectory();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        properties.validate();
        Files.createDirectories(dataDirectory());
        var config =
                ClientConfiguration.newBuilder().setEndpoints(properties.getEndpoint()).build();
        try (var records = JdbcRecordStore.sqlite(dataDirectory().resolve("hands.db"));
                var worker =
                        RocketMqWorker.builder()
                                .executor("hands", echoExecutor())
                                .recordStore(records)
                                .clientConfiguration(config)
                                .authorize(this::authorize)
                                .build()) {
            var stopped = new CountDownLatch(1);
            var hook =
                    new Thread(
                            () -> {
                                try {
                                    worker.close();
                                } finally {
                                    stopped.countDown();
                                }
                            },
                            "echo-tool-hands-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                worker.start();
                System.out.println("echo-tool hands ready");
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

    void authorize(Command command) {
        ExecutionMessages.validate(command);
        if (!properties.getNamespace().equals(command.request().session().namespace())
                || !"echo".equals(command.request().call().name()))
            throw new SecurityException("Example permits only its namespace/echo");
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
}
