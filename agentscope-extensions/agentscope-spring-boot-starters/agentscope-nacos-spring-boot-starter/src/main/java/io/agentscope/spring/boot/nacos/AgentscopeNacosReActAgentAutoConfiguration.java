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

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosPromptProperties;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * Auto-configuration that assembles {@link ReActAgent} instances backed by Nacos-managed prompts.
 *
 * <p>This configuration is responsible only for wiring the Agent from existing building blocks
 * (model, memory, toolkit, Nacos prompt infrastructure). It is separated from
 * {@code AgentscopeNacosPromptAutoConfiguration}, which focuses on Nacos prompt-related
 * components such as {@code ConfigService} and {@code NacosPromptListener}.
 */
@AutoConfiguration
@AutoConfigureBefore(AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties({AgentscopeProperties.class, AgentScopeNacosPromptProperties.class})
@ConditionalOnClass(ReActAgent.class)
@ConditionalOnProperty(
        prefix = NacosConstants.NACOS_PROMPT_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentscopeNacosReActAgentAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeNacosReActAgentAutoConfiguration.class);

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(ReActAgent.class)
    public ReActAgent nacosPromptReActAgent(
            Model model,
            Memory memory,
            Toolkit toolkit,
            AgentscopeProperties properties,
            AgentScopeNacosPromptProperties nacosPromptProperties,
            NacosPromptListener nacosPromptListener) {

        AgentProperties agentConfig = properties.getAgent();
        String defaultSysPrompt = agentConfig.getSysPrompt();

        String sysPrompt = defaultSysPrompt;
        String promptKey = nacosPromptProperties.getSysPromptKey();

        if (promptKey != null && !promptKey.isEmpty()) {
            try {
                sysPrompt =
                        nacosPromptListener.getPrompt(
                                promptKey, nacosPromptProperties.getVariables(), defaultSysPrompt);
            } catch (NacosException e) {
                log.warn(
                        "Failed to load sys prompt from Nacos for key: {}, fallback to default"
                                + " sys-prompt.",
                        promptKey,
                        e);
            }
        }

        return ReActAgent.builder()
                .name(agentConfig.getName())
                .sysPrompt(sysPrompt)
                .model(model)
                .memory(memory)
                .toolkit(toolkit)
                .maxIters(agentConfig.getMaxIters())
                .build();
    }
}
