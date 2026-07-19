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
package io.agentscope.spring.boot.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class OpenAIAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OpenAIAutoConfiguration.class));

    @Test
    void shouldCreateOpenAIModelWhenProviderIsOpenAI() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("gpt-4.1-mini");
                        });
    }

    @Test
    void shouldBindSupportedOpenAIProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini",
                        "agentscope.openai.base-url=https://example.com/v1",
                        "agentscope.openai.endpoint-path=/v4/chat/completions",
                        "agentscope.openai.stream=false")
                .run(
                        context -> {
                            OpenAIChatModel model = context.getBean(OpenAIChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("gpt-4.1-mini");
                        });
    }

    @Test
    void shouldNotCreateOpenAIModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.openai.api-key=test-openai-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateOpenAIModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.enabled=false",
                        "agentscope.openai.api-key=test-openai-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldCreateOpenAIModelWhenApiKeyMissing() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.model-name=gpt-4.1-mini")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldCreateOpenAIModelForApiKeyFreeProvider() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.model-name=opencode-free-model",
                        "agentscope.openai.base-url=https://api.opencode.example.com/v1")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(OpenAIChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericAgentscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                OpenAIAutoConfiguration.class, AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyOpenAIChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini")
                .run(
                        context -> {
                            OpenAIChatModel model = context.getBean(OpenAIChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-model-name");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnOpenAIChatModelBuilderCustomizer() {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder().modelName("original");
        OpenAIChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

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
        OpenAIChatModelBuilderCustomizer testOpenAIChatModelBuilderCustomizer() {
            return builder -> builder.modelName("customized-model-name");
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
