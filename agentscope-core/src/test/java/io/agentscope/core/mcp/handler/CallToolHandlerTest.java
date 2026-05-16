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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.mcp.schema.CallToolResult;
import io.agentscope.core.mcp.tool.Tool;
import io.agentscope.core.mcp.tool.ToolManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CallToolHandlerTest {

    @Test
    void callLocalTool() throws Exception {
        ToolManager mgr = new ToolManager();
        Tool fake =
                new Tool() {
                    @Override
                    public String getName() {
                        return "echo";
                    }

                    @Override
                    public String getDescription() {
                        return "Echo tool";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) {
                        return "OK:" + (arguments == null ? "" : arguments.toString());
                    }
                };

        mgr.register(fake);
        CallToolHandler handler = new CallToolHandler(mgr);

        Map<String, Object> params = new HashMap<>();
        params.put("name", "echo");
        params.put("arguments", Map.of("msg", "hello"));

        Object res = handler.handle(params);
        assertEquals(CallToolResult.class, res.getClass());
        CallToolResult ctr = (CallToolResult) res;
        List<Object> content = ctr.content();
        assertEquals(1, content.size());
        Object block = content.get(0);
        assertEquals(true, block.toString().contains("OK:"));
    }
}
