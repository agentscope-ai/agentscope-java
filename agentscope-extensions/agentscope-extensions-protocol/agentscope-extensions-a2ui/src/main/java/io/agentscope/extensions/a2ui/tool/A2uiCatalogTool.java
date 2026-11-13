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
package io.agentscope.extensions.a2ui.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * On-demand catalog read tool: mounted on the render sub-agent only when {@code
 * A2uiConfig.catalogInPrompt()} is off, in which case the child pays one iteration to load the
 * same guide the SYSTEM prompt would otherwise carry (spec §2.3).
 */
public class A2uiCatalogTool implements AgentTool {

    private final A2uiCatalog catalog;

    public A2uiCatalogTool(A2uiCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getName() {
        return A2uiConstants.TOOL_CATALOG;
    }

    @Override
    public String getDescription() {
        return "Load the A2UI component catalog (component names, props, examples). Call this"
                + " once before your first a2ui_present submission.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.just(ToolResultBlock.text(catalog.guideText()));
    }
}
