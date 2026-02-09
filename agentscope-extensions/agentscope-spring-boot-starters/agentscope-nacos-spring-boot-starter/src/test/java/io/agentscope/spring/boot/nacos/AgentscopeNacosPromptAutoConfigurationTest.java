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

package io.agentscope.spring.boot.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosPromptProperties;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Unit tests for {@link AgentscopeNacosPromptAutoConfiguration}.
 *
 * <p>Verifies that ConfigService and NacosPromptListener beans are correctly created,
 * conditional on properties and classpath conditions.
 */
class AgentscopeNacosPromptAutoConfigurationTest {

    private ConfigService mockConfigService;

    @BeforeEach
    void setUp() {
        mockConfigService = mock(ConfigService.class);
    }

    @Test
    @DisplayName("should create ConfigService and NacosPromptListener when enabled")
    void shouldCreateBeansWhenEnabled() {
        try (MockedStatic<ConfigFactory> mocked = Mockito.mockStatic(ConfigFactory.class)) {
            mocked.when(() -> ConfigFactory.createConfigService(any(java.util.Properties.class)))
                    .thenReturn(mockConfigService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.nacos.server-addr=127.0.0.1:8848",
                            "agentscope.nacos.prompt.enabled=true",
                            "agentscope.nacos.prompt.sys-prompt-key=test-agent")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentScopeNacosProperties.class);
                                assertThat(context)
                                        .hasSingleBean(AgentScopeNacosPromptProperties.class);
                                assertThat(context).hasSingleBean(AgentscopeProperties.class);
                                assertThat(context).hasSingleBean(ConfigService.class);
                                assertThat(context).hasSingleBean(NacosPromptListener.class);
                            });
        }
    }

    @Test
    @DisplayName("should not create beans when prompt is disabled")
    void shouldNotCreateBeansWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AgentscopeNacosPromptAutoConfiguration.class))
                .withPropertyValues("agentscope.nacos.prompt.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ConfigService.class);
                            assertThat(context).doesNotHaveBean(NacosPromptListener.class);
                        });
    }

    @Test
    @DisplayName("should bind prompt properties correctly")
    void shouldBindProperties() {
        try (MockedStatic<ConfigFactory> mocked = Mockito.mockStatic(ConfigFactory.class)) {
            mocked.when(() -> ConfigFactory.createConfigService(any(java.util.Properties.class)))
                    .thenReturn(mockConfigService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.nacos.server-addr=10.0.0.1:8848",
                            "agentscope.nacos.prompt.enabled=true",
                            "agentscope.nacos.prompt.sys-prompt-key=my-agent",
                            "agentscope.nacos.prompt.variables.role=Helper",
                            "agentscope.nacos.prompt.variables.env=prod")
                    .run(
                            context -> {
                                AgentScopeNacosPromptProperties props =
                                        context.getBean(AgentScopeNacosPromptProperties.class);
                                assertThat(props.isEnabled()).isTrue();
                                assertThat(props.getSysPromptKey()).isEqualTo("my-agent");
                                assertThat(props.getVariables())
                                        .containsEntry("role", "Helper")
                                        .containsEntry("env", "prod");
                            });
        }
    }

    @Test
    @DisplayName("should not replace existing NacosPromptListener bean")
    void shouldNotReplaceExistingBean() {
        NacosPromptListener customListener = new NacosPromptListener(mockConfigService);

        try (MockedStatic<ConfigFactory> mocked = Mockito.mockStatic(ConfigFactory.class)) {
            mocked.when(() -> ConfigFactory.createConfigService(any(java.util.Properties.class)))
                    .thenReturn(mockConfigService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.nacos.prompt.enabled=true",
                            "agentscope.nacos.prompt.sys-prompt-key=test")
                    .withBean(NacosPromptListener.class, () -> customListener)
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(NacosPromptListener.class);
                                assertThat(context.getBean(NacosPromptListener.class))
                                        .isSameAs(customListener);
                            });
        }
    }

    @Test
    @DisplayName("should use prompt-specific Nacos config when both global and prompt config set")
    void shouldUsePromptSpecificNacosConfig() {
        try (MockedStatic<ConfigFactory> mocked = Mockito.mockStatic(ConfigFactory.class)) {
            mocked.when(() -> ConfigFactory.createConfigService(any(java.util.Properties.class)))
                    .thenReturn(mockConfigService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.nacos.server-addr=global.example.com:8848",
                            "agentscope.nacos.prompt.enabled=true",
                            "agentscope.nacos.prompt.server-addr=prompt.example.com:8848",
                            "agentscope.nacos.prompt.namespace=prompt-ns",
                            "agentscope.nacos.prompt.sys-prompt-key=test")
                    .run(
                            context -> {
                                AgentScopeNacosPromptProperties promptProps =
                                        context.getBean(AgentScopeNacosPromptProperties.class);
                                assertThat(promptProps.getServerAddr())
                                        .isEqualTo("prompt.example.com:8848");
                                assertThat(promptProps.getNamespace()).isEqualTo("prompt-ns");

                                AgentScopeNacosProperties globalProps =
                                        context.getBean(AgentScopeNacosProperties.class);
                                assertThat(globalProps.getServerAddr())
                                        .isEqualTo("global.example.com:8848");
                            });
        }
    }

    @Test
    @DisplayName("should not overwrite global server-addr when prompt server-addr is not set")
    void shouldNotOverwriteGlobalServerAddrWhenPromptNotSet() {
        try (MockedStatic<ConfigFactory> mocked = Mockito.mockStatic(ConfigFactory.class)) {
            java.util.concurrent.atomic.AtomicReference<java.util.Properties> capturedProps =
                    new java.util.concurrent.atomic.AtomicReference<>();
            mocked.when(() -> ConfigFactory.createConfigService(any(java.util.Properties.class)))
                    .thenAnswer(
                            invocation -> {
                                capturedProps.set(invocation.getArgument(0));
                                return mockConfigService;
                            });

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.nacos.server-addr=production.nacos.com:8848",
                            "agentscope.nacos.namespace=prod-ns",
                            "agentscope.nacos.prompt.enabled=true",
                            "agentscope.nacos.prompt.sys-prompt-key=test")
                    .run(
                            context -> {
                                assertThat(capturedProps.get()).isNotNull();
                                assertThat(capturedProps.get().get(PropertyKeyConst.SERVER_ADDR))
                                        .isEqualTo("production.nacos.com:8848");
                                assertThat(capturedProps.get().get(PropertyKeyConst.NAMESPACE))
                                        .isEqualTo("prod-ns");
                            });
        }
    }
}
