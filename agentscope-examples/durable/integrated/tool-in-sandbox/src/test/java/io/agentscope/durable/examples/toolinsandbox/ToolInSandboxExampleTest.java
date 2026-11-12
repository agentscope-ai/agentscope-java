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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.TurnStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@Timeout(30)
class ToolInSandboxExampleTest {
    @TempDir Path directory;

    @Test
    void recoversBrainAndDetachedWork() throws Exception {
        var properties = new ToolInSandboxExampleProperties();
        properties.setDataDirectory(directory.resolve("sandbox"));
        properties.setNamespace("test");
        properties.setSessionId("sandbox");
        properties.setRequestId("request");
        properties.setTimeout(Duration.ofSeconds(10));

        var started =
                new ToolInSandboxExample(properties)
                        .execute(ToolInSandboxExampleProperties.Phase.START);
        var resumed =
                new ToolInSandboxExample(properties)
                        .execute(ToolInSandboxExampleProperties.Phase.RESUME);

        assertThat(started.turnStatus()).isEqualTo(TurnStatus.WAITING);
        assertThat(started.executionState()).isEqualTo(ExecutionState.RUNNING);
        assertThat(started.modelInvocations()).isEqualTo(1);
        assertThat(started.deleted()).isFalse();
        assertThat(resumed.turnId()).isEqualTo(started.turnId());
        assertThat(resumed.resourceId()).isEqualTo(started.resourceId());
        assertThat(resumed.turnStatus()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(resumed.executionState()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(resumed.modelInvocations()).isEqualTo(2);
        assertThat(resumed.answer()).isEqualTo("Sandbox result applied: recovered");
        assertThat(resumed.deleted()).isTrue();
    }

    @Test
    void springConfigurationBindsPhase() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "durable.tool-in-sandbox.phase", "resume",
                                "durable.tool-in-sandbox.data-directory", "data"));

        var properties =
                new Binder(source)
                        .bind(
                                "durable.tool-in-sandbox",
                                Bindable.of(ToolInSandboxExampleProperties.class))
                        .orElseThrow(() -> new AssertionError("Example properties did not bind"));

        assertThat(properties.getPhase()).isEqualTo(ToolInSandboxExampleProperties.Phase.RESUME);
        assertThat(properties.getDataDirectory()).isEqualTo(Path.of("data"));
    }
}
