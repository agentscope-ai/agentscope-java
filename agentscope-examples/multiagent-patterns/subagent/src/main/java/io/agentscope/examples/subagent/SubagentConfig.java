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
package io.agentscope.examples.subagent;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
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
import io.agentscope.examples.subagent.tools.GlobSearchTool;
import io.agentscope.examples.subagent.tools.GrepSearchTool;
import io.agentscope.examples.subagent.tools.WebFetchTool;
import io.agentscope.examples.subagent.tools.task.TaskToolsBuilder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Configures the Tech Due Diligence Assistant using AgentScope. Sub-agents codebase-explorer,
 * web-researcher, and general-purpose are loaded from classpath resources (agents/*.md) via
 * {@link TaskToolsBuilder}; dependency-analyzer is the single programmatic ReActAgent.
 * Orchestrator uses Task and TaskOutput tools to delegate to sub-agents. Exposes
 * dependencyAnalyzerAgent and orchestratorAgent as AgentScopeAgent; orchestrator is invoked via
 * a single-node graph and {@link OrchestratorService}.
 */
@Configuration
public class SubagentConfig {

    private static final String ORCHESTRATOR_SYSTEM_PROMPT =
            """
            You are a Tech Due Diligence Assistant. You help users evaluate software projects by combining:
            - Codebase analysis (structure, dependencies, patterns, technical debt)
            - Web research (documentation, alternatives, benchmarks, ecosystem)

            **Workflow:**
            1. Delegate to specialized sub-agents via the Task tool:
               - **codebase-explorer**: Finding files, searching code, analyzing structure. Use for "find X in codebase", "what frameworks does this use", "list all Java files"
               - **web-researcher**: Fetching URLs, researching docs, comparing technologies. Use for "what is Spring AI", "fetch this URL", "compare framework X and Y"
               - **general-purpose**: Complex tasks needing both code and web. Use for "evaluate this project's tech stack and suggest alternatives"
               - **dependency-analyzer**: Deep dependency analysis. Use for "analyze dependencies", "version conflicts", "outdated libraries", "security vulnerabilities in deps"
            2. Synthesize sub-agent results into a coherent response

            **When to delegate:**
            - Codebase exploration → codebase-explorer
            - Web/documentation research → web-researcher
            - Combined analysis → general-purpose
            - Dependency analysis → dependency-analyzer
            - Simple single-step tasks → use direct tools (glob_search, grep_search, web_fetch) yourself

            **Output:** Provide clear, structured findings; cite sources (file paths, URLs); include actionable recommendations when appropriate.
            """;

    private static final String DEPENDENCY_ANALYZER_SYSTEM_PROMPT =
            """
            You are a dependency analysis specialist. Your job is to analyze project dependencies.

            **Use glob_search and grep_search to:**
            - Find pom.xml, build.gradle, package.json, etc.
            - Extract dependency declarations
            - Identify version conflicts, outdated libraries, or security concerns
            - Map dependency tree and transitive dependencies

            **Output format:**
            - List dependencies by category (direct, transitive, optional)
            - Note version conflicts or duplicates
            - Flag outdated or deprecated versions
            - Provide clear, actionable recommendations
            """;

    @Bean
    public Model dashScopeChatModel() {
        String key = System.getenv("AI_DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(key).modelName("qwen-plus").build();
    }

    @Bean
    public SubagentTools subagentTools(
            Model model, @Value("${subagent.workspace-path:${user.dir}}") String workspacePathStr)
            throws Exception {

        Path workspacePath = Paths.get(workspacePathStr).toAbsolutePath().normalize();
        GlobSearchTool globSearch = GlobSearchTool.create(workspacePath);
        GrepSearchTool grepSearch = GrepSearchTool.create(workspacePath);
        WebFetchTool webFetch = WebFetchTool.create();

        Map<String, Object> defaultToolsByName =
                Map.of(
                        "glob_search", globSearch,
                        "grep_search", grepSearch,
                        "web_fetch", webFetch);

        // dependency-analyzer (only programmatic sub-agent)
        Toolkit depToolkit = new Toolkit();
        depToolkit.registerTool(globSearch);
        depToolkit.registerTool(grepSearch);
        ReActAgent dependencyAnalyzerReAct =
                ReActAgent.builder()
                        .name("dependency-analyzer")
                        .description(
                                "Analyzes project dependencies (pom.xml, package.json, etc.). Use"
                                        + " for version conflicts, outdated libs, security"
                                        + " vulnerabilities.")
                        .model(model)
                        .sysPrompt(DEPENDENCY_ANALYZER_SYSTEM_PROMPT)
                        .toolkit(depToolkit)
                        .memory(new InMemoryMemory())
                        .build();

        // Task tools: load codebase-explorer, web-researcher, general-purpose from
        // classpath:agents/*.md
        Resource[] agentResources =
                new PathMatchingResourcePatternResolver().getResources("classpath:agents/*.md");
        TaskToolsBuilder builder =
                TaskToolsBuilder.builder()
                        .model(model)
                        .defaultToolsByName(defaultToolsByName)
                        .subAgent("dependency-analyzer", dependencyAnalyzerReAct)
                        .taskRepository(
                                new io.agentscope.examples.subagent.tools.task
                                        .DefaultTaskRepository());
        for (Resource res : agentResources) {
            builder.addAgentResource(res);
        }
        TaskToolsBuilder.TaskToolsResult taskToolsResult = builder.build();

        // Orchestrator toolkit: default tools + Task + TaskOutput (no subAgent registration)
        Toolkit orchestratorToolkit = new Toolkit();
        orchestratorToolkit.registerTool(globSearch);
        orchestratorToolkit.registerTool(grepSearch);
        orchestratorToolkit.registerTool(webFetch);
        orchestratorToolkit.registerTool(taskToolsResult.taskTool());
        orchestratorToolkit.registerTool(taskToolsResult.taskOutputTool());

        ReActAgent.Builder orchestratorBuilder =
                ReActAgent.builder()
                        .name("tech-due-diligence-assistant")
                        .description(
                                "Orchestrates technical due diligence by delegating to"
                                        + " codebase-explorer, web-researcher, general-purpose, and"
                                        + " dependency-analyzer sub-agents")
                        .model(model)
                        .sysPrompt(ORCHESTRATOR_SYSTEM_PROMPT)
                        .toolkit(orchestratorToolkit)
                        .memory(new InMemoryMemory());

        return new SubagentTools(
                List.of(globSearch, grepSearch, webFetch),
                dependencyAnalyzerReAct,
                orchestratorBuilder);
    }

    @Bean("dependencyAnalyzerAgent")
    public AgentScopeAgent dependencyAnalyzerAgent(
            Model model, @Value("${subagent.workspace-path:${user.dir}}") String workspacePathStr) {
        Path workspacePath = Paths.get(workspacePathStr).toAbsolutePath().normalize();
        Toolkit t = new Toolkit();
        t.registerTool(GlobSearchTool.create(workspacePath));
        t.registerTool(GrepSearchTool.create(workspacePath));
        ReActAgent.Builder b =
                ReActAgent.builder()
                        .name("dependency-analyzer")
                        .description(
                                "Analyzes project dependencies (pom.xml, package.json, etc.). Use"
                                        + " for version conflicts, outdated libs, security"
                                        + " vulnerabilities.")
                        .model(model)
                        .sysPrompt(DEPENDENCY_ANALYZER_SYSTEM_PROMPT)
                        .toolkit(t)
                        .memory(new InMemoryMemory());
        return AgentScopeAgent.fromBuilder(b)
                .name("dependency-analyzer")
                .description(
                        "Analyzes project dependencies (pom.xml, package.json, etc.). Use for"
                                + " version conflicts, outdated libs, security vulnerabilities.")
                .build();
    }

    @Bean("orchestratorAgent")
    public AgentScopeAgent orchestratorAgent(SubagentTools subagentTools) {
        return AgentScopeAgent.fromBuilder(subagentTools.orchestratorBuilder())
                .name("tech-due-diligence-assistant")
                .description(
                        "Orchestrates technical due diligence by delegating to codebase-explorer,"
                                + " web-researcher, general-purpose, and dependency-analyzer"
                                + " sub-agents")
                .instruction("Process the following user request: {input}.")
                .build();
    }

    @Bean
    public CompiledGraph orchestratorGraph(AgentScopeAgent orchestratorAgent)
            throws GraphStateException {
        StateGraph graph =
                new StateGraph(
                        "subagent_orchestrator",
                        () -> {
                            Map<String, KeyStrategy> strategies = new HashMap<>();
                            strategies.put("messages", new AppendStrategy(false));
                            strategies.put("input", new ReplaceStrategy());
                            return strategies;
                        });
        graph.addNode("orchestrator", orchestratorAgent.asNode());
        graph.addEdge(START, "orchestrator");
        graph.addEdge("orchestrator", END);
        return graph.compile();
    }

    @Bean
    public OrchestratorService orchestratorService(CompiledGraph orchestratorGraph) {
        return new OrchestratorService(orchestratorGraph);
    }
}
