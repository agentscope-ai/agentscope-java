/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.llm.interfacesweb.anthropic;

import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts Anthropic tools to AgentScope schema-only tools. */
public class AnthropicToolConverter {

    public List<ToolSchema> convert(List<AnthropicTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<ToolSchema> schemas = new ArrayList<>();
        for (AnthropicTool tool : tools) {
            if (tool != null && tool.getName() != null && !tool.getName().isBlank()) {
                schemas.add(
                        ToolSchema.builder()
                                .name(tool.getName())
                                .description(
                                        tool.getDescription() != null ? tool.getDescription() : "")
                                .parameters(
                                        tool.getInputSchema() != null
                                                ? tool.getInputSchema()
                                                : Map.of("type", "object"))
                                .build());
            }
        }
        return schemas;
    }
}
