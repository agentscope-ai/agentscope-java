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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolManagerTest {

    private ToolManager toolManager;

    @BeforeEach
    void setUp() {
        toolManager = new ToolManager();
    }

    @Test
    void registerAndGetTool() {
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

        toolManager.register(t);
        assertTrue(toolManager.get("dummy.tool").isPresent());
        assertEquals(1, toolManager.list().size());
    }

    @Test
    void getToolByName() {
        Tool t =
                new Tool() {
                    @Override
                    public String getName() {
                        return "test.tool";
                    }

                    @Override
                    public String getDescription() {
                        return "A test tool";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of("type", "object");
                    }

                    @Override
                    public Object execute(Object arguments) throws Exception {
                        return Map.of("status", "success");
                    }
                };

        toolManager.register(t);
        assertTrue(toolManager.get("test.tool").isPresent());
        assertEquals("test.tool", toolManager.get("test.tool").get().getName());
    }

    @Test
    void getNonExistentTool() {
        assertFalse(toolManager.get("non.existent").isPresent());
    }

    @Test
    void listEmptyManager() {
        Collection<Tool> tools = toolManager.list();
        assertEquals(0, tools.size());
    }

    @Test
    void registerMultipleTools() {
        for (int i = 0; i < 5; i++) {
            final int index = i;
            Tool t =
                    new Tool() {
                        @Override
                        public String getName() {
                            return "tool." + index;
                        }

                        @Override
                        public String getDescription() {
                            return "Tool " + index;
                        }

                        @Override
                        public Map<String, Object> getInputSchema() {
                            return Map.of();
                        }

                        @Override
                        public Object execute(Object arguments) throws Exception {
                            return null;
                        }
                    };
            toolManager.register(t);
        }

        assertEquals(5, toolManager.list().size());
    }

    @Test
    void overwriteExistingTool() {
        Tool t1 =
                new Tool() {
                    @Override
                    public String getName() {
                        return "same.name";
                    }

                    @Override
                    public String getDescription() {
                        return "First version";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) throws Exception {
                        return "v1";
                    }
                };

        Tool t2 =
                new Tool() {
                    @Override
                    public String getName() {
                        return "same.name";
                    }

                    @Override
                    public String getDescription() {
                        return "Second version";
                    }

                    @Override
                    public Map<String, Object> getInputSchema() {
                        return Map.of();
                    }

                    @Override
                    public Object execute(Object arguments) throws Exception {
                        return "v2";
                    }
                };

        toolManager.register(t1);
        toolManager.register(t2);

        assertEquals(1, toolManager.list().size());
        assertEquals("Second version", toolManager.get("same.name").get().getDescription());
    }
}
