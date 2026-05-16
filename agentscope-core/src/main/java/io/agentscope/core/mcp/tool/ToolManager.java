package io.agentscope.core.mcp.tool;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple registry for server-side tools.
 */
public class ToolManager {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> list() {
        return tools.values();
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    public void clear() {
        tools.clear();
    }
}
