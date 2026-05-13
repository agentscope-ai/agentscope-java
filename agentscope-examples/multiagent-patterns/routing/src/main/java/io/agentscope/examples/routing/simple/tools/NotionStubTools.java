/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.routing.simple.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Stub tools for the Notion vertical (docs, wikis) using AgentScope @Tool.
 * In production these would call real APIs. Register via {@link io.agentscope.core.tool.Toolkit#registerTool(Object)}.
 */
@Component
public class NotionStubTools {

    @Tool(name = "search_notion", description = "Search Notion workspace for documentation.")
    public String searchNotion(
            @ToolParam(name = "query", description = "Search query") String query) {
        return "Found documentation: 'API Authentication Guide' - covers OAuth2 flow, API keys, and"
                + " JWT tokens";
    }

    @Tool(name = "get_page", description = "Get a specific Notion page by ID.")
    public String getPage(
            @ToolParam(name = "pageId", description = "Notion page ID") String pageId) {
        return "Page content: Step-by-step authentication setup instructions";
    }
}
