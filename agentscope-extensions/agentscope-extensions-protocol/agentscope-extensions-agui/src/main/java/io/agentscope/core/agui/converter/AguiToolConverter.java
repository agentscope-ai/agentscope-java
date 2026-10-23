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
package io.agentscope.core.agui.converter;

import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converter between AG-UI tools and AgentScope tool schemas.
 *
 * <p>This class handles the bidirectional conversion between the AG-UI protocol's
 * tool format and AgentScope's internal ToolSchema format.
 */
public class AguiToolConverter {

    /**
     * Convert an AG-UI tool to an AgentScope ToolSchema.
     *
     * @param aguiTool The AG-UI tool to convert
     * @return The converted ToolSchema
     */
    public ToolSchema toToolSchema(AguiTool aguiTool) {
        return ToolSchema.builder()
                .name(aguiTool.getName())
                .description(aguiTool.getDescription())
                .parameters(aguiTool.getParameters())
                .build();
    }

    /**
     * Convert an AgentScope ToolSchema to an AG-UI tool.
     *
     * @param schema The AgentScope ToolSchema to convert
     * @return The converted AG-UI tool
     */
    public AguiTool toAguiTool(ToolSchema schema) {
        return new AguiTool(schema.getName(), schema.getDescription(), schema.getParameters());
    }

    /**
     * Convert a list of AG-UI tools to AgentScope ToolSchemas.
     *
     * @param aguiTools The AG-UI tools to convert
     * @return The converted ToolSchemas
     */
    public List<ToolSchema> toToolSchemaList(List<AguiTool> aguiTools) {
        return aguiTools.stream().map(this::toToolSchema).collect(Collectors.toList());
    }

    /**
     * Convert a list of AgentScope ToolSchemas to AG-UI tools.
     *
     * @param schemas The AgentScope ToolSchemas to convert
     * @return The converted AG-UI tools
     */
    public List<AguiTool> toAguiToolList(List<ToolSchema> schemas) {
        return schemas.stream().map(this::toAguiTool).collect(Collectors.toList());
    }
}
