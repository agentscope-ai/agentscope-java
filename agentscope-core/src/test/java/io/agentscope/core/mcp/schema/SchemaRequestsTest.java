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

package io.agentscope.core.mcp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for MCP request schema classes and their builders. */
class SchemaRequestsTest {

    // --- InitializeRequest ---

    @Test
    void testInitializeRequestBuilder() {
        InitializeRequest req =
                InitializeRequest.builder()
                        .Id(1L)
                        .Jsonrpc("2.0")
                        .Method("initialize")
                        .Params(Map.of("protocolVersion", "2024-11-05"))
                        .build();

        assertEquals(1L, req.id());
        assertEquals("2.0", req.jsonrpc());
        assertEquals("initialize", req.method());
        assertNotNull(req.params());
    }

    @Test
    void testInitializeRequestRecord() {
        InitializeRequest req = new InitializeRequest(42, "2.0", "initialize", null);
        assertEquals(42, req.id());
        assertEquals("2.0", req.jsonrpc());
        assertEquals("initialize", req.method());
    }

    // --- InitializeRequestParams ---

    @Test
    void testInitializeRequestParamsBuilder() {
        InitializeRequestParams params =
                InitializeRequestParams.builder()
                        .ProtocolVersion("2024-11-05")
                        .ClientInfo(Map.of("name", "test-client", "version", "1.0"))
                        .Capabilities(Map.of("roots", Map.of()))
                        .build();

        assertEquals("2024-11-05", params.protocolVersion());
        assertNotNull(params.clientInfo());
        assertNotNull(params.capabilities());
    }

    @Test
    void testInitializeRequestParamsRecord() {
        InitializeRequestParams params = new InitializeRequestParams(null, Map.of(), "2024-11-05");
        assertEquals("2024-11-05", params.protocolVersion());
        assertNull(params.capabilities());
    }

    // --- CallToolRequest ---

    @Test
    void testCallToolRequestBuilder() {
        CallToolRequest req =
                CallToolRequest.builder()
                        .Id(5L)
                        .Jsonrpc("2.0")
                        .Method("tools/call")
                        .Params(Map.of("name", "myTool"))
                        .build();

        assertEquals(5L, req.id());
        assertEquals("2.0", req.jsonrpc());
        assertEquals("tools/call", req.method());
        assertNotNull(req.params());
    }

    @Test
    void testCallToolRequestRecord() {
        CallToolRequest req = new CallToolRequest(1, "2.0", "tools/call", null);
        assertEquals(1, req.id());
    }

    // --- CallToolRequestParams ---

    @Test
    void testCallToolRequestParamsBuilder() {
        CallToolRequestParams params =
                CallToolRequestParams.builder()
                        .Name("calculator")
                        .Arguments(Map.of("a", 1, "b", 2))
                        .build();

        assertEquals("calculator", params.name());
        assertTrue(params.arguments().isPresent());
        assertEquals(Map.of("a", 1, "b", 2), params.arguments().get());
    }

    @Test
    void testCallToolRequestParamsWithoutArguments() {
        CallToolRequestParams params = CallToolRequestParams.builder().Name("noArgs").build();

        assertEquals("noArgs", params.name());
        assertTrue(params.arguments().isEmpty());
    }

    @Test
    void testCallToolRequestParamsRecord() {
        CallToolRequestParams params =
                new CallToolRequestParams(Optional.of(Map.of("x", "y")), "test");
        assertEquals("test", params.name());
        assertTrue(params.arguments().isPresent());
    }

    // --- ListToolsRequest ---

    @Test
    void testListToolsRequestBuilder() {
        ListToolsRequest req =
                ListToolsRequest.builder().Id(10L).Jsonrpc("2.0").Method("tools/list").build();

        assertEquals(10L, req.id());
        assertEquals("2.0", req.jsonrpc());
        assertEquals("tools/list", req.method());
        assertTrue(req.params().isEmpty());
    }

    @Test
    void testListToolsRequestBuilderWithParams() {
        ListToolsRequest req =
                ListToolsRequest.builder()
                        .Id(11L)
                        .Jsonrpc("2.0")
                        .Method("tools/list")
                        .Params(Map.of("cursor", "abc"))
                        .build();

        assertEquals(11L, req.id());
        assertTrue(req.params().isPresent());
    }

    @Test
    void testListToolsRequestRecord() {
        ListToolsRequest req = new ListToolsRequest(1, "2.0", "tools/list", Optional.empty());
        assertEquals("tools/list", req.method());
        assertTrue(req.params().isEmpty());
    }

    // --- PaginatedRequestParams builder ---

    @Test
    void testPaginatedRequestParamsBuilder() {
        PaginatedRequestParams params = PaginatedRequestParams.builder().Cursor("page2").build();

        assertTrue(params.cursor().isPresent());
        assertEquals("page2", params.cursor().get());
    }

    @Test
    void testPaginatedRequestParamsBuilderNoCursor() {
        PaginatedRequestParams params = PaginatedRequestParams.builder().build();

        assertTrue(params.cursor().isEmpty());
    }
}
