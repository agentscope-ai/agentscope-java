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
package io.agentscope.spring.boot.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.spec.AgentCard;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.spring.boot.a2a.controller.A2aJsonRpcController;
import io.agentscope.spring.boot.a2a.controller.AgentCardController;
import io.agentscope.spring.boot.a2a.listener.ServerReadyListener;
import io.agentscope.spring.boot.a2a.properties.A2aAgentCardProperties;
import io.agentscope.spring.boot.a2a.properties.A2aCommonProperties;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.context.ConfigurableWebApplicationContext;

/**
 * Tests for {@link AgentscopeA2aAutoConfiguration}.
 *
 * <p>These tests verify that the auto-configuration creates the expected beans under different
 * property setups.
 */
class AgentscopeA2aAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                    .withBean(
                            ReActAgent.class, () -> ReActAgent.builder().name("mockAgent").build())
                    .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080");

    @Test
    void shouldCreateDefaultBeansWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AgentRunner.class);
                    assertThat(context).hasSingleBean(AgentScopeA2aServer.class);
                    assertThat(context).hasSingleBean(AgentCardController.class);
                    assertThat(context).hasSingleBean(A2aJsonRpcController.class);
                });
    }

    @Test
    void shouldNotCreateA2aBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("agentscope.a2a.server.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(AgentScopeA2aServer.class);
                            assertThat(context).doesNotHaveBean(AgentCardController.class);
                            assertThat(context).doesNotHaveBean(A2aJsonRpcController.class);
                        });
    }

    @Test
    void shouldCreateAgentRunnerWithReActAgent() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(AgentRunner.class));
    }

    @Test
    void shouldCreateAgentRunnerWithReActAgentBuilder() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                .withBean(ReActAgent.Builder.class, () -> ReActAgent.builder().name("mockAgent"))
                .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080")
                .run(context -> assertThat(context).hasSingleBean(AgentRunner.class));
    }

    @Test
    void shouldCreateWithCustomAgentRunner() {
        AgentRunner mockRunner = mock(AgentRunner.class);
        when(mockRunner.getAgentName()).thenReturn("mock Agent");
        when(mockRunner.getAgentDescription()).thenReturn("mock Description");
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                .withBean(AgentRunner.class, () -> mockRunner)
                .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080")
                .run(context -> assertThat(context).hasSingleBean(AgentRunner.class));
    }

    @Test
    void shouldCreateControllersWhenA2aServerExists() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AgentCardController.class);
                    assertThat(context).hasSingleBean(A2aJsonRpcController.class);
                });
    }

    @Test
    void shouldBindA2aProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.enabled=true",
                        "agentscope.a2a.server.card.name=Test Agent",
                        "agentscope.a2a.server.card.description=Test Description")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(A2aCommonProperties.class);
                            assertThat(context).hasSingleBean(A2aAgentCardProperties.class);

                            A2aAgentCardProperties cardProps =
                                    context.getBean(A2aAgentCardProperties.class);
                            assertThat(cardProps.getName()).isEqualTo("Test Agent");
                            assertThat(cardProps.getDescription()).isEqualTo("Test Description");

                            assertThat(context).hasSingleBean(AgentCardController.class);
                            AgentCardController controller =
                                    context.getBean(AgentCardController.class);
                            AgentCard agentCard = controller.getAgentCard();
                            assertThat(agentCard.name()).isEqualTo("Test Agent");
                            assertThat(agentCard.description()).isEqualTo("Test Description");
                        });
    }

    @Test
    void shouldStartSuccessWithoutServerProperties() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build());
        // In new version, the port has default value 8080, follow the spring boot web default
        // value.
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldStartSuccessWithAddressProperties() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build())
                        .withPropertyValues("server.address=localhost");
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    AgentScopeA2aServer server = context.getBean(AgentScopeA2aServer.class);
                    AgentCard agentCard = server.getAgentCard();
                    Assertions.assertEquals("http://localhost:8080", agentCard.url());
                });
    }

    @Test
    void shouldCallbackServerReadyListener() {
        AgentScopeA2aServer mockServer = mock(AgentScopeA2aServer.class);
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(AgentScopeA2aServer.class, () -> mockServer);
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ServerReadyListener.class);
                    SpringApplication mockApplication = mock(SpringApplication.class);
                    ApplicationReadyEvent event =
                            new ApplicationReadyEvent(
                                    mockApplication,
                                    null,
                                    context.getSourceApplicationContext(
                                            ConfigurableWebApplicationContext.class),
                                    Duration.ofMillis(10));
                    context.publishEvent(event);
                    verify(mockServer).postEndpointReady();
                });
    }

    @Test
    void shouldStartWithA2aServerProperties() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build())
                        .withPropertyValues(
                                "agentscope.a2a.server.complete-with-message=true",
                                "agentscope.a2a.server.require-inner-message=true");
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(A2aCommonProperties.class);
                    A2aCommonProperties a2aCommonProperties =
                            context.getBean(A2aCommonProperties.class);
                    Assertions.assertTrue(a2aCommonProperties.isCompleteWithMessage());
                    Assertions.assertTrue(a2aCommonProperties.isRequireInnerMessage());
                });
    }
}
