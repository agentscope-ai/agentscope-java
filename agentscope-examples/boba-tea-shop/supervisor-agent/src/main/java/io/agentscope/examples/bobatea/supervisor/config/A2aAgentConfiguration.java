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

package io.agentscope.examples.bobatea.supervisor.config;

import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.extensions.nacos.a2a.discovery.NacosAgentCardResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class A2aAgentConfiguration {

    @Bean
    public A2aAgent consultAgent(AiService a2aService) {
        return A2aAgent.builder()
                .name("consult_agent")
                .agentCardResolver(new NacosAgentCardResolver(a2aService))
                .build();
    }

    @Bean
    public A2aAgent businessAgent(AiService a2aService) {
        return A2aAgent.builder()
                .name("business_agent")
                .agentCardResolver(new NacosAgentCardResolver(a2aService))
                .build();
    }
}
