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
package io.agentscope.core.tool.permission;

import java.util.Map;

/**
 * @deprecated Use {@link io.agentscope.core.tool.ToolBase} directly. The canonical home is the
 *     {@code io.agentscope.core.tool} package, alongside {@link io.agentscope.core.tool.AgentTool}
 *     and {@link io.agentscope.core.tool.Toolkit}. This subclass remains as a compatibility shim
 *     and will be removed in the next minor release.
 */
@Deprecated(forRemoval = true)
public abstract class ToolBase extends io.agentscope.core.tool.ToolBase {

    @SuppressWarnings("deprecation")
    protected ToolBase(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean isReadOnly,
            boolean isConcurrencySafe,
            boolean isMcp,
            String mcpName,
            boolean isExternalTool,
            boolean isStateInjected) {
        super(
                name,
                description,
                inputSchema,
                isReadOnly,
                isConcurrencySafe,
                isMcp,
                mcpName,
                isExternalTool,
                isStateInjected);
    }
}
