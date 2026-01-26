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

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the agent registry.
 *
 * <p>This auto-configuration provides:
 * <ul>
 *   <li>AguiAgentRegistry bean for managing agents</li>
 *   <li>AguiAgentAutoRegistration bean for registering Agent beans to the AguiAgentRegistry.</li>
 * </ul>
 *
 * <p>To use this auto-configuration, simply add the dependency to your project and register your
 *  agents with the AguiAgentRegistry.
 */
@AutoConfiguration
@ConditionalOnClass(
        value = {
            AguiAgentRegistry.class,
            AguiAgentRegistryCustomizer.class,
            AguiAgentAutoRegistration.class
        })
public class AguiAgentRegistryAutoConfiguration {

    /**
     * Creates the agent registry bean.
     *
     * @param customizerObjectProvider The agent registry customizer object provider
     * @return A new AguiAgentRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiAgentRegistry aguiAgentRegistry(
            ObjectProvider<AguiAgentRegistryCustomizer> customizerObjectProvider) {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        customizerObjectProvider.stream().forEach(customizer -> customizer.customize(registry));
        return registry;
    }

    /**
     * Creates the agent auto-registration bean.
     *
     * @param registry The agent registry
     * @return A new AguiAgentAutoRegistration
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiAgentAutoRegistration aguiAgentAutoRegistration(AguiAgentRegistry registry) {
        return new AguiAgentAutoRegistration(registry);
    }
}
