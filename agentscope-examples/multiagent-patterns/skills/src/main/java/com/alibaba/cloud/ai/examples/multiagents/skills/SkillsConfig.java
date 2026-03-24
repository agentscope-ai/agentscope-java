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
package com.alibaba.cloud.ai.examples.multiagents.skills;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the SQL assistant agent using AgentScope skill support.
 *
 * <ul>
 *   <li>{@link ClasspathSkillRepository} loads skills from classpath {@code skills/}
 *       (each subdirectory with a {@code SKILL.md} is one skill).</li>
 *   <li>{@link SkillBox} holds a {@link Toolkit} and registers all loaded skills;
 *       the agent gets the skill system prompt and the read_skill / use_skill tools from the SkillBox.</li>
 *   <li>{@link ReActAgent} uses {@link DashScopeChatModel}, the toolkit, skillBox, and memory.</li>
 * </ul>
 *
 * <p>Progressive disclosure: the agent sees skill descriptions in the system prompt and loads
 * full skill content on demand via the skill tools.
 */
@Configuration
public class SkillsConfig {

    private static final String SYSTEM_PROMPT =
            """
            You are a SQL query assistant that helps users write queries against business databases.
            Use the read_skill tool when you need detailed schema or business logic for a specific domain.
            """;

    @Bean
    public ClasspathSkillRepository skillRepository() throws IOException {
        return new ClasspathSkillRepository("skills");
    }

    @Bean
    public Toolkit toolkit() {
        return new Toolkit();
    }

    @Bean
    public SkillBox skillBox(Toolkit toolkit, ClasspathSkillRepository skillRepository) {
        SkillBox skillBox = new SkillBox(toolkit);
        List<AgentSkill> skills = skillRepository.getAllSkills();
        for (AgentSkill skill : skills) {
            skillBox.registration().skill(skill).apply();
        }
        return skillBox;
    }

    @Bean("sqlAssistantAgent")
    public ReActAgent sqlAssistantAgent(Toolkit toolkit, SkillBox skillBox) {
        String key = System.getenv("AI_DASHSCOPE_API_KEY");
        return ReActAgent.builder()
                .name("sql_assistant")
                .sysPrompt(SYSTEM_PROMPT)
                .model(DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build())
                .toolkit(toolkit)
                .skillBox(skillBox)
                .memory(new InMemoryMemory())
                .build();
    }
}
