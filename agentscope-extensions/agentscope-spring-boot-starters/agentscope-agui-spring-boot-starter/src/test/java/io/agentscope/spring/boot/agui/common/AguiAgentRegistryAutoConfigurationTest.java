/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.model.DashScopeChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unit tests for {@link AguiAgentRegistryAutoConfiguration}.
 */
@Tag("unit")
@DisplayName("AguiAgentRegistryAutoConfiguration Unit Tests")
class AguiAgentRegistryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    @DisplayName("Should auto-configure AguiAgentRegistry and AguiAgentAutoRegistration")
    void testAutoConfiguration() {
        contextRunner
                .withUserConfiguration(AguiAgentRegistryAutoConfiguration.class)
                .run(
                        ctx -> {
                            assertNotNull(ctx.getBean(AguiAgentRegistry.class));
                            assertNotNull(ctx.getBean(AguiAgentAutoRegistration.class));
                        });
    }

    @Test
    @DisplayName("Should customize registering Agent beans to the AguiAgentRegistry")
    void testAguiAgentRegistryCustomizer() {
        contextRunner
                .withUserConfiguration(AguiAgentRegistryAutoConfiguration.class, TestConfig.class)
                .run(
                        ctx -> {
                            AguiAgentRegistry registry = ctx.getBean(AguiAgentRegistry.class);
                            assertNotNull(registry);
                            assertNotNull(ctx.getBean(AguiAgentAutoRegistration.class));
                            assertTrue(registry.hasAgent("customize-agent"));
                        });
    }

    @Test
    @DisplayName("Should automatically registering Agent beans to the AguiAgentRegistry")
    void testAguiAgentAutoRegistration() {
        contextRunner
                .withUserConfiguration(AguiAgentRegistryAutoConfiguration.class, TestConfig.class)
                .run(
                        ctx -> {
                            AguiAgentRegistry registry = ctx.getBean(AguiAgentRegistry.class);
                            assertNotNull(registry);
                            assertNotNull(ctx.getBean(AguiAgentAutoRegistration.class));
                            assertTrue(registry.hasAgent("annotation-agent"));
                        });
    }

    @Configuration
    static class TestConfig {

        @Bean
        public AguiAgentRegistryCustomizer customizer() {
            return registry ->
                    registry.registerFactory(
                            "customize-agent",
                            AguiAgentRegistryAutoConfigurationTest::createTestAgent);
        }

        @Bean
        @AguiAgentId("annotation-agent")
        public Agent annotationAgent() {
            return createTestAgent();
        }
    }

    private static Agent createTestAgent() {
        return ReActAgent.builder()
                .name("test")
                .model(DashScopeChatModel.builder().apiKey("ak-123").build())
                .build();
    }
}
