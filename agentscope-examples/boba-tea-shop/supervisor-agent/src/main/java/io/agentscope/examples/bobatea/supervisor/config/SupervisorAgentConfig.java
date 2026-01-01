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

import io.agentscope.core.model.Model;
import io.agentscope.examples.bobatea.supervisor.agent.SupervisorAgent;
import io.agentscope.examples.bobatea.supervisor.tools.A2aAgentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupervisorAgentConfig {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgentConfig.class);

    @Autowired private SupervisorAgentPromptConfig promptConfig;

    @Bean
    public SupervisorAgent supervisorAgent(Model model, A2aAgentTools tools) {
        logger.info("SupervisorAgent initialized - creates new agent for each request");
        return new SupervisorAgent(model, tools, promptConfig.getSupervisorAgentInstruction());
    }
}
