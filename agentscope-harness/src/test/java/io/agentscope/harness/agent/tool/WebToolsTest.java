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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void strayPercentSignsAreSentAsLiteralPercentSigns() throws Exception {
        // Services such as wttr.in use literal placeholders, e.g. format=%l:+%c
        HttpServer server = echoTargetServer();
        try {
            String url =
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/Xiamen?lang=zh&format=%l:+%c+%t+%f+%w+%h+%p";
            String out =
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, null),
                            ToolResultState.SUCCESS);
            assertEquals(
                    "status=200\n\n/Xiamen?lang=zh&format=%25l:+%25c+%25t+%25f+%25w+%25h+%25p",
                    out);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        // Valid escapes, separators and a literal plus reach the server exactly as given.
        "/a%2Fb/%2fc?q=%25&sign=a+b&tz=%2F, /a%2Fb/%2fc?q=%25&sign=a+b&tz=%2F",
        // Truncated, non-hex and trailing percent signs are encoded in path and query alike.
        "/%2/%GG/%, /%252/%25GG/%25",
        "/x?q=%2&r=%GG&s=%, /x?q=%252&r=%25GG&s=%25",
        // Stray and valid percent signs side by side are decided one by one.
        "/a%2Fb/%l?x=%25&y=%2, /a%2Fb/%25l?x=%25&y=%252",
        // The fragment is left as given and, as for any URL, is not sent to the server.
        "/a%2Fb/%l?q=%25&y=%2#%25l, /a%2Fb/%25l?q=%25&y=%252"
    })
    void percentSignsAreEncodedOnlyWhenNotPartOfAnEscape(String target, String expectedRawTarget)
            throws Exception {
        HttpServer server = echoTargetServer();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + target;
            String out =
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, null),
                            ToolResultState.SUCCESS);
            assertEquals("status=200\n\n" + expectedRawTarget, out);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                // host, userinfo and fragment keep their input, so URI.create still rejects them
                "http://ho%st/%l?q=%2",
                "http://us%er:pw@127.0.0.1:{port}/%l?q=%2",
                "http://127.0.0.1:{port}/%l?q=%2#%l"
            })
    void percentSignsOutsidePathAndQueryAreStillRejected(String template) throws Exception {
        HttpServer server = echoTargetServer();
        try {
            String url =
                    template.replace("{port}", Integer.toString(server.getAddress().getPort()));
            String out =
                    assertText(
                            new WebTools.WebFetchTool().webFetch(url, null), ToolResultState.ERROR);
            assertTrue(out.contains("web_fetch failed: Malformed escape pair"), out);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void urlWithOtherIllegalCharactersIsStillAToolError() {
        ToolResultBlock result =
                new WebTools.WebFetchTool().webFetch("https://example.com/a b?x=%l", null);
        assertText(result, ToolResultState.ERROR);
    }

    // Replies with the raw request target so tests can assert what the client actually sent.
    private static HttpServer echoTargetServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    URI target = exchange.getRequestURI();
                    String query = target.getRawQuery();
                    byte[] body =
                            (query == null
                                            ? target.getRawPath()
                                            : target.getRawPath() + "?" + query)
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();
        return server;
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
}
