/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class LoadTestModelsTest {
    @Test
    void toolMarkerTemplateSurvivesSpringEnvironmentResolution() {
        var environment =
                new MockEnvironment()
                        .withProperty("managed-agents.runtime.load-test-model.enabled", "true")
                        .withProperty(
                                "managed-agents.runtime.load-test-model.profiles-json",
                                """
                                {"tool":{"chunks":1,"firstChunkDelayMs":0,"toolRounds":1,
                                "toolName":"longwait__long_wait","toolArguments":{"marker":"{{marker}}"}}}
                                """);
        var catalog =
                new LoadTestModels(
                        environment,
                        new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        var response =
                catalog.resolve("loadtest:tool").stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent(
                                                        "LOADTEST"
                                                            + " {\"request_id\":\"spring-request\",\"marker\":\"spring-marker\"}")
                                                .build()),
                                List.of(
                                        ToolSchema.builder()
                                                .name("longwait__long_wait")
                                                .description("Real tool marker fixture")
                                                .build()),
                                null)
                        .blockLast(Duration.ofSeconds(3));
        var call = (ToolUseBlock) response.getContent().get(1);
        assertEquals("spring-marker", call.getInput().get("marker"));
    }

    @Test
    void explicitEnableAndProfileAreRequiredAndMetricsAreSeparateFromProviderModels() {
        var registry = new SimpleMeterRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoadTestModels(false, "{}", registry).resolve("loadtest:short"));
        assertThrows(
                IllegalArgumentException.class, () -> new LoadTestModels(true, "{}", registry));
        var enabled = new LoadTestModels(true, "{\"short\":{\"chunks\":4}}", registry);
        assertEquals("loadtest:short", enabled.resolve("loadtest:short").getModelName());
        assertSame(enabled.resolve("loadtest:short"), enabled.resolve("loadtest:short"));
        assertThrows(IllegalArgumentException.class, () -> enabled.resolve("dashscope:qwen-max"));
        assertThrows(IllegalArgumentException.class, () -> enabled.resolve("loadtest:unknown"));
        assertEquals(
                0,
                registry.get("managed.agents.loadtest.model.active")
                        .tag("model", "loadtest:short")
                        .gauge()
                        .value());
    }
}
