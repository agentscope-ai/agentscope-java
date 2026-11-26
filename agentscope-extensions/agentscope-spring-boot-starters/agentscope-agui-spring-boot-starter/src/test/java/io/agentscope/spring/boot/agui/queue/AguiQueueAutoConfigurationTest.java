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
package io.agentscope.spring.boot.agui.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.queue.MessageQueueRegistry;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.webflux.AgentscopeAguiWebFluxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AguiQueueAutoConfigurationTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeAguiMvcAutoConfiguration.class))
                    .withBean(AguiAgentRegistry.class, AguiAgentRegistry::new);

    @Test
    void shouldNotRegisterQueueByDefault() {
        runner.run(
                context -> {
                    assertThat(context).doesNotHaveBean(MessageQueueRegistry.class);
                    assertThat(context).doesNotHaveBean(AguiQueueController.class);
                    assertThat(context).hasSingleBean(AguiRequestProcessor.class);
                });
    }

    @Test
    void shouldRegisterQueueWhenEnabled() {
        runner.withPropertyValues("agentscope.agui.queue.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MessageQueueRegistry.class);
                            assertThat(context).hasSingleBean(AguiQueueController.class);
                            assertThat(context).hasSingleBean(AguiRequestProcessor.class);
                        });
    }

    @Test
    void shouldBindQueueDefaults() {
        runner.withPropertyValues("agentscope.agui.queue.enabled=true")
                .run(
                        context -> {
                            AguiProperties props = context.getBean(AguiProperties.class);
                            assertThat(props.getQueue().getCapacity()).isEqualTo(16);
                            assertThat(props.getQueue().getIdempotencyRetention()).isEqualTo(64);
                        });
    }

    @Test
    void shouldUseTheProcessorOfACustomMvcController() {
        AguiRequestProcessor customProcessor = mock(AguiRequestProcessor.class);
        // A controller built through the public builder owns a private processor. The queue must
        // observe that one, otherwise "idle" would always be true for it.
        runner.withBean(
                        AguiMvcController.class,
                        () -> AguiMvcController.builder().requestProcessor(customProcessor).build())
                .withPropertyValues("agentscope.agui.queue.enabled=true")
                .run(
                        context ->
                                assertThat(
                                                context.getBean(AguiQueueController.class)
                                                        .getRequestProcessor())
                                        .isSameAs(customProcessor));
    }

    @Test
    void shouldRegisterQueueForWebFlux() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AgentscopeAguiWebFluxAutoConfiguration.class))
                .withBean(AguiAgentRegistry.class, AguiAgentRegistry::new)
                .withPropertyValues("agentscope.agui.queue.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MessageQueueRegistry.class);
                            assertThat(context).hasSingleBean(AguiQueueController.class);
                            assertThat(context).hasSingleBean(AguiRequestProcessor.class);
                        });
    }
}
