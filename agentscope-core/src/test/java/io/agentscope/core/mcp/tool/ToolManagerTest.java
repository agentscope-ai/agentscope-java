package io.agentscope.core.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolManagerTest {

    @Test
    void registerAndGetTool() {
        ToolManager mgr = new ToolManager();
        Tool t =
                new Tool() {
                    @Override
                    public String getName() {
                        return "dummy.tool";
                    }

                    @Override
                    public String getDescription() {
                        return "A dummy tool";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) throws Exception {
                        return "result";
                    }
                };

        mgr.register(t);
        assertTrue(mgr.get("dummy.tool").isPresent());
        assertEquals(1, mgr.list().size());
    }
}
