package io.agentscope.core.mcp.handler;

import io.agentscope.core.mcp.schema.InitializeResult;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.HashMap;
import java.util.Map;

/**
 * Handler for `initialize` requests (handshake).
 */
public class InitializeHandler extends AbstractMethodHandler {

    private final ToolManager toolManager;

    public InitializeHandler(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    @Override
    public String getMethod() {
        return "initialize";
    }

    @Override
    public Object handle(Object params) throws Exception {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", true);
        capabilities.put("protocol", "mcp");

        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "agentscope-core");
        serverInfo.put("version", "0.1.0");

        return new InitializeResult(capabilities, "2.0", serverInfo);
    }
}
