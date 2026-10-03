/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agui.processor.AguiResumeStateStore;
import io.agentscope.core.agui.processor.InMemoryAguiResumeStateStore;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.webflux.AgentscopeAguiWebFluxAutoConfiguration;
import io.agentscope.spring.boot.agui.webflux.AguiWebFluxHandler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

/** Additional MVC/WebFlux wiring contract checks for PR 2890. */
class Pr2890SpringContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultsAreLocalAndIndependent(boolean webflux) {
        AtomicReference<AguiResumeStateStore> first = new AtomicReference<>();
        runner(webflux)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            first.set(store(ctx.getBean(handler(webflux))));
                            assertInstanceOf(InMemoryAguiResumeStateStore.class, first.get());
                        });
        runner(webflux)
                .run(ctx -> assertNotSame(first.get(), store(ctx.getBean(handler(webflux)))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneBeanIsInjectedByIdentity(boolean webflux) {
        runner(webflux)
                .withUserConfiguration(OneStore.class)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertSame(ctx.getBean("first"), store(ctx.getBean(handler(webflux))));
                        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousBeansFailStartup(boolean webflux) {
        runner(webflux)
                .withUserConfiguration(OneStore.class, AnotherStore.class)
                .run(
                        ctx -> {
                            Throwable cause = ctx.getStartupFailure();
                            while (cause != null && cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            assertInstanceOf(NoUniqueBeanDefinitionException.class, cause);
                        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void primarySelectsTheSharedStore(boolean webflux) {
        runner(webflux)
                .withUserConfiguration(OneStore.class, PrimaryStore.class)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertSame(
                                    ctx.getBean("primary"), store(ctx.getBean(handler(webflux))));
                        });
    }

    private static AbstractApplicationContextRunner<?, ?, ?> runner(boolean webflux) {
        return webflux
                ? new ReactiveWebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeAguiWebFluxAutoConfiguration.class))
                        .withUserConfiguration(Registry.class)
                : new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeAguiMvcAutoConfiguration.class))
                        .withUserConfiguration(Registry.class);
    }

    private static Class<?> handler(boolean webflux) {
        return webflux ? AguiWebFluxHandler.class : AguiMvcController.class;
    }

    private static AguiResumeStateStore store(Object handler) {
        Object processor = ReflectionTestUtils.getField(handler, "processor");
        Object coordinator = ReflectionTestUtils.getField(processor, "resumeCoordinator");
        return (AguiResumeStateStore) ReflectionTestUtils.getField(coordinator, "stateStore");
    }

    @Configuration
    static class Registry {
        @Bean
        AguiAgentRegistry registry() {
            return new AguiAgentRegistry();
        }
    }

    @Configuration
    static class OneStore {
        @Bean
        AguiResumeStateStore first() {
            return new InMemoryAguiResumeStateStore();
        }
    }

    @Configuration
    static class AnotherStore {
        @Bean
        AguiResumeStateStore second() {
            return new InMemoryAguiResumeStateStore();
        }
    }

    @Configuration
    static class PrimaryStore {
        @Bean
        @Primary
        AguiResumeStateStore primary() {
            return new InMemoryAguiResumeStateStore();
        }
    }
}
