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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.tool.AgentscopeToolRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Tests for the {@link Tool} auto-scanning and registration capabilities.
 *
 * Tests cover:
 * - Auto-scanning configuration enablement and disablement
 * - Standard Spring bean registration
 * - Handling of AOP-proxied beans (e.g., CGLIB proxy via @Async)
 * - Eager initialization of @Lazy beans
 * - Skipping prototype-scoped beans to prevent memory leaks
 * - Fail-fast validation for duplicate tool names
 * - Detection of @Tool annotations declared on interfaces
 * - Detection of @Tool annotations declared on superclasses
 * - Registration of multiple @Tool methods from a single bean
 * - Conditional bean registration via @ConditionalOnProperty
 */
class AgentscopeToolAutoScanTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.agent.enabled=true",
                            "agentscope.dashscope.api-key=test-api-key");

    @Test
    void shouldCreateRegistrarWhenAutoScanEnabledByDefault() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AgentscopeToolRegistrar.class);
                });
    }

    @Test
    void shouldNotCreateRegistrarWhenAutoScanDisabled() {
        contextRunner
                .withPropertyValues("agentscope.tool.auto-scan.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(AgentscopeToolRegistrar.class);
                        });
    }

    @Test
    void shouldRegisterNormalToolBeanSuccessfully() {
        contextRunner
                .withUserConfiguration(NormalToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames()).contains("calculator_add");
                        });
    }

    @Test
    void shouldHandleAndRegisterAopProxiedToolBeans() {
        contextRunner
                .withUserConfiguration(AopToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames()).contains("async_action");
                        });
    }

    @Test
    void shouldForceInitAndRegisterLazyToolBeans() {
        contextRunner
                .withUserConfiguration(LazyToolConfig.class)
                .run(
                        context -> {
                            assertThat(LazyService.initialized).isTrue();
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames()).contains("lazy_action");
                        });
    }

    @Test
    void shouldSkipPrototypeBeansToPreventMemoryLeaks() {
        contextRunner
                .withUserConfiguration(PrototypeToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames())
                                    .doesNotContain("prototype_action");
                        });
    }

    @Test
    void shouldFailFastWhenDuplicateToolNamesDetected() {
        contextRunner
                .withUserConfiguration(DuplicateToolConfig.class)
                .run(
                        context -> {
                            assertThat(context.getStartupFailure()).isNotNull();
                            assertThat(context.getStartupFailure())
                                    .isInstanceOf(BeanInitializationException.class)
                                    .hasMessageContaining(
                                            "Duplicate AgentScope tool name 'common_search'");
                        });
    }

    @Test
    void shouldRegisterToolDeclaredOnInterface() {
        contextRunner
                .withUserConfiguration(InterfaceToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames()).contains("interface_action");
                        });
    }

    @Test
    void shouldRegisterToolDeclaredOnSuperclass() {
        contextRunner
                .withUserConfiguration(InheritanceToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames()).contains("superclass_action");
                        });
    }

    @Test
    void shouldRegisterMultipleToolsFromSingleBean() {
        contextRunner
                .withUserConfiguration(MultiToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames())
                                    .contains("math_multiply", "math_divide");
                        });
    }

    @Test
    void shouldRespectConditionalOnPropertyForToolBeans() {
        // Case 1: Condition not met, do not register this tool
        contextRunner
                .withUserConfiguration(ConditionalToolConfig.class)
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames())
                                    .doesNotContain("conditional_action");
                        });

        // Case 2: Condition is met, register this tool
        contextRunner
                .withUserConfiguration(ConditionalToolConfig.class)
                .withPropertyValues("feature.custom-tool.enabled=true")
                .run(
                        context -> {
                            Toolkit globalToolkit =
                                    context.getBean("globalAgentscopeToolkit", Toolkit.class);
                            assertThat(globalToolkit.getToolNames()).contains("conditional_action");
                        });
    }

    // ====================================================================================
    // Internal testing configuration class (Mock Beans)
    // ====================================================================================

    @Configuration
    static class NormalToolConfig {
        @Bean
        public CalculatorTool calculatorTool() {
            return new CalculatorTool();
        }
    }

    static class CalculatorTool {
        @Tool(name = "calculator_add", description = "Adds two integers")
        public int add(int a, int b) {
            return a + b;
        }
    }

    @Configuration
    @EnableAsync
    static class AopToolConfig {
        @Bean
        public AsyncService asyncService() {
            return new AsyncService();
        }
    }

    static class AsyncService {
        @Async // Trigger Spring CGLIB proxy
        @Tool(name = "async_action", description = "Executes an action asynchronously")
        public void doAction() {}
    }

    @Configuration
    static class LazyToolConfig {
        @Bean
        @Lazy
        public LazyService lazyService() {
            return new LazyService();
        }
    }

    static class LazyService {
        public static boolean initialized = false;

        public LazyService() {
            initialized = true;
        }

        @Tool(name = "lazy_action", description = "Lazy tool")
        public void execute() {}
    }

    @Configuration
    static class PrototypeToolConfig {
        @Bean
        @Scope("prototype")
        public PrototypeService prototypeService() {
            return new PrototypeService();
        }
    }

    static class PrototypeService {
        @Tool(name = "prototype_action", description = "Prototype tool")
        public void execute() {}
    }

    @Configuration
    static class DuplicateToolConfig {
        @Bean
        public SearchServiceOne serviceOne() {
            return new SearchServiceOne();
        }

        @Bean
        public SearchServiceTwo serviceTwo() {
            return new SearchServiceTwo();
        }
    }

    static class SearchServiceOne {
        @Tool(name = "common_search")
        public void searchWeb() {}
    }

    static class SearchServiceTwo {
        @Tool(name = "common_search")
        public void searchDatabase() {}
    }

    @Configuration
    static class InterfaceToolConfig {
        @Bean
        public WebService webService() {
            return new WebServiceImpl();
        }
    }

    interface WebService {
        @Tool(name = "interface_action", description = "Tool defined on interface")
        void executeWebTask();
    }

    static class WebServiceImpl implements WebService {
        @Override
        public void executeWebTask() {}
    }

    @Configuration
    static class InheritanceToolConfig {
        @Bean
        public ChildService childService() {
            return new ChildService();
        }
    }

    static class BaseService {
        @Tool(name = "superclass_action", description = "Tool defined on superclass")
        public void executeBaseTask() {}
    }

    static class ChildService extends BaseService {
        // Only inherit superclass
    }

    @Configuration
    static class MultiToolConfig {
        @Bean
        public MathService mathService() {
            return new MathService();
        }
    }

    static class MathService {
        @Tool(name = "math_multiply", description = "Multiplies two integers")
        public int multiply(int a, int b) {
            return a * b;
        }

        @Tool(name = "math_divide", description = "Divides two integers")
        public int divide(int a, int b) {
            return a / b;
        }
    }

    @Configuration
    static class ConditionalToolConfig {
        @Bean
        @ConditionalOnProperty(name = "feature.custom-tool.enabled", havingValue = "true")
        public ConditionalService conditionalService() {
            return new ConditionalService();
        }
    }

    static class ConditionalService {
        @Tool(name = "conditional_action", description = "Conditionally loaded tool")
        public void execute() {}
    }
}
