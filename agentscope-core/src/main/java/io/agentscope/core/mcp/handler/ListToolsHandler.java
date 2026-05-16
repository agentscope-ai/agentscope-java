package io.agentscope.core.mcp.handler;

import io.agentscope.core.mcp.schema.ListToolsResult;
import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handler for `tools/list` requests. Returns metadata about registered tools.
 */
public class ListToolsHandler extends AbstractMethodHandler {

    private final ToolManager toolManager;

    public ListToolsHandler(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    @Override
    public String getMethod() {
        return "tools/list";
    }

    @Override
    public Object handle(Object params) throws Exception {
        List<Object> out = new ArrayList<>();
        for (Tool t : toolManager.list()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("inputSchema", t.getInputSchema());
            out.add(m);
        }

        return new ListToolsResult(Optional.empty(), out);
    }
}
