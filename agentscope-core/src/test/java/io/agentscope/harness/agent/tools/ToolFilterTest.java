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
package io.agentscope.harness.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolFilterTest {

    @Test
    void noFilter_whenAllowAndDenyEmpty() {
        Toolkit toolkit = makeToolkit();
        ToolsConfig cfg = new ToolsConfig();
        ToolFilter.apply(toolkit, cfg);
        assertEquals(threeNames(), toolkit.getToolNames());
    }

    @Test
    void noFilter_whenCfgNull() {
        Toolkit toolkit = makeToolkit();
        ToolFilter.apply(toolkit, null);
        assertEquals(threeNames(), toolkit.getToolNames());
    }

    @Test
    void allowList_keepsOnlyMatchingTools() {
        Toolkit toolkit = makeToolkit();
        ToolsConfig cfg = new ToolsConfig();
        cfg.setAllow(List.of("read_file", "list_files"));
        ToolFilter.apply(toolkit, cfg);
        Set<String> names = toolkit.getToolNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("list_files"));
        assertFalse(names.contains("execute"));
    }

    @Test
    void denyList_removesNamedTools() {
        Toolkit toolkit = makeToolkit();
        ToolsConfig cfg = new ToolsConfig();
        cfg.setDeny(List.of("execute"));
        ToolFilter.apply(toolkit, cfg);
        Set<String> names = toolkit.getToolNames();
        assertEquals(2, names.size());
        assertFalse(names.contains("execute"));
    }

    @Test
    void denyOverridesAllow_evenIfBothListed() {
        Toolkit toolkit = makeToolkit();
        ToolsConfig cfg = new ToolsConfig();
        cfg.setAllow(List.of("read_file", "execute"));
        cfg.setDeny(List.of("execute"));
        ToolFilter.apply(toolkit, cfg);
        Set<String> names = toolkit.getToolNames();
        assertEquals(1, names.size());
        assertTrue(names.contains("read_file"));
        assertFalse(names.contains("execute"));
    }

    @Test
    void unknownNames_areLoggedButTolerated() {
        Toolkit toolkit = makeToolkit();
        ToolsConfig cfg = new ToolsConfig();
        cfg.setAllow(List.of("read_file", "nonexistent_tool"));
        cfg.setDeny(List.of("also_nonexistent"));
        // Must not throw — typos in tools.json should never break the agent.
        ToolFilter.apply(toolkit, cfg);
        Set<String> names = toolkit.getToolNames();
        assertEquals(1, names.size());
        assertTrue(names.contains("read_file"));
    }

    @Test
    void emptyAllow_treatedAsUnset() {
        Toolkit toolkit = makeToolkit();
        ToolsConfig cfg = new ToolsConfig();
        cfg.setAllow(List.of());
        cfg.setDeny(List.of("execute"));
        ToolFilter.apply(toolkit, cfg);
        Set<String> names = toolkit.getToolNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("list_files"));
    }

    private static Toolkit makeToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TestTools());
        return toolkit;
    }

    private static Set<String> threeNames() {
        return Set.of("read_file", "list_files", "execute");
    }

    /** Three @Tool methods named after typical harness built-ins. */
    public static class TestTools {

        @Tool(name = "read_file", description = "stub")
        public String readFile(@ToolParam(name = "path", description = "p") String path) {
            return path;
        }

        @Tool(name = "list_files", description = "stub")
        public String listFiles(@ToolParam(name = "dir", description = "d") String dir) {
            return dir;
        }

        @Tool(name = "execute", description = "stub")
        public String execute(@ToolParam(name = "cmd", description = "c") String cmd) {
            return cmd;
        }
    }
}
