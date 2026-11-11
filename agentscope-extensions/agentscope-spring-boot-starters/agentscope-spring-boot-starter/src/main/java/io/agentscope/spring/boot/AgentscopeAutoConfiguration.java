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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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
 *   <li><b>Middleware auto-assembly</b> — every {@link MiddlewareBase} bean
 *       is auto-injected into the agent builder via an
 *       {@link AgentBuilderCustomizer}, ordered by
 *       {@link org.springframework.core.annotation.Order @Order}. Opt out with
 *       {@code agentscope.agent.auto-assemble-middleware=false}.</li>
 *   <li><b>PermissionContextState auto-injection</b> — if exactly one
 *       {@link PermissionContextState} bean exists in the context, it is
 *       auto-applied to the agent builder. No-op when absent; a warning is
 *       logged when ambiguous (two or more beans).</li>
 * </ul>
 *
 * <p>Both conveniences are implemented as {@link AgentBuilderCustomizer} beans ordered with
 * {@link Ordered#HIGHEST_PRECEDENCE}, so a user-defined {@code AgentBuilderCustomizer}
 * <b>without an explicit {@code @Order}</b> (and therefore defaulting to
 * {@link Ordered#LOWEST_PRECEDENCE}) runs afterwards and can override them. A user customizer
 * that sets its own small {@code @Order} can run earlier instead.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentscopeProperties.class)
@ConditionalOnClass(ReActAgent.class)
public class AgentscopeAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AgentscopeAutoConfiguration.class);

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
        customizers.orderedStream().forEach(c -> c.customize(builder));
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Middleware auto-assembly
    // ------------------------------------------------------------------

    /**
     * Auto-injects all {@link MiddlewareBase} beans into the agent builder,
     * ordered by {@link org.springframework.core.annotation.Order @Order}.
     *
     * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 10} — before the permission and
     * hook customizers, and before any user-defined {@link AgentBuilderCustomizer}.
     *
     * <p><b>Sharing contract:</b> a {@code MiddlewareBase} bean is a singleton, and every agent
     * built from this auto-configuration receives the same instance. Middleware must therefore be
     * stateless / thread-safe — keep per-request state in {@code RuntimeContext}, never in
     * instance fields.
     *
     * <p>Disable with {@code agentscope.agent.auto-assemble-middleware=false} when middleware is
     * wired manually, to avoid attaching the same middleware twice. To replace just the assembly
     * logic, shadow the {@code middlewareAutoCustomizer} bean by name.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(
            prefix = "agentscope.agent",
            name = "auto-assemble-middleware",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(name = "middlewareAutoCustomizer")
    public AgentBuilderCustomizer middlewareAutoCustomizer(
            ObjectProvider<MiddlewareBase> middlewares) {
        return builder -> {
            List<MiddlewareBase> beans = middlewares.orderedStream().toList();
            if (!beans.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Auto-assembled {} MiddlewareBase bean class(es): {}",
                            beans.size(),
                            beans.stream().map(b -> b.getClass().getSimpleName()).toList());
                }
                beans.forEach(builder::middleware);
            }
        };
    }

    // ------------------------------------------------------------------
    // PermissionContextState auto-injection
    // ------------------------------------------------------------------

    /**
     * If exactly one {@link PermissionContextState} bean exists in the context,
     * auto-applies it to the agent builder. No-op when no
     * {@code PermissionContextState} bean is present.
     *
     * <p>When more than one {@code PermissionContextState} bean is present the context is
     * ambiguous, so nothing is injected and a warning is logged — the permission engine decides
     * allow/approve/deny, so silently picking one would be a security-relevant choice.
     *
     * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 20} — after middleware assembly
     * but still before the hook customizer and any user-defined {@link AgentBuilderCustomizer}.
     *
     * <p>Backs off when a bean named {@code permissionContextAutoCustomizer} already exists
     * ({@code @ConditionalOnMissingBean(name = ...)}), so supplying one disables or replaces this
     * auto-injection.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "permissionContextAutoCustomizer")
    public AgentBuilderCustomizer permissionContextAutoCustomizer(
            ObjectProvider<PermissionContextState> permissionContext) {
        return builder -> {
            List<PermissionContextState> contexts = permissionContext.orderedStream().toList();
            if (contexts.size() == 1) {
                builder.permissionContext(contexts.get(0));
            } else if (contexts.size() > 1) {
                logger.warn(
                        "Found {} PermissionContextState beans; refusing to auto-inject an"
                                + " ambiguous permission context. Declare exactly one bean, or"
                                + " apply it via a user-defined AgentBuilderCustomizer.",
                        contexts.size());
            } else {
                logger.debug(
                        "No PermissionContextState bean present;"
                                + " skipping permission context auto-injection");
            }
        };
    }
}
