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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Supervisor Agent Prompt Configuration Class
 */
@Configuration
@ConfigurationProperties(prefix = "agent.prompts")
public class SupervisorAgentPromptConfig {

    /**
     * Supervisor Agent prompt
     */
    private String supervisorAgentInstruction;

    /**
     * TODO Re-adapt for xxljob
     */
    private String schedulingAgentInstruction;

    private String dailyReportAgentInstruction;

    public String getSupervisorAgentInstruction() {
        return supervisorAgentInstruction;
    }

    public void setSupervisorAgentInstruction(String supervisorAgentInstruction) {
        this.supervisorAgentInstruction = supervisorAgentInstruction;
    }

    public String getSchedulingAgentInstruction() {
        return schedulingAgentInstruction;
    }

    public void setSchedulingAgentInstruction(String schedulingAgentInstruction) {
        this.schedulingAgentInstruction = schedulingAgentInstruction;
    }

    public String getDailyReportAgentInstruction() {
        return dailyReportAgentInstruction;
    }

    public void setDailyReportAgentInstruction(String dailyReportAgentInstruction) {
        this.dailyReportAgentInstruction = dailyReportAgentInstruction;
    }
}
