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
package io.agentscope.extensions.higress;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HigressMcpClientBuilderTest {

    @Test
    void testCreate_WithValidName() {
        HigressMcpClientBuilder builder = HigressMcpClientBuilder.create("test-client");
        assertNotNull(builder);
    }

    @Test
    void testCreate_WithNullName() {
        assertThrows(IllegalArgumentException.class, () -> HigressMcpClientBuilder.create(null));
    }

    @Test
    void testCreate_WithEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> HigressMcpClientBuilder.create(""));
    }

    @Test
    void testCreate_WithWhitespaceName() {
        assertThrows(IllegalArgumentException.class, () -> HigressMcpClientBuilder.create("   "));
    }

    @Test
    void testSseEndpoint() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/mcp-servers/union-tools-search/sse");
        assertNotNull(builder);
    }

    @Test
    void testStreamableHttpEndpoint() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .streamableHttpEndpoint("http://gateway/mcp-servers/union-tools-search");
        assertNotNull(builder);
    }

    @Test
    void testHeader() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .header("Authorization", "Bearer token123")
                        .header("X-Custom-Header", "custom-value");
        assertNotNull(builder);
    }

    @Test
    void testHeaders_MultipleHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");
        headers.put("X-API-Key", "api-key-456");

        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .headers(headers);
        assertNotNull(builder);
    }

    @Test
    void testHeaders_WithNullHeaders() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .headers(null);
        assertNotNull(builder);
    }

    @Test
    void testQueryParam() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .queryParam("token", "abc123")
                        .queryParam("env", "prod");
        assertNotNull(builder);
    }

    @Test
    void testQueryParams_MultipleParams() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("token", "abc123");
        queryParams.put("env", "prod");

        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .queryParams(queryParams);
        assertNotNull(builder);
    }

    @Test
    void testQueryParams_WithNullParams() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .queryParams(null);
        assertNotNull(builder);
    }

    @Test
    void testTimeout() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").timeout(Duration.ofSeconds(90));
        assertNotNull(builder);
    }

    @Test
    void testInitializationTimeout() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .initializationTimeout(Duration.ofSeconds(45));
        assertNotNull(builder);
    }

    @Test
    void testToolSearch_WithQuery() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("查询天气");
        assertNotNull(builder);
    }

    @Test
    void testToolSearch_WithQueryAndTopK() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("查询天气", 5);
        assertNotNull(builder);
    }

    @Test
    void testToolSearch_WithInvalidTopK_Zero() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HigressMcpClientBuilder.create("test-client")
                                .sseEndpoint("http://gateway/sse")
                                .toolSearch("查询天气", 0));
    }

    @Test
    void testToolSearch_WithInvalidTopK_Negative() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HigressMcpClientBuilder.create("test-client")
                                .sseEndpoint("http://gateway/sse")
                                .toolSearch("查询天气", -1));
    }

    @Test
    void testFluentApi_ChainedCalls() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .header("Authorization", "Bearer token")
                        .timeout(Duration.ofSeconds(60))
                        .initializationTimeout(Duration.ofSeconds(30))
                        .toolSearch("查询天气", 5);
        assertNotNull(builder);
    }

    @Test
    void testBuildSync_WithoutEndpoint() {
        HigressMcpClientBuilder builder = HigressMcpClientBuilder.create("test-client");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::buildSync);
        assertTrue(exception.getMessage().contains("Endpoint must be configured"));
    }

    @Test
    void testBuildSync_WithEmptyEndpoint() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").sseEndpoint("");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::buildSync);
        assertTrue(exception.getMessage().contains("Endpoint must be configured"));
    }

    @Test
    void testBuildSync_WithWhitespaceEndpoint() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").sseEndpoint("   ");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::buildSync);
        assertTrue(exception.getMessage().contains("Endpoint must be configured"));
    }

    @Test
    void testBuildSync_WithToolSearchButNoQuery() {
        // This test simulates enabling tool search without a query
        // by using reflection or building with toolSearch(null)
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch(null);

        Exception exception = assertThrows(IllegalArgumentException.class, builder::buildSync);
        assertTrue(exception.getMessage().contains("Query is required for tool search"));
    }

    @Test
    void testBuildSync_WithToolSearchAndEmptyQuery() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::buildSync);
        assertTrue(exception.getMessage().contains("Query is required for tool search"));
    }

    @Test
    void testBuildSync_WithToolSearchAndWhitespaceQuery() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("   ");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::buildSync);
        assertTrue(exception.getMessage().contains("Query is required for tool search"));
    }
}
