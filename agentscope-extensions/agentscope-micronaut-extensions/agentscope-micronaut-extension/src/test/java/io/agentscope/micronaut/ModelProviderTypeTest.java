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
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.micronaut.model.ModelProviderType;
import io.agentscope.micronaut.properties.AgentscopeProperties;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for ModelProviderType enum and model creation logic.
 */
class ModelProviderTypeTest {

    @Test
    void shouldThrowExceptionWhenPropertiesIsNull() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelProviderType.fromProperties(null));

        assertEquals("AgentscopeProperties cannot be null", exception.getMessage());
    }

    @Test
    void shouldDefaultToDashScopeWhenProviderIsNull() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.enabled",
                                "false"))) {

            AgentscopeProperties properties = ctx.getBean(AgentscopeProperties.class);
            ModelProviderType provider = ModelProviderType.fromProperties(properties);

            assertEquals(ModelProviderType.DASHSCOPE, provider);
        }
    }

    @Test
    void shouldDefaultToDashScopeWhenProviderIsEmpty() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.enabled",
                                "false"))) {

            AgentscopeProperties properties = ctx.getBean(AgentscopeProperties.class);
            ModelProviderType provider = ModelProviderType.fromProperties(properties);

            assertEquals(ModelProviderType.DASHSCOPE, provider);
        }
    }

    @Test
    void shouldThrowExceptionForUnsupportedProvider() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "unknown-provider",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.enabled",
                                "false"))) {

            AgentscopeProperties properties = ctx.getBean(AgentscopeProperties.class);

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> ModelProviderType.fromProperties(properties));

            assertEquals(
                    "Unsupported agentscope.model.provider: unknown-provider",
                    exception.getMessage());
        }
    }
}
