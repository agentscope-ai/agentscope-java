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

package io.agentscope.core.mcp.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for JSON-RPC message classes.
 */
class JsonRpcMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new Jdk8Module());
    }

    @Test
    void testJsonRpcRequest() {
        JsonRpcRequest request = new JsonRpcRequest(1, "test/method", "params");
        assertEquals("params", request.getParams());
        assertTrue(request.getId().isPresent());
        assertTrue(request.getMethod().isPresent());
        assertEquals(1, request.getId().get());
        assertEquals("test/method", request.getMethod().get());
    }

    @Test
    void testJsonRpcResponse() {
        JsonRpcResponse response = new JsonRpcResponse(1, "result");
        assertEquals("result", response.getResult());
        assertNull(response.getError());
        assertFalse(response.isError());
        assertTrue(response.getId().isPresent());
        assertEquals(1, response.getId().get());
    }

    @Test
    void testJsonRpcResponseWithError() {
        JsonRpcError error =
                new JsonRpcError(JsonRpcError.ErrorCode.INVALID_PARAMS, "Invalid parameters");
        JsonRpcResponse response = new JsonRpcResponse(1, error);
        assertTrue(response.isError());
        assertEquals(error, response.getError());
        assertNull(response.getResult());
    }

    @Test
    void testJsonRpcNotification() {
        JsonRpcNotification notification = new JsonRpcNotification("test/notify", "data");
        assertEquals("data", notification.getParams());
        assertFalse(notification.getId().isPresent());
        assertTrue(notification.getMethod().isPresent());
        assertEquals("test/notify", notification.getMethod().get());
    }

    @Test
    void testJsonRpcErrorCodes() {
        assertEquals(-32700, JsonRpcError.ErrorCode.PARSE_ERROR);
        assertEquals(-32600, JsonRpcError.ErrorCode.INVALID_REQUEST);
        assertEquals(-32601, JsonRpcError.ErrorCode.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpcError.ErrorCode.INVALID_PARAMS);
        assertEquals(-32603, JsonRpcError.ErrorCode.INTERNAL_ERROR);
    }
}
