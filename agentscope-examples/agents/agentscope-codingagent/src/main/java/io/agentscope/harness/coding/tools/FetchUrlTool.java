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
import java.util.regex.Pattern;

/**
 * Fetches a URL and returns a simplified text representation (strips most HTML tags).
 */
public class FetchUrlTool {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_BLANK = Pattern.compile("[\r\n]{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \t]{2,}");

    private final HttpClient client;

    public FetchUrlTool() {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    @Tool(
            description =
                    "Fetch a URL and return its content as simplified text (HTML stripped to"
                            + " readable text). Use for web pages only. For APIs, use"
                            + " http_request instead.")
    public String fetch_url(@ToolParam(name = "url", description = "URL to fetch") String url) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", "AgentScope-CodingAgent/1.0 (compatible; bot)")
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: HTTP " + response.statusCode() + " for " + url;
            }

            String body = response.body();
            String text = simplifyHtml(body);
            return truncate(text, 20000);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error fetching URL: " + e.getMessage();
        }
    }

    private static String simplifyHtml(String html) {
        if (html == null) return "";
        String text = HTML_TAG.matcher(html).replaceAll(" ");
        text =
                text.replace("&nbsp;", " ")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"");
        text = MULTI_BLANK.matcher(text).replaceAll("\n\n");
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        return text.strip();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "\n... (truncated)" : s;
    }
}
