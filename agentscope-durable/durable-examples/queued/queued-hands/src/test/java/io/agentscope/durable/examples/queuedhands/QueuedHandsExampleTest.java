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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class QueuedHandsExampleTest {
    @Test
    void validatesConfigurationAndItsExecutorContract() {
        var properties = new QueuedHandsExampleProperties();
        properties.setTimeout(Duration.ofSeconds(10));
        var example = new QueuedHandsExample(properties);
        example.validateConfiguration();
        var request =
                new ExecutionRequest(
                        "execution",
                        new ThreadKey(new SessionKey("demo", "session"), "main"),
                        "turn",
                        new ToolCall("call", "hands", "echo", DurableJson.value(Map.of())));

        var result = QueuedHandsExample.echoExecutor().startExecution(request);

        assertThat(result.state()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(result.handle()).isEqualTo("execution");
        assertThat(result.result().asText()).isEqualTo("echo: {}");
        assertThat(QueuedHandsExample.echoExecutor().query(request, "execution").state())
                .isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(QueuedHandsExample.echoExecutor().cancel(request, "execution").state())
                .isEqualTo(ExecutionState.CANCELLED);

        properties.setEndpoint(" ");
        assertThatThrownBy(example::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RocketMQ");
    }

    @Test
    void springConfigurationBindsRoleAndEndpoint() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "durable.queued-hands.role", "worker",
                                "durable.queued-hands.endpoint", "broker:8081"));

        var properties =
                new Binder(source)
                        .bind(
                                "durable.queued-hands",
                                Bindable.of(QueuedHandsExampleProperties.class))
                        .orElseThrow(() -> new AssertionError("Example properties did not bind"));

        assertThat(properties.getRole()).isEqualTo(QueuedHandsExampleProperties.Role.WORKER);
        assertThat(properties.getEndpoint()).isEqualTo("broker:8081");
    }
}
