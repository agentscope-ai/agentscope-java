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
package io.agentscope.examples.subagent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * AgentScope tool: fetch content from a URL and return as text.
 * Register via {@link io.agentscope.core.tool.Toolkit#registerTool(Object)}.
 */
public class WebFetchTool {

    @Tool(
            name = "web_fetch",
            description =
                    "Fetch content from a URL. Use for documentation, research, or comparing"
                            + " technologies. Returns raw text (e.g. HTML as text).")
    public String webFetch(
            @ToolParam(name = "url", description = "Full URL to fetch (e.g. https://example.com)")
                    String url,
            @ToolParam(
                            name = "prompt",
                            description = "Optional prompt describing what to extract or summarize",
                            required = false)
                    String prompt) {
        if (url == null || url.isBlank()) {
            return "Error: url is required.";
        }
        try {
            URI.create(url);
            URL u = new URL(url);
            try (InputStream in = u.openStream();
                    Scanner scanner =
                            new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                String content = scanner.hasNext() ? scanner.next() : "";
                if (content.length() > 15000) {
                    content = content.substring(0, 15000) + "\n...[truncated]";
                }
                return content;
            }
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }
    }

    public static WebFetchTool create() {
        return new WebFetchTool();
    }
}
