/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Scoped registration of AG-UI frontend tools into an AgentScope toolkit.
 *
 * <p>Frontend tool definitions are injected for one run and removed afterwards. If an injected tool
 * temporarily replaced an existing tool with the same name, closing the scope restores the previous
 * tool when the slot is empty.
 */
final class ToolInjection {

    private static final ToolInjection EMPTY =
            new ToolInjection(null, Collections.emptyList(), Collections.emptyMap());

    private final Toolkit toolkit;
    private final List<SchemaOnlyTool> registeredTools;
    private final Map<String, AgentTool> previousTools;

    /**
     * Create a scoped tool injection.
     *
     * @param toolkit target toolkit, or {@code null} for an empty scope
     * @param registeredTools frontend schema-only tools registered for the run
     * @param previousTools tools displaced by the frontend tools
     */
    ToolInjection(
            Toolkit toolkit,
            List<SchemaOnlyTool> registeredTools,
            Map<String, AgentTool> previousTools) {
        this.toolkit = toolkit;
        this.registeredTools = registeredTools;
        this.previousTools = previousTools;
    }

    /**
     * Return a no-op injection scope.
     *
     * @return empty tool injection
     */
    static ToolInjection empty() {
        return EMPTY;
    }

    /**
     * Remove injected frontend tools and restore displaced toolkit entries.
     */
    void close() {
        if (toolkit == null) {
            return;
        }

        for (int i = registeredTools.size() - 1; i >= 0; i--) {
            SchemaOnlyTool tool = registeredTools.get(i);
            toolkit.removeToolIfSame(tool.getName(), tool);
        }

        for (Map.Entry<String, AgentTool> entry : previousTools.entrySet()) {
            if (toolkit.getTool(entry.getKey()) == null) {
                toolkit.registerAgentTool(entry.getValue());
            }
        }
    }
}
