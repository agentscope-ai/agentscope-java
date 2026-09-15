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
import static org.mockito.Mockito.verify;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.mcp.NacosMcpClients;
import io.agentscope.core.nacos.mcp.discovery.NacosMcpDiscoveryClient;
import io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper;
import io.agentscope.spring.boot.nacos.properties.mcp.AgentScopeMcpNacosProperties;
import io.agentscope.spring.boot.nacos.properties.mcp.NacosMcpConnectionProperties.LoadBalanceStrategy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for {@link AgentscopeMcpNacosAutoConfiguration}.
 *
 * <p>These tests verify bean creation, property binding and shutdown behavior under different
 * property setups. No real Nacos server is contacted: {@link AiFactory} is mocked so the
 * auto-configuration receives a stub {@link AiService}, and the wrappers are never initialized
 * (initialization only happens when a Toolkit registers them).
 */
class AgentscopeMcpNacosAutoConfigurationTest {

    private AiService mockAiService;

    @BeforeEach
    void setUp() {
        mockAiService = mock(AiService.class);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AgentscopeMcpNacosAutoConfiguration.class));
    }

    @Test
    void shouldNotCreateBeansWhenDisabled() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withPropertyValues("agentscope.nacos.mcp.enabled=false")
                    .run(
                            context -> {
                                assertThat(context).doesNotHaveBean(NacosMcpClients.class);
                                assertThat(context).doesNotHaveBean(NacosMcpDiscoveryClient.class);
                            });
        }
    }

    @Test
    void shouldNotCreateBeansWhenEnabledMissing() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            // matchIfMissing = false: no enabled property means the auto-configuration is off
            runner().withPropertyValues("agentscope.nacos.server-addr=127.0.0.1:8848")
                    .run(
                            context -> {
                                assertThat(context).doesNotHaveBean(NacosMcpClients.class);
                                assertThat(context).doesNotHaveBean(NacosMcpDiscoveryClient.class);
                            });
        }
    }

    @Test
    void shouldCreateDiscoveryClientAndEmptyClientsWhenEnabledWithoutConnections() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withPropertyValues(
                            "agentscope.nacos.mcp.enabled=true",
                            "agentscope.nacos.server-addr=127.0.0.1:8848")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).hasSingleBean(NacosMcpDiscoveryClient.class);
                                assertThat(context).hasSingleBean(NacosMcpClients.class);
                                assertThat(context.getBean(NacosMcpClients.class).isEmpty())
                                        .isTrue();
                            });
        }
    }

    @Test
    void shouldCreateOneWrapperPerConfiguredConnection() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withPropertyValues(
                            "agentscope.nacos.mcp.enabled=true",
                            "agentscope.nacos.server-addr=127.0.0.1:8848",
                            "agentscope.nacos.mcp.connections.weather.service-name=weather-mcp-server",
                            "agentscope.nacos.mcp.connections.weather.version=1.0.0",
                            "agentscope.nacos.mcp.connections.amap.service-name=amap-mcp-server",
                            "agentscope.nacos.mcp.connections.amap.version=2.0.0")
                    .run(
                            context -> {
                                NacosMcpClients clients = context.getBean(NacosMcpClients.class);
                                assertThat(clients.list()).hasSize(2);

                                NacosLoadBalancedMcpClientWrapper weather = clients.get("weather");
                                assertThat(weather).isNotNull();
                                assertThat(weather.getName()).isEqualTo("weather");
                                assertThat(weather.getServerName()).isEqualTo("weather-mcp-server");
                                assertThat(weather.getVersion()).isEqualTo("1.0.0");

                                NacosLoadBalancedMcpClientWrapper amap = clients.get("amap");
                                assertThat(amap).isNotNull();
                                assertThat(amap.getServerName()).isEqualTo("amap-mcp-server");
                                assertThat(amap.getVersion()).isEqualTo("2.0.0");

                                // wrappers are not initialized during context startup
                                assertThat(weather.getCurrentEndpoints()).isEmpty();
                                assertThat(amap.getCurrentEndpoints()).isEmpty();
                            });
        }
    }

    @Test
    void shouldBindGlobalAndPerConnectionLoadBalanceStrategies() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withPropertyValues(
                            "agentscope.nacos.mcp.enabled=true",
                            "agentscope.nacos.mcp.load-balance=round-robin",
                            "agentscope.nacos.mcp.connections.weather.service-name=weather-mcp-server",
                            "agentscope.nacos.mcp.connections.amap.service-name=amap-mcp-server",
                            "agentscope.nacos.mcp.connections.amap.load-balance=sticky")
                    .run(
                            context -> {
                                AgentScopeMcpNacosProperties properties =
                                        context.getBean(AgentScopeMcpNacosProperties.class);
                                assertThat(properties.getLoadBalance())
                                        .isEqualTo(LoadBalanceStrategy.ROUND_ROBIN);
                                // weather does not override -> its own value stays null (falls back
                                // to the global strategy at wrapper build time)
                                assertThat(
                                                properties
                                                        .getConnections()
                                                        .get("weather")
                                                        .getLoadBalance())
                                        .isNull();
                                assertThat(properties.getConnections().get("amap").getLoadBalance())
                                        .isEqualTo(LoadBalanceStrategy.STICKY);
                            });
        }
    }

    @Test
    void shouldDefaultGlobalLoadBalanceToRoundRobin() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withPropertyValues(
                            "agentscope.nacos.mcp.enabled=true",
                            "agentscope.nacos.mcp.connections.weather.service-name=weather-mcp-server")
                    .run(
                            context -> {
                                AgentScopeMcpNacosProperties properties =
                                        context.getBean(AgentScopeMcpNacosProperties.class);
                                assertThat(properties.getLoadBalance())
                                        .isEqualTo(LoadBalanceStrategy.ROUND_ROBIN);
                            });
        }
    }

    @Test
    void shouldNotInitializeAiServiceShutdownUntilContextClosed() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withPropertyValues(
                            "agentscope.nacos.mcp.enabled=true",
                            "agentscope.nacos.mcp.connections.weather.service-name=weather-mcp-server")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(NacosMcpClients.class);
                                verify(mockAiService, Mockito.never()).shutdown();

                                AgentscopeMcpNacosAutoConfiguration autoConfiguration =
                                        context.getBean(AgentscopeMcpNacosAutoConfiguration.class);
                                autoConfiguration.close();
                                verify(mockAiService).shutdown();
                            });
        }
    }

    @Test
    void shouldBackOffWhenUserProvidesCustomNacosMcpClients() {
        NacosMcpClients custom = new NacosMcpClients(List.of());
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            runner().withBean(NacosMcpClients.class, () -> custom)
                    .withPropertyValues(
                            "agentscope.nacos.mcp.enabled=true",
                            "agentscope.nacos.mcp.connections.weather.service-name=weather-mcp-server")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(NacosMcpClients.class);
                                assertThat(context.getBean(NacosMcpClients.class)).isSameAs(custom);
                            });
        }
    }
}
