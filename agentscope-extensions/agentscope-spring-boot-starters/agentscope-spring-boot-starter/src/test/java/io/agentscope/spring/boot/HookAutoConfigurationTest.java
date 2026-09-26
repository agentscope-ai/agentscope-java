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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link HookAutoConfiguration}.
 *
 * <p>Isolated because {@link Hook} and {@link io.agentscope.core.hook.HookEvent}
 * are {@link Deprecated} for removal.
 */
@SuppressWarnings("deprecation")
class HookAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    AgentscopeAutoConfiguration.class, HookAutoConfiguration.class))
                    .withPropertyValues("agentscope.agent.enabled=true");

    @Test
    void shouldAutoInjectHookBeans() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class, HookConfiguration.class)
                .run(
                        context -> {
                            ReActAgent agent = context.getBean(ReActAgent.class);
                            assertThat(agent.getHooks()).anyMatch(h -> h instanceof TestHook);
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
    static class HookConfiguration {

        @Bean
        TestHook testHook() {
            return new TestHook();
        }
    }

    static class TestHook implements Hook {
        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            return Mono.just(event);
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
