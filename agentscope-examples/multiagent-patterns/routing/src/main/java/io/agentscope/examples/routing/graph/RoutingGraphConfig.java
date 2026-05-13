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
package io.agentscope.examples.routing.graph;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.agent.agentscope.flow.AgentScopeRoutingAgent;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.routing.graph.node.PostprocessNode;
import io.agentscope.examples.routing.graph.node.PreprocessNode;
import io.agentscope.examples.routing.graph.tools.GitHubStubTools;
import io.agentscope.examples.routing.graph.tools.NotionStubTools;
import io.agentscope.examples.routing.graph.tools.SlackStubTools;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the routing-graph workflow: preprocess → LlmRoutingAgent (as node) → postprocess.
 * LlmRoutingAgent includes routing + merge node internally. Sub-agents (GitHub, Notion, Slack) use AgentScopeAgent.
 */
@Configuration
public class RoutingGraphConfig {

    private static final String GITHUB_PROMPT =
            """
            You are a GitHub expert. Answer questions about code, API references, and implementation \
            details by searching repositories, issues, and pull requests.
            Please respond to the following request: {github_input}
            """;

    private static final String NOTION_PROMPT =
            """
            You are a Notion expert. Answer questions about internal processes, policies, and team \
            documentation by searching the organization's Notion workspace.
            Please respond to the following request: {notion_input}
            """;

    private static final String SLACK_PROMPT =
            """
            You are a Slack expert. Answer questions by searching relevant threads and discussions \
            where team members have shared knowledge and solutions.
            Please respond to the following request: {slack_input}
            """;

    private static Model dashScopeModel() {
        String key = System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build();
    }

    @Bean
    public AgentScopeAgent githubAgent(GitHubStubTools githubStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(githubStubTools);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("github")
                        .description("GitHub specialist for code, issues, and PRs")
                        .sysPrompt(GITHUB_PROMPT)
                        .model(dashScopeModel())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory());
        return AgentScopeAgent.fromBuilder(builder)
                .name("github")
                .description("GitHub specialist for code, issues, and PRs")
                .instruction("Please respond to the following request: {github_input}.")
                .outputKey("github_key")
                .build();
    }

    @Bean
    public AgentScopeAgent notionAgent(NotionStubTools notionStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(notionStubTools);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("notion")
                        .description("Notion specialist for docs and wikis")
                        .sysPrompt(NOTION_PROMPT)
                        .model(dashScopeModel())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory());
        return AgentScopeAgent.fromBuilder(builder)
                .name("notion")
                .description("Notion specialist for docs and wikis")
                .instruction("Please respond to the following request: {notion_input}.")
                .outputKey("notion_key")
                .build();
    }

    @Bean
    public AgentScopeAgent slackAgent(SlackStubTools slackStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(slackStubTools);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("slack")
                        .description("Slack specialist for messages and threads")
                        .sysPrompt(SLACK_PROMPT)
                        .model(dashScopeModel())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory());
        return AgentScopeAgent.fromBuilder(builder)
                .name("slack")
                .description("Slack specialist for messages and threads")
                .instruction("Please respond to the following request: {slack_input}.")
                .outputKey("slack_key")
                .build();
    }

    @Bean
    public AgentScopeRoutingAgent routerAgent(
            AgentScopeAgent githubAgent, AgentScopeAgent notionAgent, AgentScopeAgent slackAgent) {
        return AgentScopeRoutingAgent.builder()
                .name("router")
                .model(dashScopeModel())
                .description(
                        "Routes queries to GitHub, Notion, and/or Slack specialists based on"
                                + " relevance.")
                .subAgents(List.of(githubAgent, notionAgent, slackAgent))
                .build();
    }

    @Bean
    public CompiledGraph routingGraph(AgentScopeRoutingAgent routerAgent)
            throws GraphStateException {
        KeyStrategyFactory keyFactory =
                () -> {
                    Map<String, KeyStrategy> strategies = new HashMap<>();
                    strategies.put("input", new ReplaceStrategy());
                    strategies.put("query", new ReplaceStrategy());
                    strategies.put("messages", new AppendStrategy(false));
                    strategies.put("preprocess_metadata", new ReplaceStrategy());
                    strategies.put("merged_result", new ReplaceStrategy());
                    strategies.put("final_answer", new ReplaceStrategy());
                    strategies.put("postprocess_metadata", new ReplaceStrategy());
                    strategies.put("github_key", new ReplaceStrategy());
                    strategies.put("notion_key", new ReplaceStrategy());
                    strategies.put("slack_key", new ReplaceStrategy());
                    return strategies;
                };

        StateGraph graph =
                new StateGraph("routing_graph", keyFactory)
                        .addNode("preprocess", node_async(new PreprocessNode()))
                        .addNode("routing", routerAgent.getAndCompileGraph())
                        .addNode("postprocess", node_async(new PostprocessNode()))
                        .addEdge(START, "preprocess")
                        .addEdge("preprocess", "routing")
                        .addEdge("routing", "postprocess")
                        .addEdge("postprocess", END);

        return graph.compile();
    }

    @Bean
    public RoutingGraphService routingGraphService(CompiledGraph routingGraph) {
        return new RoutingGraphService(routingGraph);
    }
}
