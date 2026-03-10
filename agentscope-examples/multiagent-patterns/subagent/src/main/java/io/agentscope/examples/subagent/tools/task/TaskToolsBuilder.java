/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.subagent.tools.task;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.CallableAgent;
import io.agentscope.core.model.Model;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Builds AgentScope Task and TaskOutput tools with sub-agents loaded from resources
 * and/or registered programmatically. Use {@link #addAgentResource(Resource)} for
 * classpath agents (e.g. {@code classpath:agents/*.md}) and {@link #subAgent(String, ReActAgent)}
 * for the single programmatic agent (e.g. dependency-analyzer). Call {@link #build()} to get
 * {@link TaskToolsResult} and register taskTool and taskOutputTool on the
 * orchestrator toolkit.
 */
public final class TaskToolsBuilder {

    private TaskRepository taskRepository = new DefaultTaskRepository();
    private final Map<String, CallableAgent> subAgents = new HashMap<>();
    private final List<Resource> agentResources = new ArrayList<>();
    private Model model;
    private Map<String, Object> defaultToolsByName = Map.of();

    private TaskToolsBuilder() {}

    public static TaskToolsBuilder builder() {
        return new TaskToolsBuilder();
    }

    /**
     * Set the task repository (required for background execution).
     */
    public TaskToolsBuilder taskRepository(TaskRepository taskRepository) {
        Assert.notNull(taskRepository, "taskRepository must not be null");
        this.taskRepository = taskRepository;
        return this;
    }

    /**
     * Set the Model used to create ReActAgents from agent spec resources.
     */
    public TaskToolsBuilder model(Model model) {
        this.model = model;
        return this;
    }

    /**
     * Set default tools by name for sub-agents built from specs. Keys must match
     * tool names (e.g. glob_search, grep_search, web_fetch); values are the tool instances.
     */
    public TaskToolsBuilder defaultToolsByName(Map<String, Object> defaultToolsByName) {
        this.defaultToolsByName =
                defaultToolsByName != null ? Map.copyOf(defaultToolsByName) : Map.of();
        return this;
    }

    /**
     * Add a single sub-agent (programmatic). Use for e.g. dependency-analyzer.
     */
    public TaskToolsBuilder subAgent(String type, ReActAgent agent) {
        Assert.hasText(type, "type must not be empty");
        Assert.notNull(agent, "agent must not be null");
        this.subAgents.put(type, agent);
        return this;
    }

    /**
     * Add a Spring Resource to load an agent spec from (e.g. classpath:agents/codebase-explorer.md).
     */
    public TaskToolsBuilder addAgentResource(Resource resource) {
        if (resource != null) {
            this.agentResources.add(resource);
        }
        return this;
    }

    /**
     * Build and return Task and TaskOutput tools. Resolves sub-agents from resources
     * (using {@link AgentSpecLoader} and {@link AgentSpecReActAgentFactory}) and merges
     * with any programmatic sub-agents.
     */
    public TaskToolsResult build() {
        Assert.notNull(taskRepository, "taskRepository must be provided");

        Map<String, CallableAgent> resolved = new HashMap<>(this.subAgents);
        loadFromResourcesAndMerge(resolved);

        Assert.notEmpty(
                resolved,
                "At least one sub-agent must be configured (via subAgent or addAgentResource)");

        TaskTool taskTool = new TaskTool(resolved, taskRepository);
        TaskOutputTool taskOutputTool = new TaskOutputTool(taskRepository);
        return new TaskToolsResult(taskTool, taskOutputTool);
    }

    private void loadFromResourcesAndMerge(Map<String, CallableAgent> into) {
        if (agentResources.isEmpty()) {
            return;
        }
        Assert.notNull(model, "model must be set when using addAgentResource");
        Assert.notEmpty(
                defaultToolsByName, "defaultToolsByName must be set when using addAgentResource");

        AgentSpecReActAgentFactory factory =
                new AgentSpecReActAgentFactory(model, defaultToolsByName);

        for (Resource resource : agentResources) {
            try {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                AgentSpec spec = AgentSpecLoader.loadFromResource(resource);
                if (spec != null && StringUtils.hasText(spec.name())) {
                    into.put(spec.name(), factory.create(spec));
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load agent spec from " + resource, e);
            }
        }
    }

    /**
     * Result of {@link TaskToolsBuilder#build()}: register {@link #taskTool()} and
     * {@link #taskOutputTool()} on the orchestrator toolkit.
     */
    public record TaskToolsResult(TaskTool taskTool, TaskOutputTool taskOutputTool) {}
}
