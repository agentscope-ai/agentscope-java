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
package io.agentscope.examples.routing.simple;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.agent.agentscope.flow.AgentScopeRoutingAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.routing.simple.tools.GitHubStubTools;
import io.agentscope.examples.routing.simple.tools.NotionStubTools;
import io.agentscope.examples.routing.simple.tools.SlackStubTools;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the router workflow using AgentScope Model (DashScopeChatModel) and AgentScopeRoutingAgent:
 * classifier + parallel specialist agents (GitHub, Notion, Slack) implemented as AgentScopeAgent.
 * RouterService wraps the agent and adds synthesis using the same AgentScope Model.
 */
@Configuration
public class RoutingConfig {

    /**
     * Sub-agent instruction. Placeholder {@code {github_input}} follows the fixed format
     * {@code agentName + "_input"} and receives the sub-task description from the routing node.
     */
    private static final String GITHUB_PROMPT =
            """
            You are a GitHub expert. Answer questions about code, API references, and implementation \
            details by searching repositories, issues, and pull requests.
            Please respond to the following request: {github_input}
            """;

    private static final String NOTION_INSTRUCTION =
            """
            You are a Notion expert. Answer questions about internal processes, policies, and team \
            documentation by searching the organization's Notion workspace.
            Please respond to the following request: {notion_input}
            """;

    private static final String SLACK_INSTRUCTION =
            """
            You are a Slack expert. Answer questions by searching relevant threads and discussions \
            where team members have shared knowledge and solutions.
            Please respond to the following request: {slack_input}
            """;

    /** AgentScope DashScope model bean used by sub-agents, router, and synthesis. */
    @Bean
    public Model dashScopeChatModel() {
        String key = System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build();
    }

    @Bean
    public AgentScopeAgent githubAgent(Model dashScopeChatModel, GitHubStubTools githubStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(githubStubTools);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("github")
                        .description("GitHub specialist for code, issues, and PRs")
                        .sysPrompt(GITHUB_PROMPT)
                        .model(dashScopeChatModel)
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
    public AgentScopeAgent notionAgent(Model dashScopeChatModel, NotionStubTools notionStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(notionStubTools);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("notion")
                        .description("Notion specialist for docs and wikis")
                        .sysPrompt(NOTION_INSTRUCTION)
                        .model(dashScopeChatModel)
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
    public AgentScopeAgent slackAgent(Model dashScopeChatModel, SlackStubTools slackStubTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(slackStubTools);
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("slack")
                        .description("Slack specialist for messages and threads")
                        .sysPrompt(SLACK_INSTRUCTION)
                        .model(dashScopeChatModel)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory());
        return AgentScopeAgent.fromBuilder(builder)
                .name("slack")
                .description(
                        "Searches Slack threads and discussions for team-shared knowledge and"
                                + " solutions.")
                .instruction("Please respond to the following request: {slack_input}.")
                .outputKey("slack_key")
                .build();
    }

    @Bean
    public AgentScopeRoutingAgent routerAgent(
            Model dashScopeChatModel,
            AgentScopeAgent githubAgent,
            AgentScopeAgent notionAgent,
            AgentScopeAgent slackAgent) {
        return AgentScopeRoutingAgent.builder()
                .name("router")
                .model(dashScopeChatModel)
                .description(
                        "Routes queries to GitHub, Notion, and/or Slack specialists based on"
                                + " relevance.")
                .subAgents(List.of(githubAgent, notionAgent, slackAgent))
                .build();
    }

    @Bean
    public RouterService routerService(
            Model dashScopeChatModel, AgentScopeRoutingAgent routerAgent) {
        return new RouterService(dashScopeChatModel, routerAgent);
    }
}
