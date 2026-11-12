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
package io.agentscope.durable.examples.quickstart;

import static org.assertj.core.api.Assertions.assertThat;

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
class QuickstartExampleTest {
    @TempDir Path directory;

    @Test
    void reusesTheDurableTurnWithoutRepeatingTheModelCall() throws Exception {
        var properties = new QuickstartExampleProperties();
        properties.setDataDirectory(directory.resolve("quickstart"));
        properties.setNamespace("test");
        properties.setSessionId("quickstart");
        properties.setRequestId("request");
        properties.setTimeout(Duration.ofSeconds(10));

        var first = new QuickstartExample(properties).execute();
        var replay = new QuickstartExample(properties).execute();

        assertThat(first.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(first.modelInvocations()).isEqualTo(1);
        assertThat(first.runs()).isPositive();
        assertThat(first.modelCalls()).isPositive();
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.modelInvocations()).isZero();
        assertThat(replay.runs()).isEqualTo(first.runs());
        assertThat(replay.modelCalls()).isEqualTo(first.modelCalls());
    }

    @Test
    void springConfigurationBindsQuickstartProperties() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "durable.quickstart.data-directory", "data",
                                "durable.quickstart.request-id", "request-2",
                                "durable.quickstart.timeout", "5s"));

        var properties =
                new Binder(source)
                        .bind("durable.quickstart", Bindable.of(QuickstartExampleProperties.class))
                        .orElseThrow(() -> new AssertionError("Example properties did not bind"));

        assertThat(properties.getDataDirectory()).isEqualTo(Path.of("data"));
        assertThat(properties.getRequestId()).isEqualTo("request-2");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
