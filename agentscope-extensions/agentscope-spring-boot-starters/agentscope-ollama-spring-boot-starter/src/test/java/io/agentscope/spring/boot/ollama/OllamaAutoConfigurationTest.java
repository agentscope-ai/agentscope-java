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
package io.agentscope.spring.boot.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class OllamaAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OllamaAutoConfiguration.class));

    @Test
    void shouldCreateOllamaModelWhenProviderIsOllama() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=ollama", "agentscope.ollama.model-name=llama3")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OllamaChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("llama3");
                        });
    }

    @Test
    void shouldBindSupportedOllamaProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=ollama",
                        "agentscope.ollama.model-name=llama3.2",
                        "agentscope.ollama.base-url=http://ollama.example.com:11434")
                .run(
                        context -> {
                            OllamaProperties properties = context.getBean(OllamaProperties.class);
                            assertThat(properties.isEnabled()).isTrue();
                            assertThat(properties.getModelName()).isEqualTo("llama3.2");
                            assertThat(properties.getBaseUrl())
                                    .isEqualTo("http://ollama.example.com:11434");

                            OllamaChatModel model = context.getBean(OllamaChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("llama3.2");
                        });
    }

    @Test
    void shouldNotCreateOllamaModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.ollama.model-name=llama3")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(OllamaChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateOllamaModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=ollama",
                        "agentscope.ollama.enabled=false",
                        "agentscope.ollama.model-name=llama3")
                .run(
                        context -> {
                            assertThat(context.getBean(OllamaProperties.class).isEnabled())
                                    .isFalse();
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(OllamaChatModel.class);
                        });
    }

    @Test
    void shouldFailClearlyWhenModelNameBlank() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=ollama", "agentscope.ollama.model-name= ")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "agentscope.ollama.model-name must be configured"));
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues("agentscope.model.provider=ollama")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(OllamaChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericAgentscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                OllamaAutoConfiguration.class, AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=ollama",
                        "agentscope.ollama.model-name=llama3")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OllamaChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyOllamaChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=ollama", "agentscope.ollama.model-name=llama3")
                .run(
                        context -> {
                            OllamaChatModel model = context.getBean(OllamaChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-model-name");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnOllamaChatModelBuilderCustomizer() {
        OllamaChatModel.Builder builder = OllamaChatModel.builder().modelName("original");
        OllamaChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

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
        OllamaChatModelBuilderCustomizer testOllamaChatModelBuilderCustomizer() {
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
