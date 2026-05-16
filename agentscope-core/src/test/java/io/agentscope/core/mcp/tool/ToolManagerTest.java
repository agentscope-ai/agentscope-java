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
