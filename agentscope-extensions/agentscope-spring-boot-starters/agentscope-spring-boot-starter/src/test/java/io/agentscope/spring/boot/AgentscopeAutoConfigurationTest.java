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
package io.agentscope.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.WebSocketTransport;
import io.agentscope.core.model.transport.websocket.OkHttpWebSocketTransport;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import io.agentscope.spring.boot.properties.HttpTransportProperties;
import io.agentscope.spring.boot.properties.WebSocketTransportProperties;
import io.agentscope.spring.boot.transport.WebClientTransport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for {@link AgentscopeAutoConfiguration}.
 *
 * <p>These tests verify that the auto-configuration creates the expected beans under different
 * property setups.
 */
class AgentscopeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.agent.enabled=true",
                            "agentscope.dashscope.api-key=test-api-key");

    @Test
    void shouldCreateDefaultBeansWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(Memory.class);
                    assertThat(context).hasSingleBean(Toolkit.class);
                    assertThat(context).hasSingleBean(HttpTransport.class);
                    assertThat(context).hasSingleBean(WebSocketTransport.class);
                    assertThat(context).hasSingleBean(Model.class);
                    assertThat(context).hasSingleBean(ReActAgent.class);
                });
    }

    @Test
    void shouldNotCreateReActAgentWhenDisabled() {
        contextRunner
                .withPropertyValues("agentscope.agent.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ReActAgent.class);
                            assertThat(context).doesNotHaveBean(Memory.class);
                            assertThat(context).doesNotHaveBean(Toolkit.class);
                            assertThat(context).doesNotHaveBean(HttpTransport.class);
                            assertThat(context).doesNotHaveBean(WebSocketTransport.class);
                            assertThat(context).doesNotHaveBean(Model.class);
                        });
    }

    @Test
    void shouldFailWhenApiKeyMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues("agentscope.agent.enabled=true")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "agentscope.dashscope.api-key must be configured"));
    }

    @Test
    void shouldCreateOpenAIModelWhenProviderIsOpenAI() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldCreateOpenAIModelWithCustomEndpointPath() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini",
                        "agentscope.openai.endpoint-path=/v4/chat/completions")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldCreateGeminiModelWhenProviderIsGemini() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key",
                        "agentscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(GeminiChatModel.class);
                        });
    }

    @Test
    void shouldCreateAnthropicModelWhenProviderIsAnthropic() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(AnthropicChatModel.class);
                        });
    }

    @Test
    void shouldCreateHttpTransportWhenConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-api-key",
                        "agentscope.transport.http.type=webclient")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(HttpTransport.class);
                            assertThat(context.getBean(HttpTransport.class))
                                    .isInstanceOf(WebClientTransport.class);
                        });
    }

    @Test
    void shouldCreateWebSocketTransportWhenConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-api-key",
                        "agentscope.transport.websocket.type=okhttp")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(WebSocketTransport.class);
                            assertThat(context.getBean(WebSocketTransport.class))
                                    .isInstanceOf(OkHttpWebSocketTransport.class);
                        });
    }

    @Test
    void shouldConfigureHttpTransportProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-api-key",
                        "agentscope.transport.http.connect-timeout=10s",
                        "agentscope.transport.http.read-timeout=10m",
                        "agentscope.transport.http.max-connections=100",
                        "agentscope.transport.http.ignore-ssl=true",
                        "agentscope.transport.http.proxy.type=socks5",
                        "agentscope.transport.http.proxy.host=localhost",
                        "agentscope.transport.http.proxy.port=8080",
                        "agentscope.transport.http.proxy.username=username",
                        "agentscope.transport.http.proxy.password=password",
                        "agentscope.transport.http.proxy.non-proxy-hosts[0]=nonproxy.com")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(AgentscopeProperties.class);
                            AgentscopeProperties agentscopeProperties =
                                    context.getBean(AgentscopeProperties.class);
                            HttpTransportProperties httpTransportProperties =
                                    agentscopeProperties.getTransport().getHttp();
                            assertThat(httpTransportProperties).isNotNull();
                            assertThat(httpTransportProperties.getConnectTimeout())
                                    .isEqualTo(Duration.ofSeconds(10));
                            assertThat(httpTransportProperties.getReadTimeout())
                                    .isEqualTo(Duration.ofMinutes(10));
                            assertThat(httpTransportProperties.getMaxConnections()).isEqualTo(100);
                            assertThat(httpTransportProperties.isIgnoreSsl()).isTrue();
                            assertThat(httpTransportProperties.getProxy()).isNotNull();
                            assertThat(httpTransportProperties.getProxy().getType())
                                    .isEqualTo(ProxyType.SOCKS5);
                            assertThat(httpTransportProperties.getProxy().getHost())
                                    .isEqualTo("localhost");
                            assertThat(httpTransportProperties.getProxy().getPort())
                                    .isEqualTo(8080);
                            assertThat(httpTransportProperties.getProxy().getUsername())
                                    .isEqualTo("username");
                            assertThat(httpTransportProperties.getProxy().getPassword())
                                    .isEqualTo("password");
                            assertThat(httpTransportProperties.getProxy().getNonProxyHosts())
                                    .contains("nonproxy.com");
                        });
    }

    @Test
    void shouldConfigureWebSocketTransportProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-api-key",
                        "agentscope.transport.websocket.connect-timeout=10s",
                        "agentscope.transport.websocket.write-timeout=5m",
                        "agentscope.transport.websocket.ping-interval=30s",
                        "agentscope.transport.websocket.ignore-ssl=true",
                        "agentscope.transport.websocket.proxy.type=socks5",
                        "agentscope.transport.websocket.proxy.host=localhost",
                        "agentscope.transport.websocket.proxy.port=8080",
                        "agentscope.transport.websocket.proxy.username=username",
                        "agentscope.transport.websocket.proxy.password=password",
                        "agentscope.transport.websocket.proxy.non-proxy-hosts[0]=nonproxy.com")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(AgentscopeProperties.class);
                            AgentscopeProperties agentscopeProperties =
                                    context.getBean(AgentscopeProperties.class);
                            WebSocketTransportProperties webSocketTransportProperties =
                                    agentscopeProperties.getTransport().getWebsocket();

                            assertThat(webSocketTransportProperties).isNotNull();
                            assertThat(webSocketTransportProperties.getConnectTimeout())
                                    .isEqualTo(Duration.ofSeconds(10));
                            assertThat(webSocketTransportProperties.getReadTimeout())
                                    .isEqualTo(Duration.ZERO);
                            assertThat(webSocketTransportProperties.getWriteTimeout())
                                    .isEqualTo(Duration.ofMinutes(5));
                            assertThat(webSocketTransportProperties.getPingInterval())
                                    .isEqualTo(Duration.ofSeconds(30));
                            assertThat(webSocketTransportProperties.isIgnoreSsl()).isTrue();
                            assertThat(webSocketTransportProperties.getProxy()).isNotNull();
                            assertThat(webSocketTransportProperties.getProxy().getType())
                                    .isEqualTo(ProxyType.SOCKS5);
                            assertThat(webSocketTransportProperties.getProxy().getHost())
                                    .isEqualTo("localhost");
                            assertThat(webSocketTransportProperties.getProxy().getPort())
                                    .isEqualTo(8080);
                            assertThat(webSocketTransportProperties.getProxy().getUsername())
                                    .isEqualTo("username");
                            assertThat(webSocketTransportProperties.getProxy().getPassword())
                                    .isEqualTo("password");
                            assertThat(webSocketTransportProperties.getProxy().getNonProxyHosts())
                                    .contains("nonproxy.com");
                        });
    }
}
