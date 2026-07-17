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
package io.agentscope.spring.boot.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class DashScopeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DashScopeAutoConfiguration.class));

    @Test
    void shouldCreateDashScopeModelWhenProviderIsDashscope() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-dashscope-key",
                        "agentscope.dashscope.model-name=qwen-max")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(DashScopeChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("qwen-max");
                        });
    }

    @Test
    void shouldCreateDashScopeModelWhenProviderIsMissing() {
        contextRunner
                .withPropertyValues(
                        "agentscope.dashscope.api-key=test-dashscope-key",
                        "agentscope.dashscope.model-name=qwen-plus")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(DashScopeChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("qwen-plus");
                        });
    }

    @Test
    void shouldBindSupportedDashScopeProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-dashscope-key",
                        "agentscope.dashscope.model-name=qwen-max",
                        "agentscope.dashscope.base-url=https://dashscope.example.com",
                        "agentscope.dashscope.stream=false",
                        "agentscope.dashscope.enable-thinking=true")
                .run(
                        context -> {
                            DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("qwen-max");
                        });
    }

    @Test
    void shouldNotCreateDashScopeModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=gemini",
                        "agentscope.dashscope.api-key=test-dashscope-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(DashScopeChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateDashScopeModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.enabled=false",
                        "agentscope.dashscope.api-key=test-dashscope-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(DashScopeChatModel.class);
                        });
    }

    @Test
    void shouldFailClearlyWhenApiKeyMissing() {
        contextRunner
                .withPropertyValues("agentscope.model.provider=dashscope")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "agentscope.dashscope.api-key must be configured"));
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-dashscope-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(DashScopeChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericAgentscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                DashScopeAutoConfiguration.class,
                                AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-dashscope-key",
                        "agentscope.dashscope.model-name=qwen-max")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(DashScopeChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyDashScopeChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "agentscope.model.provider=dashscope",
                        "agentscope.dashscope.api-key=test-dashscope-key",
                        "agentscope.dashscope.model-name=qwen-max")
                .run(
                        context -> {
                            DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-qwen");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnDashScopeChatModelBuilderCustomizer() {
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey("test-key").modelName("original");
        DashScopeChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

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
        DashScopeChatModelBuilderCustomizer testDashScopeChatModelBuilderCustomizer() {
            return builder -> builder.modelName("customized-qwen");
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
