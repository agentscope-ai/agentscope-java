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
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.webflux.AgentscopeAguiWebFluxAutoConfiguration;
import io.agentscope.spring.boot.agui.webflux.AguiWebFluxHandler;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for AG-UI adapter configuration auto-configuration.
 */
@Tag("unit")
@DisplayName("AguiAdapterConfig Auto-configuration Unit Tests")
class AguiAdapterConfigAutoConfigurationTest {

    private final WebApplicationContextRunner mvcContextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeAguiMvcAutoConfiguration.class))
                    .withUserConfiguration(RegistryConfig.class);

    private final ReactiveWebApplicationContextRunner webFluxContextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(AgentscopeAguiWebFluxAutoConfiguration.class))
                    .withUserConfiguration(RegistryConfig.class);

    @Test
    @DisplayName("Should keep token usage and base properties enricher disabled by default")
    void testDefaultAdapterConfig() {
        mvcContextRunner.run(
                ctx -> {
                    AguiAdapterConfig config = mvcConfig(ctx.getBean(AguiMvcController.class));
                    assertFalse(config.isEmitTokenUsage());
                    assertFalse(config.isBaseEventPropertiesEnricherEnabled());
                    assertTrue(config.getEventConverters().isEmpty());
                    assertTrue(config.getEventEnrichers().isEmpty());
                });
    }

    @Test
    @DisplayName("Should bind emit-token-usage property for MVC")
    void testMvcEmitTokenUsageProperty() {
        mvcContextRunner
                .withPropertyValues("agentscope.agui.emit-token-usage=true")
                .run(
                        ctx -> {
                            AguiAdapterConfig config =
                                    mvcConfig(ctx.getBean(AguiMvcController.class));
                            assertTrue(config.isEmitTokenUsage());
                        });
    }

    @Test
    @DisplayName("Should bind emit-token-usage property for WebFlux")
    void testWebFluxEmitTokenUsageProperty() {
        webFluxContextRunner
                .withPropertyValues("agentscope.agui.emit-token-usage=true")
                .run(
                        ctx -> {
                            AguiAdapterConfig config =
                                    webFluxConfig(ctx.getBean(AguiWebFluxHandler.class));
                            assertTrue(config.isEmitTokenUsage());
                        });
    }

    @Test
    @DisplayName("Should register ordered converter and enricher beans for MVC")
    void testMvcRegistersOrderedExtensions() {
        mvcContextRunner
                .withUserConfiguration(OrderedExtensionsConfig.class)
                .run(
                        ctx -> {
                            AguiAdapterConfig config =
                                    mvcConfig(ctx.getBean(AguiMvcController.class));
                            assertIterableEquals(
                                    List.of(
                                            OrderedExtensionsConfig.FIRST_CONVERTER,
                                            OrderedExtensionsConfig.SECOND_CONVERTER),
                                    config.getEventConverters());
                            assertIterableEquals(
                                    List.of(
                                            OrderedExtensionsConfig.FIRST_ENRICHER,
                                            OrderedExtensionsConfig.SECOND_ENRICHER),
                                    config.getEventEnrichers());
                        });
    }

    @Test
    @DisplayName("Should register ordered converter and enricher beans for WebFlux")
    void testWebFluxRegistersOrderedExtensions() {
        webFluxContextRunner
                .withUserConfiguration(OrderedExtensionsConfig.class)
                .run(
                        ctx -> {
                            AguiAdapterConfig config =
                                    webFluxConfig(ctx.getBean(AguiWebFluxHandler.class));
                            assertSame(
                                    OrderedExtensionsConfig.FIRST_CONVERTER,
                                    config.getEventConverters().get(0));
                            assertSame(
                                    OrderedExtensionsConfig.SECOND_ENRICHER,
                                    config.getEventEnrichers().get(1));
                        });
    }

    private static AguiAdapterConfig mvcConfig(AguiMvcController controller) {
        Object processor = ReflectionTestUtils.getField(controller, "processor");
        return (AguiAdapterConfig) ReflectionTestUtils.getField(processor, "config");
    }

    private static AguiAdapterConfig webFluxConfig(AguiWebFluxHandler handler) {
        Object processor = ReflectionTestUtils.getField(handler, "processor");
        return (AguiAdapterConfig) ReflectionTestUtils.getField(processor, "config");
    }

    @Configuration
    static class RegistryConfig {

        @Bean
        public AguiAgentRegistry aguiAgentRegistry() {
            return new AguiAgentRegistry();
        }
    }

    @Configuration
    static class OrderedExtensionsConfig {

        static final AgentEventConverter FIRST_CONVERTER = new NoopConverter(AgentStartEvent.class);
        static final AgentEventConverter SECOND_CONVERTER = new NoopConverter(AgentEndEvent.class);
        static final AguiEventEnricher FIRST_ENRICHER = (source, events, context) -> events;
        static final AguiEventEnricher SECOND_ENRICHER = (source, events, context) -> events;

        @Bean
        @Order(2)
        public AgentEventConverter secondConverter() {
            return SECOND_CONVERTER;
        }

        @Bean
        @Order(1)
        public AgentEventConverter firstConverter() {
            return FIRST_CONVERTER;
        }

        @Bean
        @Order(2)
        public AguiEventEnricher secondEnricher() {
            return SECOND_ENRICHER;
        }

        @Bean
        @Order(1)
        public AguiEventEnricher firstEnricher() {
            return FIRST_ENRICHER;
        }
    }

    private static final class NoopConverter implements AgentEventConverter {

        private final Class<? extends AgentEvent> eventType;

        private NoopConverter(Class<? extends AgentEvent> eventType) {
            this.eventType = eventType;
        }

        @Override
        public Set<Class<? extends AgentEvent>> eventTypes() {
            return Set.of(eventType);
        }

        @Override
        public void convert(AgentEvent event, AguiStreamContext context) {}
    }
}
