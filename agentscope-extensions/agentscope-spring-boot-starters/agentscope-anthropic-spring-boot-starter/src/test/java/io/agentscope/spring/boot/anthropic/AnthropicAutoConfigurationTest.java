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
package io.agentscope.spring.boot.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class AnthropicAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AnthropicAutoConfiguration.class));

    @Test
    void shouldCreateAnthropicModelWhenProviderIsAnthropic() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(AnthropicChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("claude-sonnet-4.5");
                        });
    }

    @Test
    void shouldBindSupportedAnthropicProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5",
                        "agentscope.anthropic.base-url=https://anthropic.example.com",
                        "agentscope.anthropic.stream=false")
                .run(
                        context -> {
                            AnthropicChatModel model = context.getBean(AnthropicChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("claude-sonnet-4.5");
                        });
    }

    @Test
    void shouldAuthenticateGatewayRequestsWithAuthToken() throws Exception {
        assertGatewayAuthentication(
                contextRunner.withPropertyValues(
                        "agentscope.anthropic.auth-token=test-gateway-token"),
                null,
                "Bearer test-gateway-token");
    }

    @Test
    void shouldIgnoreBlankAuthTokenAndKeepApiKeyAuthentication() throws Exception {
        assertGatewayAuthentication(
                contextRunner.withPropertyValues(
                        "agentscope.anthropic.api-key=test-api-key",
                        "agentscope.anthropic.auth-token=   "),
                "test-api-key",
                null);
    }

    @Test
    void shouldApplyAuthTokenCustomizerAfterProperties() throws Exception {
        assertGatewayAuthentication(
                contextRunner
                        .withPropertyValues("agentscope.anthropic.auth-token=property-token")
                        .withBean(
                                AnthropicChatModelBuilderCustomizer.class,
                                () -> builder -> builder.authToken("customized-token")),
                null,
                "Bearer customized-token");
    }

    private void assertGatewayAuthentication(
            ApplicationContextRunner runner, String expectedApiKey, String expectedAuthorization)
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "id": "msg_gateway", "type": "message", "role": "assistant",
                                      "model": "claude-sonnet-4.5",
                                      "content": [{"type": "text", "text": "Hello"}],
                                      "stop_reason": "end_turn", "stop_sequence": null,
                                      "usage": {"input_tokens": 1, "output_tokens": 1}
                                    }
                                    """));

            runner.withPropertyValues(
                            "agentscope.model.provider=anthropic",
                            "agentscope.anthropic.base-url=" + server.url("/anthropic/"),
                            "agentscope.anthropic.stream=false")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AnthropicChatModel.class);
                                AnthropicChatModel model =
                                        context.getBean(AnthropicChatModel.class);
                                List<ChatResponse> responses =
                                        model.stream(
                                                        List.of(
                                                                Msg.builder()
                                                                        .role(MsgRole.USER)
                                                                        .textContent("Hello")
                                                                        .build()),
                                                        null,
                                                        null)
                                                .collectList()
                                                .block(Duration.ofSeconds(10));

                                assertThat(responses).hasSize(1);
                                assertThat(responses.get(0).getContent())
                                        .singleElement()
                                        .isInstanceOfSatisfying(
                                                TextBlock.class,
                                                text ->
                                                        assertThat(text.getText())
                                                                .isEqualTo("Hello"));
                                RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
                                assertThat(request).isNotNull();
                                assertThat(request.getHeader("Authorization"))
                                        .isEqualTo(expectedAuthorization);
                                assertThat(request.getHeader("X-Api-Key"))
                                        .isEqualTo(expectedApiKey);
                            });
        }
    }

    @Test
    void shouldNotCreateAnthropicModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.anthropic.api-key=test-anthropic-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(AnthropicChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateAnthropicModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.enabled=false",
                        "agentscope.anthropic.api-key=test-anthropic-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(AnthropicChatModel.class);
                        });
    }

    @Test
    void shouldCreateAnthropicModelWhenApiKeyMissing() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(AnthropicChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("claude-sonnet-4.5");
                        });
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(AnthropicChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericAgentscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                AnthropicAutoConfiguration.class,
                                AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(AnthropicChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyAnthropicChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5")
                .run(
                        context -> {
                            AnthropicChatModel model = context.getBean(AnthropicChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-claude");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnAnthropicChatModelBuilderCustomizer() {
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder().modelName("original");
        AnthropicChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

        customizer.accept(builder);

        assertThat(builder.build().getModelName()).isEqualTo("customized");
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomModelConfiguration {

        @Bean
        Model customModel() {
            return new TestModel();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBuilderConfiguration {

        @Bean
        AnthropicChatModelBuilderCustomizer testAnthropicChatModelBuilderCustomizer() {
            return builder -> builder.modelName("customized-claude");
        }
    }

    private static final class TestModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "custom-model";
        }
    }
}
