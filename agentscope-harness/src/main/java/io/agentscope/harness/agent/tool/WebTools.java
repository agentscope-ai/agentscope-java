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
package io.agentscope.harness.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Builtin web tools ({@code web_fetch}, {@code web_search}) for Managed Agents / Harness.
 *
 * <p>{@code web_search} uses the Tavily API when {@code TAVILY_API_KEY} (or vault-injected env) is
 * present; otherwise returns a clear configuration error.
 */
public final class WebTools {

    private WebTools() {}

    public static final class WebFetchTool {
        private final HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        @Tool(
                name = "web_fetch",
                readOnly = true,
                description =
                        "Fetch content from an HTTP(S) URL and return a truncated text preview.")
        public String webFetch(
                @ToolParam(name = "url", description = "HTTP or HTTPS URL to fetch") String url,
                @ToolParam(
                                name = "max_chars",
                                description = "Max characters to return (default 20000)",
                                required = false)
                        Integer maxChars) {
            if (url == null || url.isBlank()) {
                return "Error: url is required";
            }
            String trimmed = url.strip();
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return "Error: only http/https URLs are allowed";
            }
            int limit = maxChars != null && maxChars > 0 ? Math.min(maxChars, 100_000) : 20_000;
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(trimmed))
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "AgentScope-Harness-WebFetch/1.0")
                                .GET()
                                .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();
                if (body.length() > limit) {
                    body = body.substring(0, limit) + "\n...[truncated]";
                }
                return "status=" + response.statusCode() + "\n\n" + body;
            } catch (Exception e) {
                return "Error: web_fetch failed: " + e.getMessage();
            }
        }
    }

    public static final class WebSearchTool {
        private static final String TAVILY_API = "https://api.tavily.com/search";
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        @Tool(
                name = "web_search",
                readOnly = true,
                description =
                        "Search the web for information. Requires TAVILY_API_KEY. Returns titles,"
                                + " URLs and snippets.")
        public String webSearch(
                @ToolParam(name = "query", description = "Search query") String query,
                @ToolParam(
                                name = "max_results",
                                description = "Maximum results (default 5)",
                                required = false)
                        Integer maxResults) {
            if (query == null || query.isBlank()) {
                return "Error: query is required";
            }
            String apiKey = System.getenv("TAVILY_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                return "Error: TAVILY_API_KEY is not set. Configure the key via Environment vault"
                        + " credentials or process env to enable web_search.";
            }
            int limit = maxResults != null && maxResults > 0 ? Math.min(maxResults, 10) : 5;
            String body =
                    "{\"query\":"
                            + mapper.valueToTree(query)
                            + ",\"max_results\":"
                            + limit
                            + ",\"search_depth\":\"basic\"}";
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(TAVILY_API))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + apiKey)
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return "Error: Tavily API returned " + response.statusCode();
                }
                JsonNode root = mapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) {
                    return "No results.";
                }
                StringBuilder sb = new StringBuilder();
                int i = 1;
                for (JsonNode r : results) {
                    sb.append(i++)
                            .append(". ")
                            .append(r.path("title").asText(""))
                            .append("\n   ")
                            .append(r.path("url").asText(""))
                            .append("\n   ")
                            .append(r.path("content").asText(""))
                            .append("\n\n");
                }
                return sb.toString().strip();
            } catch (Exception e) {
                return "Error: web_search failed: " + e.getMessage();
            }
        }
    }
}
