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

package io.agentscope.spring.boot.nacos;

import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosPromptProperties;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import java.util.Properties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that builds ReActAgent from prompts stored in Nacos.
 *
 * <p>When this starter is on the classpath and {@code agentscope.nacos.prompt.enabled=true}, the
 * Agent's system prompt will be loaded from Nacos via {@link NacosPromptListener}. The Nacos
 * prompt takes precedence over {@code agentscope.agent.sys-prompt} defined in YAML, with the YAML
 * value still acting as a default fallback.
 */
@AutoConfiguration
@AutoConfigureBefore(AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties({
    AgentScopeNacosProperties.class,
    AgentScopeNacosPromptProperties.class,
    AgentscopeProperties.class
})
@ConditionalOnClass(NacosPromptListener.class)
@ConditionalOnProperty(
        prefix = NacosConstants.NACOS_PROMPT_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentscopeNacosPromptAutoConfiguration {

    @Bean(name = "agentscopePromptConfigService")
    @ConditionalOnMissingBean(name = "agentscopePromptConfigService")
    public ConfigService agentscopePromptConfigService(
            AgentScopeNacosProperties nacosProperties,
            AgentScopeNacosPromptProperties promptNacosProperties)
            throws NacosException {
        // Start from the global Nacos configuration (with defaults)
        Properties result = nacosProperties.getNacosProperties();
        // Only overlay explicitly configured prompt-specific fields (no defaults)
        result.putAll(promptNacosProperties.getExplicitNacosProperties());
        return ConfigFactory.createConfigService(result);
    }

    @Bean
    @ConditionalOnMissingBean(NacosPromptListener.class)
    public NacosPromptListener nacosPromptListener(ConfigService agentscopePromptConfigService) {
        return new NacosPromptListener(agentscopePromptConfigService);
    }
}
