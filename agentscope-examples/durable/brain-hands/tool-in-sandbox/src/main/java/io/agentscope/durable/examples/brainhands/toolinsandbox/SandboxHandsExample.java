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

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.agentscope.extensions.durable.rocketmq.RocketMqWorker;
import io.agentscope.extensions.durable.sandbox.LocalSandboxClient;
import io.agentscope.extensions.durable.sandbox.SandboxBindings;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Hands owns durable Sandbox bindings, authorization and the local executor. */
public final class SandboxHandsExample implements ApplicationRunner {
    private final SandboxToolProperties properties;

    public SandboxHandsExample(SandboxToolProperties properties) {
        this.properties = properties;
    }

    Path dataDirectory() {
        return properties.getDataDirectory() == null
                ? Path.of("durable-brain-hands-tool-in-sandbox-data", "hands")
                : properties.getDataDirectory();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        properties.validate();
        Path directory = Files.createDirectories(dataDirectory().toAbsolutePath());
        var config =
                ClientConfiguration.newBuilder().setEndpoints(properties.getEndpoint()).build();
        try (var records = JdbcRecordStore.sqlite(directory.resolve("hands.db"));
                var bindings = sandboxBindings(records, directory);
                var worker =
                        RocketMqWorker.builder()
                                .executor(
                                        "hands",
                                        bindings.executor(resourceBinding(), SandboxTool.tool()))
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
                                    try {
                                        bindings.close();
                                    } finally {
                                        stopped.countDown();
                                    }
                                }
                            },
                            "tool-in-sandbox-hands-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                bindings.start();
                worker.start();
                System.out.println("tool-in-sandbox hands ready");
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

    static SandboxBindings sandboxBindings(RecordStore records, Path handsDirectory) {
        return SandboxBindings.builder()
                .stateStore(records)
                .resolver(
                        binding ->
                                new LocalSandboxClient(handsDirectory.resolve("sandboxes"))
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
        SandboxTool.tool().verify(request);
        if (!DurableJson.value(SandboxTool.commandArguments()).equals(request.call().arguments()))
            throw new SecurityException(
                    "Example permits only its fixed Sandbox command and timeout");
    }
}
