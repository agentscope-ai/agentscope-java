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
        capabilities.put("tools", new HashMap<>());
        capabilities.put("protocol", "mcp");

        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "agentscope-core");
        serverInfo.put("version", "0.1.0");

        return new InitializeResult(capabilities, "2.0", serverInfo);
    }
}
