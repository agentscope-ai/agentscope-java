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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link AgentscopeAutoConfiguration}.
 */
class AgentscopeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                    .withPropertyValues("agentscope.agent.enabled=true");

    @Test
    void shouldCreateMemoryAndToolkitWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(Memory.class);
                    assertThat(context).hasSingleBean(Toolkit.class);
                    assertThat(context).doesNotHaveBean(Model.class);
                    assertThat(context).doesNotHaveBean(ReActAgent.class);
                });
    }

    @Test
    void shouldCreateReActAgentWhenModelBeanExists() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Memory.class);
                            assertThat(context).hasSingleBean(Toolkit.class);
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldNotCreateBeansWhenAgentIsDisabled() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues("agentscope.agent.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(ReActAgent.class);
                            assertThat(context).doesNotHaveBean(Memory.class);
                            assertThat(context).doesNotHaveBean(Toolkit.class);
                        });
    }

    @Test
    void shouldBackOffWhenUserDefinesMemoryToolkitAndAgentBeans() {
        contextRunner
                .withUserConfiguration(CustomAgentConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Memory.class);
                            assertThat(context).hasSingleBean(Toolkit.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                            assertThat(context.getBean("customMemory"))
                                    .isSameAs(context.getBean(Memory.class));
                            assertThat(context.getBean("customToolkit"))
                                    .isSameAs(context.getBean(Toolkit.class));
                            assertThat(context.getBean("customAgent"))
                                    .isSameAs(context.getBean(ReActAgent.class));
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomModelConfiguration {

        @Bean
        Model customModel() {
            return new TestModel();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAgentConfiguration {

        @Bean
        Model customModel() {
            return new TestModel();
        }

        @Bean
        Memory customMemory() {
            return new InMemoryMemory();
        }

        @Bean
        Toolkit customToolkit() {
            return new Toolkit();
        }

        @Bean
        ReActAgent customAgent(Model model, Toolkit toolkit) {
            return ReActAgent.builder().name("customAgent").model(model).toolkit(toolkit).build();
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
