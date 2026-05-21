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
package io.agentscope.harness.coding.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * GitHub API tool — plan option B (Java-side direct calls).
 *
 * <p>Replaces the Python-side {@code GH_TOKEN=dummy gh} pattern. The GitHub token is resolved
 * from the session context (via {@code RuntimeContext.extra("github_token")}) or falls back to
 * {@code GITHUB_TOKEN} / {@code GH_TOKEN} environment variables. The agent never sees the raw
 * token.
 *
 * <p>Mirrors open-swe's {@code http_request} + GitHub proxy approach, providing a dedicated tool
 * that automatically injects auth headers.
 */
public class GitHubApiTool {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private final HttpClient client;

    public GitHubApiTool() {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    @Tool(
            description =
                    "Call the GitHub REST API. Token is injected automatically — never ask the"
                            + " user for a token. Use for issues, PRs, comments, repo search,"
                            + " file content, checks, etc. Path should start with '/' (e.g."
                            + " '/repos/owner/repo/issues').")
    public String github_api_request(
            RuntimeContext runtimeContext,
            @ToolParam(name = "method", description = "HTTP method: GET, POST, PUT, PATCH, DELETE")
                    String method,
            @ToolParam(
                            name = "path",
                            description =
                                    "GitHub API path starting with '/' (e.g."
                                            + " /repos/owner/repo/issues)")
                    String path,
            @ToolParam(
                            name = "body",
                            description = "Optional JSON request body (for POST/PUT/PATCH)")
                    String body,
            @ToolParam(
                            name = "query_params",
                            description = "Optional query parameters as JSON object")
                    Map<String, String> queryParams) {
        String token = resolveToken(runtimeContext);
        if (token == null || token.isBlank()) {
            return "Error: No GitHub token available. Set GITHUB_TOKEN or configure GitHub App"
                    + " credentials.";
        }

        String normalizedPath = path != null ? path : "/";
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        String urlString = GITHUB_API_BASE + normalizedPath;
        if (queryParams != null && !queryParams.isEmpty()) {
            StringBuilder qs = new StringBuilder("?");
            queryParams.forEach(
                    (k, v) -> {
                        if (qs.length() > 1) qs.append("&");
                        qs.append(urlEncode(k)).append("=").append(urlEncode(v));
                    });
            urlString += qs;
        }

        try {
            HttpRequest.BodyPublisher publisher =
                    (body != null && !body.isEmpty())
                            ? HttpRequest.BodyPublishers.ofString(body)
                            : HttpRequest.BodyPublishers.noBody();

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(urlString))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("Content-Type", "application/json")
                            .header("User-Agent", "AgentScope-CodingAgent/1.0")
                            .timeout(Duration.ofSeconds(30))
                            .method(method != null ? method.toUpperCase() : "GET", publisher)
                            .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            return "Status: "
                    + response.statusCode()
                    + "\nBody:\n"
                    + truncate(response.body(), 16000);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error: " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------
    //  Token resolution
    // -----------------------------------------------------------------

    /**
     * Resolves the GitHub token from (in priority order):
     *
     * <ol>
     *   <li>Session extra {@code github_token} (injected by the webhook handler or dispatcher)
     *   <li>{@code GITHUB_TOKEN} environment variable
     *   <li>{@code GH_TOKEN} environment variable
     * </ol>
     */
    public static String resolveToken(RuntimeContext ctx) {
        if (ctx != null) {
            Map<String, Object> extra = ctx.getExtra();
            if (extra != null) {
                Object sessionToken = extra.get("github_token");
                if (sessionToken instanceof String t && !t.isBlank()) {
                    return t;
                }
            }
        }
        String envToken = System.getenv("GITHUB_TOKEN");
        if (envToken != null && !envToken.isBlank()) return envToken;
        return System.getenv("GH_TOKEN");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "\n... (truncated)" : s;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(
                    Objects.requireNonNullElse(s, ""), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
