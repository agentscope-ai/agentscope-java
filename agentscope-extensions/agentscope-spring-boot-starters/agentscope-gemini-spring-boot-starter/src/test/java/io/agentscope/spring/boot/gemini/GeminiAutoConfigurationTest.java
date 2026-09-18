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
package io.agentscope.spring.boot.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class GeminiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(GeminiAutoConfiguration.class));

    @Test
    void shouldCreateGeminiModelWhenProviderIsGemini() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key",
                        "agentscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(GeminiChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("gemini-2.0-flash");
                        });
    }

    @Test
    void shouldCreateGeminiModelWithVertexAIProject() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.project=test-project",
                        "agentscope.gemini.location=us-central1",
                        "agentscope.gemini.vertex-ai=true",
                        "agentscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "Failed to get application default credentials"));
    }

    @Test
    void shouldBindSupportedGeminiProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key",
                        "agentscope.gemini.model-name=gemini-2.0-flash",
                        "agentscope.gemini.base-url=https://gemini.example.com",
                        "agentscope.gemini.stream=false",
                        "agentscope.gemini.project=test-project",
                        "agentscope.gemini.location=us-central1",
                        "agentscope.gemini.vertex-ai=false")
                .run(
                        context -> {
                            GeminiChatModel model = context.getBean(GeminiChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("gemini-2.0-flash");
                        });
    }

    @Test
    void shouldNotCreateGeminiModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.gemini.api-key=test-gemini-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(GeminiChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateGeminiModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.enabled=false",
                        "agentscope.gemini.api-key=test-gemini-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(GeminiChatModel.class);
                        });
    }

    @Test
    void shouldFailClearlyWhenCredentialMissing() {
        contextRunner
                .withPropertyValues("agentscope.model.provider=gemini")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "agentscope.gemini.api-key must be configured"
                                                        + " when Gemini API mode is selected"));
    }

    @Test
    void shouldFailClearlyWhenVertexAIProjectMissing() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=gemini", "agentscope.gemini.vertex-ai=true")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "agentscope.gemini.project must be configured when"
                                                        + " Vertex AI mode is enabled"));
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(GeminiChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericAgentscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                GeminiAutoConfiguration.class, AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key",
                        "agentscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(GeminiChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyGeminiChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key",
                        "agentscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            GeminiChatModel model = context.getBean(GeminiChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-model-name");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnGeminiChatModelBuilderCustomizer() {
        GeminiChatModel.Builder builder =
                GeminiChatModel.builder().apiKey("test-key").modelName("original");
        GeminiChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

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
        GeminiChatModelBuilderCustomizer testGeminiChatModelBuilderCustomizer() {
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
