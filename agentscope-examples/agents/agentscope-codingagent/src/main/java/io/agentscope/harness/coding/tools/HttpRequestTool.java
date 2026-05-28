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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Makes arbitrary HTTP requests with configurable method, headers, and body.
 */
public class HttpRequestTool {

    private final HttpClient client;

    public HttpRequestTool() {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    @Tool(
            description =
                    "Make an HTTP request (GET, POST, PUT, DELETE, PATCH) to any URL. Use for API"
                            + " calls with custom methods, headers, or request bodies. Do NOT use"
                            + " for GitHub — use github_api_request instead.")
    public String http_request(
            @ToolParam(name = "method", description = "HTTP method: GET, POST, PUT, DELETE, PATCH")
                    String method,
            @ToolParam(name = "url", description = "Full URL to request") String url,
            @ToolParam(name = "headers", description = "Optional request headers as JSON object")
                    Map<String, String> headers,
            @ToolParam(name = "body", description = "Optional request body (string)") String body) {
        try {
            HttpRequest.Builder reqBuilder =
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60));

            if (headers != null) {
                headers.forEach(reqBuilder::header);
            }

            HttpRequest.BodyPublisher publisher =
                    (body != null && !body.isEmpty())
                            ? HttpRequest.BodyPublishers.ofString(body)
                            : HttpRequest.BodyPublishers.noBody();

            HttpRequest request =
                    reqBuilder
                            .method(method != null ? method.toUpperCase() : "GET", publisher)
                            .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            return "Status: "
                    + response.statusCode()
                    + "\nHeaders: "
                    + response.headers().map()
                    + "\nBody:\n"
                    + truncate(response.body(), 16000);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error: " + e.getMessage();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "\n... (truncated)" : s;
    }
}
