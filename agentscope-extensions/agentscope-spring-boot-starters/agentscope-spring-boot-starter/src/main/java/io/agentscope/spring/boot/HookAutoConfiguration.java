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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for {@link Hook} auto-assembly.
 *
 * <p>Isolated from {@link AgentscopeAutoConfiguration} because {@link Hook} and
 * {@link io.agentscope.core.hook.HookEvent} are
 * {@link Deprecated @Deprecated} for removal since 2.0.0. This class will be
 * removed together with the Hook API.
 *
 * <p>When {@code agentscope.agent.enabled=true}, every {@link Hook} bean is
 * auto-injected into the agent builder via an {@link AgentBuilderCustomizer},
 * ordered by {@link org.springframework.core.annotation.Order @Order}.
 */
@AutoConfiguration
@ConditionalOnClass(Hook.class)
@ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
@SuppressWarnings("deprecation")
public class HookAutoConfiguration {

    /**
     * Auto-injects all {@link Hook} beans into the agent builder, ordered by
     * {@link org.springframework.core.annotation.Order @Order}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "hookAutoCustomizer")
    public AgentBuilderCustomizer hookAutoCustomizer(ObjectProvider<Hook> hooks) {
        return builder -> hooks.orderedStream().forEach(builder::hook);
    }
}
