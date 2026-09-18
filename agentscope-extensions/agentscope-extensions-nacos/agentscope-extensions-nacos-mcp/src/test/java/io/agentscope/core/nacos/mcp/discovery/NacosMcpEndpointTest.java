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
package io.agentscope.core.nacos.mcp.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NacosMcpEndpoint}.
 */
class NacosMcpEndpointTest {

    @Test
    @DisplayName("Should default path to slash when path is null or empty")
    void shouldDefaultPathToSlash() {
        assertEquals("/", new NacosMcpEndpoint("127.0.0.1", 8080, null, "http").getPath());
        assertEquals("/", new NacosMcpEndpoint("127.0.0.1", 8080, "", "http").getPath());
    }

    @Test
    @DisplayName("Should normalize the path to a single leading slash without a trailing one")
    void shouldNormalizePath() {
        assertEquals("/mcp", new NacosMcpEndpoint("127.0.0.1", 8080, "mcp", "http").getPath());
        assertEquals("/mcp", new NacosMcpEndpoint("127.0.0.1", 8080, "/mcp/", "http").getPath());
        assertEquals("/", new NacosMcpEndpoint("127.0.0.1", 8080, "/", "http").getPath());
        assertEquals(
                "http://127.0.0.1:8080/mcp",
                new NacosMcpEndpoint("127.0.0.1", 8080, "mcp", "http").url());
    }

    @Test
    @DisplayName("Should build key from scheme, address, port and path")
    void shouldBuildKey() {
        NacosMcpEndpoint endpoint = new NacosMcpEndpoint("127.0.0.1", 8080, "/mcp", "http");
        assertEquals("http@@127.0.0.1@@8080@@/mcp", endpoint.key());
    }

    @Test
    @DisplayName("Should treat a scheme change on the same address as a different endpoint")
    void shouldIncludeSchemeInKey() {
        NacosMcpEndpoint http = new NacosMcpEndpoint("127.0.0.1", 8080, "/mcp", "http");
        NacosMcpEndpoint https = new NacosMcpEndpoint("127.0.0.1", 8080, "/mcp", "https");

        assertNotEquals(http.key(), https.key());
    }

    @Test
    @DisplayName("Should build url from scheme, address, port and path")
    void shouldBuildUrl() {
        NacosMcpEndpoint endpoint = new NacosMcpEndpoint("127.0.0.1", 8080, "/mcp", "http");
        assertEquals("http://127.0.0.1:8080/mcp", endpoint.url());
        assertEquals("http://127.0.0.1:8080/mcp", endpoint.toString());
    }

    @Test
    @DisplayName("Should keep explicit http/https protocol when resolving from endpoint info")
    void shouldKeepExplicitProtocol() {
        McpEndpointInfo info = new McpEndpointInfo();
        info.setAddress("10.0.0.1");
        info.setPort(9000);
        info.setPath("/sse");
        info.setProtocol("https");

        NacosMcpEndpoint endpoint = NacosMcpEndpoint.from(info, "/mcp");

        assertEquals("https", endpoint.getScheme());
        // export path takes precedence over the endpoint info path
        assertEquals("/mcp", endpoint.getPath());
        assertEquals("https://10.0.0.1:9000/mcp", endpoint.url());
    }

    @Test
    @DisplayName("Should infer https scheme from port 443/8443 when protocol is not http(s)")
    void shouldInferHttpsFromPort() {
        McpEndpointInfo https443 = new McpEndpointInfo();
        https443.setAddress("host");
        https443.setPort(443);
        https443.setProtocol("mcp");
        assertEquals("https", NacosMcpEndpoint.from(https443, null).getScheme());

        McpEndpointInfo https8443 = new McpEndpointInfo();
        https8443.setAddress("host");
        https8443.setPort(8443);
        assertEquals("https", NacosMcpEndpoint.from(https8443, null).getScheme());
    }

    @Test
    @DisplayName("Should infer http scheme for non 443/8443 ports when protocol is not http(s)")
    void shouldInferHttpForOtherPorts() {
        McpEndpointInfo info = new McpEndpointInfo();
        info.setAddress("host");
        info.setPort(8080);
        info.setPath("/streamable");

        NacosMcpEndpoint endpoint = NacosMcpEndpoint.from(info, null);

        assertEquals("http", endpoint.getScheme());
        // falls back to the endpoint info path when export path is null
        assertEquals("/streamable", endpoint.getPath());
    }
}
