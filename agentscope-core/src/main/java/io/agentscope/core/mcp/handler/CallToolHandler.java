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

        // Normalize result into a content block list. Always wrap in a text block.
        Map<String, Object> block = new HashMap<>();
        block.put("type", "text");
        String text;
        if (result instanceof String) {
            text = (String) result;
        } else {
            text = objectMapper.writeValueAsString(result == null ? "" : result);
        }
        block.put("text", text);
        List<Object> content = new ArrayList<>();
        content.add(block);

        return new CallToolResult(content, Optional.empty());
    }
}
