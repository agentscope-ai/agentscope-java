/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.mcp.tool;

import java.util.Map;

/**
 * Server-side tool abstraction. Implementations perform actual work (call external APIs, run local
 * logic) and return a result that will be serialized back to the client.
 */
public interface Tool {

    /**
     * Tool unique name (e.g., "context7.run").
     */
    String getName();

    /**
     * Human-readable description of the tool.
     */
    String getDescription();

    /**
     * JSON Schema (or a map-like representation) describing expected input for the tool.
     */
    Map<String, Object> getInputSchema();

    /**
     * Execute the tool with provided arguments.
     *
     * @param arguments arbitrary params (usually a Map)
     * @return result object suitable for inclusion in a MCP `CallToolResult` content block
     */
    Object execute(Object arguments) throws Exception;
}
