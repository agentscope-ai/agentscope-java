/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.higress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void testDelayInitialize() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").delayInitialize(true);
        assertNotNull(builder);
    }

    @Test
    void testAsyncClient() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").asyncClient(false);
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
                        .delayInitialize(true)
                        .asyncClient(true)
                        .toolSearch("查询天气", 5);
        assertNotNull(builder);
    }

    @Test
    void testBuild_WithoutEndpoint() {
        HigressMcpClientBuilder builder = HigressMcpClientBuilder.create("test-client");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("Endpoint must be configured"));
    }

    @Test
    void testBuild_WithEmptyEndpoint() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").sseEndpoint("");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("Endpoint must be configured"));
    }

    @Test
    void testBuild_WithWhitespaceEndpoint() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client").sseEndpoint("   ");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("Endpoint must be configured"));
    }

    @Test
    void testBuild_WithToolSearchButNoQuery() {
        // This test simulates enabling tool search without a query
        // by using reflection or building with toolSearch(null)
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch(null);

        Exception exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("Query is required for tool search"));
    }

    @Test
    void testBuild_WithToolSearchAndEmptyQuery() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("Query is required for tool search"));
    }

    @Test
    void testBuild_WithToolSearchAndWhitespaceQuery() {
        HigressMcpClientBuilder builder =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("   ");

        Exception exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("Query is required for tool search"));
    }

    @Test
    void testBuild_WithSseEndpoint_DelayInitialize() {
        HigressMcpClientWrapper wrapper =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .delayInitialize(true)
                        .build();

        assertNotNull(wrapper);
        assertEquals("test-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
        assertFalse(wrapper.isToolSearchEnabled());
    }

    @Test
    void testBuild_WithStreamableHttpEndpoint_DelayInitialize() {
        HigressMcpClientWrapper wrapper =
                HigressMcpClientBuilder.create("test-client")
                        .streamableHttpEndpoint("http://gateway/mcp")
                        .delayInitialize(true)
                        .build();

        assertNotNull(wrapper);
        assertEquals("test-client", wrapper.getName());
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testBuild_WithToolSearchEnabled_DelayInitialize() {
        HigressMcpClientWrapper wrapper =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .toolSearch("查询天气", 5)
                        .delayInitialize(true)
                        .build();

        assertNotNull(wrapper);
        assertTrue(wrapper.isToolSearchEnabled());
    }

    @Test
    void testBuild_WithSyncClient_DelayInitialize() {
        HigressMcpClientWrapper wrapper =
                HigressMcpClientBuilder.create("test-client")
                        .streamableHttpEndpoint("http://gateway/mcp")
                        .asyncClient(false)
                        .delayInitialize(true)
                        .build();

        assertNotNull(wrapper);
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testBuild_WithCustomTimeouts_DelayInitialize() {
        HigressMcpClientWrapper wrapper =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .timeout(Duration.ofSeconds(90))
                        .initializationTimeout(Duration.ofSeconds(45))
                        .delayInitialize(true)
                        .build();

        assertNotNull(wrapper);
        assertFalse(wrapper.isInitialized());
    }

    @Test
    void testBuild_WithHeaders_DelayInitialize() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("X-API-Key", "key123");

        HigressMcpClientWrapper wrapper =
                HigressMcpClientBuilder.create("test-client")
                        .sseEndpoint("http://gateway/sse")
                        .headers(headers)
                        .header("X-Custom", "value")
                        .delayInitialize(true)
                        .build();

        assertNotNull(wrapper);
    }
}
