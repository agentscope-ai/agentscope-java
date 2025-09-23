/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.tool;

import java.util.Map;

/**
 * Interface for agent tools that can be called by models.
 */
public interface AgentTool {

    /**
     * Get the name of the tool.
     */
    String getName();

    /**
     * Get the description of the tool.
     */
    String getDescription();

    /**
     * Get the parameters schema for this tool in JSON Schema format.
     */
    Map<String, Object> getParameters();

    /**
     * Execute the tool with the given input parameters.
     * @param input Input parameters as a map
     * @return Result as a map
     */
    ToolResponse call(Map<String, Object> input);

    ToolCallResultConverter getResultConverter();
}
