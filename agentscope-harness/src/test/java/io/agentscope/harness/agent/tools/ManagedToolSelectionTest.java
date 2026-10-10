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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ManagedToolSelectionTest {
    @Test
    void defaultDisabledDoesNotBecomeAllowAll() {
        ToolsConfig cfg = new ToolsConfig();
        cfg.setDefaultToolsEnabled(false);
        cfg.setAllow(List.of());
        Assertions.assertFalse(ToolFilter.isAllowed("execute", cfg));
        cfg.setAllow(List.of("read_file"));
        Assertions.assertTrue(ToolFilter.isAllowed("read_file", cfg));
        Assertions.assertFalse(ToolFilter.isAllowed("write_file", cfg));
    }

    @Test
    void mcpSelectionIsIndependentAndDenyWins() {
        McpServerConfig server = new McpServerConfig();
        server.setPrefixToolNames(true);
        server.setDefaultToolsEnabled(false);
        Assertions.assertFalse(McpServerRegistrar.isToolEnabled("search", server));
        server.setEnableTools(List.of("search", "delete"));
        server.setDisableTools(List.of("delete"));
        Assertions.assertTrue(McpServerRegistrar.isToolEnabled("search", server));
        Assertions.assertFalse(McpServerRegistrar.isToolEnabled("delete", server));
        ToolsConfig cfg = new ToolsConfig();
        cfg.setDefaultToolsEnabled(false);
        cfg.setMcpServers(Map.of("crm", server));
        Assertions.assertTrue(ToolFilter.isAllowed("crm__search", cfg));
        cfg.setStrictAllow(true);
        Assertions.assertFalse(ToolFilter.isAllowed("crm__search", cfg));
    }
}
