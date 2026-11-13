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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
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
            String out =
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, null),
                            ToolResultState.SUCCESS);
            assertTrue(out.contains("hello from http/1.1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void non2xxResponseIsAToolErrorAndKeepsStatusAndBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/missing",
                exchange -> {
                    byte[] body = "resource not found".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
            String output =
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, null), ToolResultState.ERROR);
            assertTrue(output.contains("status=404"));
            assertTrue(output.contains("resource not found"));
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
            String out =
                    assertText(
                            new WebTools.WebFetchTool(custom).webFetch(url, null),
                            ToolResultState.SUCCESS);
            assertTrue(out.contains("custom client hit"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonSuccessfulResponseIncludesBoundedBodyInError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String errorBody = "validation failed\n" + "x".repeat(3_000);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(422, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String output =
                    assertText(
                            call(new WebTools.WebFetchTool(), "web_fetch", Map.of("url", url)),
                            ToolResultState.ERROR);
            assertTrue(output.contains("status=422"));
            assertTrue(output.contains("validation failed"));
            assertTrue(output.contains("\n...[truncated]"));
            // The echoed body is server-controlled, so it is labelled rather than presented as
            // framework output.
            assertTrue(output.contains("response body (untrusted): "));
            assertTrue(output.length() < 2_100);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulResponseHonorsRequestedAndDefaultCharacterLimits() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String responseBody = "x".repeat(100_001);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            WebTools.WebFetchTool tool = new WebTools.WebFetchTool();

            assertEquals(
                    "status=200\n\nxxxxx\n...[truncated]",
                    assertText(tool.webFetch(url, 5), ToolResultState.SUCCESS));
            assertEquals(
                    "status=200\n\n" + "x".repeat(20_000) + "\n...[truncated]",
                    assertText(tool.webFetch(url, 0), ToolResultState.SUCCESS));
            assertEquals(
                    "status=200\n\n" + "x".repeat(100_000) + "\n...[truncated]",
                    assertText(tool.webFetch(url, 200_000), ToolResultState.SUCCESS));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectWithoutLocationRemainsAnErrorAndHonorsRequestedLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "😀ab".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(302, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertEquals(
                    "status=302\n\nresponse body (untrusted): 😀\n...[truncated]",
                    assertText(
                            call(
                                    new WebTools.WebFetchTool(),
                                    "web_fetch",
                                    Map.of("url", url, "max_chars", 1)),
                            ToolResultState.ERROR));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorWithoutBodyReportsStatusOnly() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertEquals(
                    "status=503",
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, null),
                            ToolResultState.ERROR));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncationCountsCharactersAndDoesNotSplitSurrogatePairs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "a😀b".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            WebTools.WebFetchTool tool = new WebTools.WebFetchTool();

            // The astral character counts as one, and the cut never lands inside the pair.
            assertEquals(
                    "status=200\n\na😀\n...[truncated]",
                    assertText(tool.webFetch(url, 2), ToolResultState.SUCCESS));
            assertEquals(
                    "status=200\n\na😀b",
                    assertText(tool.webFetch(url, 3), ToolResultState.SUCCESS));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oneCharacterLimitStillReturnsTheAstralCharacter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "😀bc".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            // A limit of one must not degrade to a marker with no content, and must not
            // return a lone surrogate either.
            assertEquals(
                    "status=200\n\n😀\n...[truncated]",
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, 1), ToolResultState.SUCCESS));
        } finally {
            server.stop(0);
        }
    }
}
