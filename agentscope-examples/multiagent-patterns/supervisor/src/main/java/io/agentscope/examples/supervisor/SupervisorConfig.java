/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.supervisor;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.supervisor.tools.CalendarStubTools;
import io.agentscope.examples.supervisor.tools.EmailStubTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Configures the supervisor personal assistant using AgentScope: DashScopeChatModel,
 * calendar and email ReActAgents with stub tools (AgentScope @Tool), and a supervisor
 * ReActAgent that delegates to them via Toolkit.registration().subAgent().
 */
@Configuration
public class SupervisorConfig {

    private static final String CALENDAR_AGENT_PROMPT =
            """
            You are a calendar scheduling assistant. \
            Parse natural language scheduling requests (e.g., 'next Tuesday at 2pm') \
            into proper ISO datetime formats. \
            Use get_available_time_slots to check availability when needed. \
            Use create_calendar_event to schedule events. \
            Always confirm what was scheduled in your final response.
            """;

    private static final String EMAIL_AGENT_PROMPT =
            """
            You are an email assistant. \
            Compose professional emails based on natural language requests. \
            Extract recipient information and craft appropriate subject lines and body text. \
            Use send_email to send the message. \
            Always confirm what was sent in your final response.
            """;

    private static final String SUPERVISOR_PROMPT =
            """
            You are a helpful personal assistant. \
            You can schedule calendar events and send emails. \
            Break down user requests into appropriate tool calls and coordinate the results. \
            When a request involves multiple actions, use multiple tools in sequence.
            """;

    @Bean
    public Model dashScopeChatModel(@Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        String key = StringUtils.hasText(apiKey) ? apiKey : System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build();
    }

    @Bean
    public CalendarStubTools calendarStubTools() {
        return new CalendarStubTools();
    }

    @Bean
    public EmailStubTools emailStubTools() {
        return new EmailStubTools();
    }

    @Bean
    public ReActAgent calendarAgent(Model model, CalendarStubTools calendarStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(calendarStubTools);
        return ReActAgent.builder()
                .name("schedule_event")
                .description("Calendar scheduling assistant")
                .sysPrompt(CALENDAR_AGENT_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Bean
    public ReActAgent emailAgent(Model model, EmailStubTools emailStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(emailStubTools);
        return ReActAgent.builder()
                .name("manage_email")
                .description("Email assistant")
                .sysPrompt(EMAIL_AGENT_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Bean("supervisorAgent")
    public ReActAgent supervisorAgent(
            Model model, ReActAgent calendarAgent, ReActAgent emailAgent) {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().subAgent(() -> calendarAgent).apply();
        toolkit.registration().subAgent(() -> emailAgent).apply();
        return ReActAgent.builder()
                .name("personal_assistant")
                .sysPrompt(SUPERVISOR_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }
}
