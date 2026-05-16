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
