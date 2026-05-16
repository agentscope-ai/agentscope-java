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
