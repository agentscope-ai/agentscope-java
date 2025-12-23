/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.examples.bobatea.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent提示词配置类
 * 用于管理Agent的提示词内容
 */
@Configuration
@ConfigurationProperties(prefix = "agent.prompts")
public class AgentPromptConfig {

    /**
     * 订单与反馈Agent提示词（合并后的统一提示词）
     */
    private String businessAgentInstruction;

    public String getBusinessAgentInstruction() {
        return businessAgentInstruction;
    }

    public void setBusinessAgentInstruction(String businessAgentInstruction) {
        this.businessAgentInstruction = businessAgentInstruction;
    }
}
