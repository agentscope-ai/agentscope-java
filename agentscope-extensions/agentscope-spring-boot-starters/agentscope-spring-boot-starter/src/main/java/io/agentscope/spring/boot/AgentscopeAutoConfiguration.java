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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import io.agentscope.spring.boot.tool.ToolAutoRegistrationBeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * Spring Boot auto-configuration that exposes default Memory, Toolkit and ReActAgent beans for
 * AgentScope.
 *
 * <p>Model beans are provided by provider-specific starters such as
 * {@code agentscope-dashscope-spring-boot-starter}, {@code agentscope-openai-spring-boot-starter},
 * {@code agentscope-gemini-spring-boot-starter}, {@code agentscope-anthropic-spring-boot-starter},
 * or by user-defined {@link Model} beans.
 *
 * <p>Basic configuration:
 *
 * <pre>{@code
 * agentscope:
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *     max-iters: 10
 * }</pre>
 *
 * <p>In addition to the core beans, this configuration provides the following
 * conveniences when {@code agentscope.agent.enabled=true}:
 *
 * <ul>
 *   <li><b>Tool auto-registration</b> — beans annotated with
 *       {@link io.agentscope.spring.boot.tool.ToolBean @ToolBean} are
 *       automatically registered into every {@link Toolkit} via a
 *       {@link ToolAutoRegistrationBeanPostProcessor}.</li>
 *   <li><b>Middleware auto-assembly</b> — every {@link MiddlewareBase} bean
 *       is auto-injected into the agent builder via an
 *       {@link AgentBuilderCustomizer}, ordered by
 *       {@link org.springframework.core.annotation.Order @Order}.</li>
 *   <li><b>PermissionContextState auto-injection</b> — if a
 *       {@link PermissionContextState} bean exists in the context, it is
 *       auto-applied to the agent builder.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentscopeProperties.class)
@ConditionalOnClass(ReActAgent.class)
public class AgentscopeAutoConfiguration {

    /**
     * Default Memory implementation backed by InMemoryMemory.
     *
     * <p>
     * Memory is stateful and not thread-safe, so we expose it as a prototype-scoped
     * bean.
     * In multi-threaded / web environments, it is recommended to obtain instances
     * lazily via
     * {@code ObjectProvider<Memory>} or method injection.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Memory agentscopeMemory() {
        return new InMemoryMemory();
    }

    /**
     * Default Toolkit implementation with an initially empty tool set.
     *
     * <p>
     * Toolkit holds mutable state and is not thread-safe, so it is also exposed as
     * a
     * prototype-scoped bean. In application code, prefer obtaining instances lazily
     * via
     * {@code ObjectProvider<Toolkit>} or method injection.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Toolkit agentscopeToolkit() {
        return new Toolkit();
    }

    /**
     * Default ReActAgent that wires together the configured Model, Memory and
     * Toolkit beans using
     * {@link AgentProperties}.
     *
     * ReActAgent in 2.0 is thread-safe, so we just use a singleton instance.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Model.class)
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    public ReActAgent agentscopeReActAgent(
            Model model,
            Memory memory,
            Toolkit toolkit,
            AgentscopeProperties properties,
            ObjectProvider<AgentBuilderCustomizer> customizers) {
        AgentProperties config = properties.getAgent();
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name(config.getName())
                        .sysPrompt(config.getSysPrompt())
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(config.getMaxIters());
        customizers.orderedStream().forEach(c -> c.accept(builder));
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Tool auto-registration
    // ------------------------------------------------------------------

    /**
     * {@link org.springframework.beans.factory.config.BeanPostProcessor} that
     * auto-registers {@link io.agentscope.spring.boot.tool.ToolBean @ToolBean}
     * beans into every {@link Toolkit}.
     *
     * <p>Declared as {@code static} so that the configuration class is not
     * instantiated prematurely.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public static ToolAutoRegistrationBeanPostProcessor toolAutoRegistrationBeanPostProcessor() {
        return new ToolAutoRegistrationBeanPostProcessor();
    }

    // ------------------------------------------------------------------
    // Middleware auto-assembly
    // ------------------------------------------------------------------

    /**
     * Auto-injects all {@link MiddlewareBase} beans into the agent builder,
     * ordered by {@link org.springframework.core.annotation.Order @Order}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "middlewareAutoCustomizer")
    public AgentBuilderCustomizer middlewareAutoCustomizer(
            ObjectProvider<MiddlewareBase> middlewares) {
        return builder -> middlewares.orderedStream().forEach(builder::middleware);
    }

    // ------------------------------------------------------------------
    // PermissionContextState auto-injection
    // ------------------------------------------------------------------

    /**
     * If a {@link PermissionContextState} bean exists in the context,
     * auto-applies it to the agent builder. No-op when no
     * {@code PermissionContextState} bean is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "permissionContextAutoCustomizer")
    public AgentBuilderCustomizer permissionContextAutoCustomizer(
            ObjectProvider<PermissionContextState> permissionContext) {
        return builder -> {
            PermissionContextState ctx = permissionContext.getIfAvailable();
            if (ctx != null) {
                builder.permissionContext(ctx);
            }
        };
    }
}
