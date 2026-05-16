package io.agentscope.core.mcp.handler;

import io.agentscope.core.mcp.schema.CallToolResult;
import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handler for `tools/call` requests. Looks up a registered server-side Tool and executes it.
 */
public class CallToolHandler extends AbstractMethodHandler {

    private final ToolManager toolManager;

    public CallToolHandler(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    @Override
    public String getMethod() {
        return "tools/call";
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object handle(Object params) throws Exception {
        if (!(params instanceof Map)) {
            throw new IllegalArgumentException("Invalid params for tools/call");
        }
        Map<String, Object> map = (Map<String, Object>) params;
        String name = (String) map.get("name");
        Object arguments = map.get("arguments");

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name is required");
        }

        Optional<Tool> toolOpt = toolManager.get(name);
        if (toolOpt.isEmpty()) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }

        Tool tool = toolOpt.get();
        Object result = tool.execute(arguments);

        // Normalize result into a content block list. If result is already a Map representing a
        // content block, pass it through; otherwise wrap into a text block.
        List<Object> content = new ArrayList<>();
        if (result instanceof Map) {
            content.add(result);
        } else {
            Map<String, Object> block = new HashMap<>();
            block.put("type", "text");
            block.put("text", result == null ? "" : result.toString());
            content.add(block);
        }

        return new CallToolResult(content, Optional.empty());
    }
}
