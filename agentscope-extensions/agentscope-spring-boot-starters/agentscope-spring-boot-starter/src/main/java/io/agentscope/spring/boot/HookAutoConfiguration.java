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

import io.agentscope.core.hook.Hook;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Auto-configuration for {@link Hook} auto-assembly.
 *
 * <p>Isolated from {@link AgentscopeAutoConfiguration} because {@link Hook} and
 * {@link io.agentscope.core.hook.HookEvent} are
 * {@link Deprecated @Deprecated} for removal since 2.0.0. This class will be
 * removed together with the Hook API.
 *
 * <p><b>Opt-in:</b> hook auto-attach is disabled by default because {@link Hook} is deprecated
 * for removal. Enable it with {@code agentscope.agent.auto-assemble-hooks=true}; when enabled,
 * every {@link Hook} bean is attached to the agent builder, so applications that also attach
 * hooks manually must remove the manual attachment to avoid registering the same hook twice.
 *
 * <p>The injected hooks are ordered by
 * {@link org.springframework.core.annotation.Order @Order}. The customizer
 * itself is ordered at {@link Ordered#HIGHEST_PRECEDENCE}{@code + 30} so that it
 * runs after the built-in middleware and permission customizers but still before
 * any user-defined {@link AgentBuilderCustomizer}.
 */
@AutoConfiguration
@ConditionalOnClass(Hook.class)
@ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
@SuppressWarnings("deprecation")
public class HookAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(HookAutoConfiguration.class);

    /**
     * Auto-injects all {@link Hook} beans into the agent builder, ordered by
     * {@link org.springframework.core.annotation.Order @Order}.
     *
     * <p>Disabled by default; enable with {@code agentscope.agent.auto-assemble-hooks=true}.
     *
     * <p>Backs off when a bean named {@code hookAutoCustomizer} already exists
     * ({@code @ConditionalOnMissingBean(name = ...)}), so supplying one disables or replaces this
     * auto-attach.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 30)
    @ConditionalOnProperty(
            prefix = "agentscope.agent",
            name = "auto-assemble-hooks",
            havingValue = "true")
    @ConditionalOnMissingBean(name = "hookAutoCustomizer")
    public AgentBuilderCustomizer hookAutoCustomizer(ObjectProvider<Hook> hooks) {
        return builder -> {
            List<Hook> beans = hooks.orderedStream().toList();
            if (!beans.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Auto-assembled {} Hook bean class(es): {}",
                            beans.size(),
                            beans.stream().map(b -> b.getClass().getSimpleName()).toList());
                }
                beans.forEach(builder::hook);
            }
        };
    }
}
