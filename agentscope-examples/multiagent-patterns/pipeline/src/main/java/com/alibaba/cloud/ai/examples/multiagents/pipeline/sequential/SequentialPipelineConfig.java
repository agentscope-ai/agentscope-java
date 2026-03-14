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
package com.alibaba.cloud.ai.examples.multiagents.pipeline.sequential;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SequentialAgent example: Natural language to SQL generation pipeline.
 *
 * <p>Business scenario: User describes a query in natural language, the pipeline:
 * <ol>
 *   <li>SQL Generator: Converts natural language to MySQL SQL</li>
 *   <li>SQL Rater: Scores how well the SQL matches the user intent (0-1)</li>
 * </ol>
 *
 * <p>Uses Spring AI Alibaba SequentialAgent with AgentScopeAgent sub-agents (AgentScope Model / DashScopeChatModel).
 */
@Configuration
public class SequentialPipelineConfig {

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

    @Bean("sequentialSqlAgent")
    public SequentialAgent sequentialSqlAgent(Model dashScopeChatModel) {
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

        return SequentialAgent.builder()
                .name("sequential_sql_agent")
                .description(
                        "Natural language to SQL pipeline: generates SQL and scores its quality")
                .subAgents(List.of(sqlGenerateAgent, sqlRatingAgent))
                .build();
    }
}
