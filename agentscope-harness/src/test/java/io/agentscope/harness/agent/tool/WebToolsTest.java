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

package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebToolsTest {
    private ToolResultBlock call(Object tool, String name, Map<String, Object> input) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return toolkit.getTool(name)
                .callAsync(
                        ToolCallParam.builder()
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("web-call")
                                                .name(name)
                                                .input(input)
                                                .build())
                                .input(input)
                                .build())
                .block();
    }

    @Test
    void invalidUrlIsAToolError() {
        ToolResultBlock result =
                call(new WebTools.WebFetchTool(), "web_fetch", Map.of("url", "file:///tmp/data"));
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
    }

    @Test
    void missingSearchCredentialIsAToolError() {
        assumeTrue(
                System.getenv("TAVILY_API_KEY") == null
                        || System.getenv("TAVILY_API_KEY").isBlank());
        ToolResultBlock result =
                call(new WebTools.WebSearchTool(), "web_search", Map.of("query", "industry"));
        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
    }

    @Test
    void defaultClientUsesJdkVersionNegotiation() {
        HttpClient client = WebTools.createDefaultHttpClient();
        // No version pinned: JDK default HTTP/2 preferred, automatic HTTP/1.1 fallback.
        assertEquals(HttpClient.Version.HTTP_2, client.version());
    }

    @Test
    void defaultClientFetchesFromHttp11Server() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "hello from http/1.1".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String out = new WebTools.WebFetchTool().webFetch(url, null);
            assertTrue(out.contains("hello from http/1.1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nullClientIsRejected() {
        assertThrows(NullPointerException.class, () -> new WebTools.WebFetchTool(null));
        assertThrows(NullPointerException.class, () -> new WebTools.WebSearchTool(null));
    }

    @Test
    void customHttpClientIsUsed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "custom client hit".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            HttpClient custom =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String out = new WebTools.WebFetchTool(custom).webFetch(url, null);
            assertTrue(out.contains("custom client hit"));
        } finally {
            server.stop(0);
        }
    }
}
