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
package com.alibaba.cloud.ai.examples.multiagents.pipeline.loop;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LoopAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.LoopMode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LoopAgent example: SQL refinement until quality threshold.
 *
 * <p>Business scenario: Generate SQL from natural language and iteratively refine until the quality
 * score exceeds 0.5. Each iteration:
 * <ol>
 *   <li>SQL Generator: Produces SQL from user request</li>
 *   <li>SQL Rater: Scores the SQL (0-1)</li>
 * </ol>
 * Loop continues until score &gt; 0.5 or max iterations reached.
 *
 * <p>Uses Spring AI Alibaba LoopAgent and SequentialAgent with AgentScopeAgent sub-agents (AgentScope Model / DashScopeChatModel).
 */
@Configuration
public class LoopPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(LoopPipelineConfig.class);

    private static final double QUALITY_THRESHOLD = 0.5;

    private static final String SQL_GENERATOR_PROMPT =
            """
            You are a MySQL database expert. Given the user's natural language request, output the corresponding SQL statement.
            Only output valid MySQL SQL. Do not include explanations.
            """;

    private static final String SQL_RATER_PROMPT =
            """
            You are a SQL quality reviewer. Given the user's natural language request and the generated SQL,
            output a single float score between 0 and 1. The score indicates how well the SQL matches the user intent.
            Output ONLY the number, no other text. Example: 0.85
            """;

    @Bean("loopSqlRefinementAgent")
    public LoopAgent loopSqlRefinementAgent(Model dashScopeChatModel) {
        ReActAgent.Builder sqlGenBuilder =
                ReActAgent.builder()
                        .name("sql_generator")
                        .model(dashScopeChatModel)
                        .description("Converts natural language to MySQL SQL")
                        .sysPrompt(SQL_GENERATOR_PROMPT)
                        .memory(new InMemoryMemory());
        AgentScopeAgent sqlGenerateAgent =
                AgentScopeAgent.fromBuilder(sqlGenBuilder)
                        .name("sql_generator")
                        .description("Converts natural language to MySQL SQL")
                        .instruction("{input}")
                        .includeContents(false)
                        .outputKey("sql")
                        .build();

        ReActAgent.Builder sqlRaterBuilder =
                ReActAgent.builder()
                        .name("sql_rater")
                        .model(dashScopeChatModel)
                        .description("Scores SQL against user intent")
                        .sysPrompt(SQL_RATER_PROMPT)
                        .memory(new InMemoryMemory());
        AgentScopeAgent sqlRatingAgent =
                AgentScopeAgent.fromBuilder(sqlRaterBuilder)
                        .name("sql_rater")
                        .description("Scores SQL against user intent")
                        .instruction(
                                "Here's the generated SQL:\n"
                                        + " {sql}.\n\n"
                                        + " Here's the original user request:\n"
                                        + " {input}.")
                        .includeContents(false)
                        .outputKey("score")
                        .build();

        SequentialAgent sqlAgent =
                SequentialAgent.builder()
                        .name("sql_agent")
                        .description("Generates SQL and scores its quality")
                        .subAgents(List.of(sqlGenerateAgent, sqlRatingAgent))
                        .build();

        return LoopAgent.builder()
                .name("loop_sql_refinement_agent")
                .description(
                        "Iteratively refines SQL until quality score exceeds " + QUALITY_THRESHOLD)
                .subAgent(sqlAgent)
                .loopStrategy(
                        LoopMode.condition(
                                messages -> {
                                    if (messages == null || messages.isEmpty()) {
                                        return false;
                                    }
                                    String text = messages.get(messages.size() - 1).getText();
                                    if (text == null || text.isBlank()) {
                                        return false;
                                    }
                                    try {
                                        double score = Double.parseDouble(text.trim());
                                        boolean satisfied = score > QUALITY_THRESHOLD;
                                        if (satisfied) {
                                            log.debug(
                                                    "SQL quality score {} exceeds threshold {},"
                                                            + " stopping loop",
                                                    score,
                                                    QUALITY_THRESHOLD);
                                        }
                                        return satisfied;
                                    } catch (NumberFormatException e) {
                                        log.debug("Could not parse score from: {}", text);
                                        return false;
                                    }
                                }))
                .build();
    }
}
