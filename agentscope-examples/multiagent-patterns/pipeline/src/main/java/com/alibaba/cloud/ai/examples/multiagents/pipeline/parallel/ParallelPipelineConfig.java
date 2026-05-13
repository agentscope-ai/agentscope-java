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
package com.alibaba.cloud.ai.examples.multiagents.pipeline.parallel;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ParallelAgent example: Multi-topic research.
 *
 * <p>Business scenario: User provides a broad topic, the pipeline researches it from multiple angles in parallel:
 * <ul>
 *   <li>Technology perspective</li>
 *   <li>Finance / business perspective</li>
 *   <li>Market / industry perspective</li>
 * </ul>
 *
 * <p>Uses Spring AI Alibaba ParallelAgent with AgentScopeAgent sub-agents (AgentScope Model / DashScopeChatModel).
 */
@Configuration
public class ParallelPipelineConfig {

    private static final String TECH_RESEARCH_PROMPT =
            """
            You are a technology analyst. Research the given topic from a technology perspective.
            Provide a concise 2-3 paragraph analysis covering: key technologies, trends, and innovations.
            Focus on technical aspects only.
            """;

    private static final String FINANCE_RESEARCH_PROMPT =
            """
            You are a financial analyst. Research the given topic from a finance and business perspective.
            Provide a concise 2-3 paragraph analysis covering: market size, investment trends, business models.
            Focus on financial and business aspects only.
            """;

    private static final String MARKET_RESEARCH_PROMPT =
            """
            You are a market analyst. Research the given topic from an industry and market perspective.
            Provide a concise 2-3 paragraph analysis covering: competitive landscape, growth drivers, challenges.
            Focus on market and industry aspects only.
            """;

    @Bean("parallelResearchAgent")
    public ParallelAgent parallelResearchAgent(Model dashScopeChatModel) {
        ReActAgent.Builder techBuilder =
                ReActAgent.builder()
                        .name("tech_researcher")
                        .model(dashScopeChatModel)
                        .description("Researches from technology perspective")
                        .sysPrompt(TECH_RESEARCH_PROMPT)
                        .memory(new InMemoryMemory());
        AgentScopeAgent techResearcher =
                AgentScopeAgent.fromBuilder(techBuilder)
                        .name("tech_researcher")
                        .description("Researches from technology perspective")
                        .instruction("Research the following topic: {input}.")
                        .includeContents(false)
                        .outputKey("tech_analysis")
                        .build();

        ReActAgent.Builder financeBuilder =
                ReActAgent.builder()
                        .name("finance_researcher")
                        .model(dashScopeChatModel)
                        .description("Researches from finance perspective")
                        .sysPrompt(FINANCE_RESEARCH_PROMPT)
                        .memory(new InMemoryMemory());
        AgentScopeAgent financeResearcher =
                AgentScopeAgent.fromBuilder(financeBuilder)
                        .name("finance_researcher")
                        .description("Researches from finance perspective")
                        .instruction("Research the following topic: {input}.")
                        .includeContents(false)
                        .outputKey("finance_analysis")
                        .build();

        ReActAgent.Builder marketBuilder =
                ReActAgent.builder()
                        .name("market_researcher")
                        .model(dashScopeChatModel)
                        .description("Researches from market perspective")
                        .sysPrompt(MARKET_RESEARCH_PROMPT)
                        .memory(new InMemoryMemory());
        AgentScopeAgent marketResearcher =
                AgentScopeAgent.fromBuilder(marketBuilder)
                        .name("market_researcher")
                        .description("Researches from market perspective")
                        .instruction("Research the following topic: {input}.")
                        .outputKey("market_analysis")
                        .build();

        return ParallelAgent.builder()
                .name("parallel_research_agent")
                .description(
                        "Multi-topic research: analyzes a topic from tech, finance, and market"
                                + " angles in parallel")
                .subAgents(List.of(techResearcher, financeResearcher, marketResearcher))
                .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
                .mergeOutputKey("research_report")
                .maxConcurrency(3)
                .build();
    }
}
