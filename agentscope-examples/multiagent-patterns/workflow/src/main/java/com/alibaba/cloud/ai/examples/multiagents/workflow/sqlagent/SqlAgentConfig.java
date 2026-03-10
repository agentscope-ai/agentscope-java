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
package com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent.node.CallGetSchemaNode;
import com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent.node.ExecuteGetSchemaNode;
import com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent.node.ListTablesNode;
import com.alibaba.cloud.ai.examples.multiagents.workflow.sqlagent.tools.SqlTools;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

/**
 * SQL agent workflow using StateGraph and AgentScope.
 * Flow: START → list_tables → call_get_schema → get_schema (execute) → generate_query (ReActAgent) → END.
 * Uses DashScopeChatModel, AgentScope @Tool in SqlTools, and AgentScopeAgent for the generate_query node.
 */
@Configuration
@ConditionalOnProperty(name = "workflow.sql.enabled", havingValue = "true")
public class SqlAgentConfig {

    private static final String DIALECT = "H2";
    private static final int TOP_K = 5;

    private static final String GENERATE_QUERY_PROMPT =
            """
            You are an agent designed to interact with a SQL database.
            Given an input question, create a syntactically correct %s query to run,
            then look at the results of the query and return the answer. Unless the user
            specifies a specific number of examples they wish to obtain, always limit your
            query to at most %d results.

            You can order the results by a relevant column to return the most interesting
            examples in the database. Never query for all the columns from a specific table,
            only ask for the relevant columns given the question.

            DO NOT make any DML statements (INSERT, UPDATE, DELETE, DROP etc.) to the database.
            """
                    .formatted(DIALECT, TOP_K);

    @Bean
    public SqlTools sqlTools(JdbcTemplate jdbcTemplate) {
        return new SqlTools(jdbcTemplate);
    }

    @Bean
    public Model dashScopeChatModel(@Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        String key = StringUtils.hasText(apiKey) ? apiKey : System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build();
    }

    @Bean
    public CompiledGraph sqlGraph(Model model, SqlTools sqlTools) throws GraphStateException {
        StateGraph graph =
                new StateGraph(
                        "sql_workflow",
                        () -> {
                            Map<String, KeyStrategy> strategies = new HashMap<>();
                            strategies.put("messages", new AppendStrategy(false));
                            strategies.put("llm_response", new ReplaceStrategy());
                            strategies.put("question", new ReplaceStrategy());
                            return strategies;
                        });

        ListTablesNode listTablesNode = new ListTablesNode(sqlTools);
        CallGetSchemaNode callGetSchemaNode = new CallGetSchemaNode(model, sqlTools);
        ExecuteGetSchemaNode executeGetSchemaNode = new ExecuteGetSchemaNode(sqlTools);

        Toolkit generateQueryToolkit = new Toolkit();
        generateQueryToolkit.registerTool(sqlTools);
        AgentScopeAgent generateQueryAgent =
                AgentScopeAgent.fromBuilder(
                                ReActAgent.builder()
                                        .name("generate_query")
                                        .sysPrompt(GENERATE_QUERY_PROMPT)
                                        .model(model)
                                        .toolkit(generateQueryToolkit)
                                        .memory(new InMemoryMemory()))
                        .name("generate_query")
                        .description("Generate and run SQL query")
                        // Either set includeContents to true, or use instruction with {input}
                        // placeholder to pass the original user question to the agent, so it can
                        // generate relevant SQL.
                        .includeContents(true)
                        .returnReasoningContents(false)
                        .build();

        graph.addNode("list_tables", node_async(listTablesNode))
                .addNode("call_get_schema", node_async(callGetSchemaNode))
                .addNode("get_schema", node_async(executeGetSchemaNode))
                .addNode("generate_query", generateQueryAgent.asNode())
                .addEdge(START, "list_tables")
                .addEdge("list_tables", "call_get_schema")
                .addEdge("call_get_schema", "get_schema")
                .addEdge("get_schema", "generate_query")
                .addEdge("generate_query", END);

        return graph.compile();
    }

    @Bean
    public SqlAgentService sqlAgentService(CompiledGraph sqlGraph) {
        return new SqlAgentService(sqlGraph);
    }
}
