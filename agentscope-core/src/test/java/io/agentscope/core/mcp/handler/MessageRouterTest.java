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

package io.agentscope.core.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.mcp.message.JsonRpcError;
import io.agentscope.core.mcp.message.JsonRpcNotification;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import io.agentscope.core.mcp.transport.Transport;
import io.agentscope.core.mcp.transport.TransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for MessageRouter and handler integration.
 */
class MessageRouterTest {

    @Mock private Transport mockTransport;

    private HandlerRegistry registry;
    private MessageRouter router;
    private TestHandler testHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new HandlerRegistry();
        router = new MessageRouter(mockTransport, registry);
        testHandler = new TestHandler();
        registry.register("test/echo", testHandler);
    }

    @Test
    void testHandleRequest() throws TransportException {
        JsonRpcRequest request = new JsonRpcRequest(1, "test/echo", "hello");
        router.handleMessage(request);

        verify(mockTransport, times(1)).send(any(JsonRpcResponse.class));
    }

    @Test
    void testHandleNotification() throws TransportException {
        JsonRpcNotification notification = new JsonRpcNotification("test/echo", "hello");
        router.handleMessage(notification);

        // Notifications shouldn't trigger a response send
        verify(mockTransport, never()).send(any());
    }

    @Test
    void testHandleUnknownMethod() throws TransportException {
        JsonRpcRequest request = new JsonRpcRequest(1, "unknown/method", null);
        router.handleMessage(request);

        // Should send an error response
        verify(mockTransport, times(1))
                .send(
                        argThat(
                                msg -> {
                                    if (msg instanceof JsonRpcResponse) {
                                        JsonRpcResponse response = (JsonRpcResponse) msg;
                                        return response.isError()
                                                && response.getError().getCode()
                                                        == JsonRpcError.ErrorCode.METHOD_NOT_FOUND;
                                    }
                                    return false;
                                }));
    }

    @Test
    void testRegisterMethod() {
        assertFalse(registry.has("new/method"));
        router.register("new/method", testHandler);
        assertTrue(registry.has("new/method"));
    }

    @Test
    void testHandlerError() throws TransportException {
        TestErrorHandler errorHandler = new TestErrorHandler();
        registry.register("test/error", errorHandler);

        JsonRpcRequest request = new JsonRpcRequest(2, "test/error", null);
        router.handleMessage(request);

        verify(mockTransport, times(1))
                .send(
                        argThat(
                                msg -> {
                                    if (msg instanceof JsonRpcResponse) {
                                        JsonRpcResponse response = (JsonRpcResponse) msg;
                                        return response.isError()
                                                && response.getError().getCode()
                                                        == JsonRpcError.ErrorCode.INTERNAL_ERROR;
                                    }
                                    return false;
                                }));
    }

    private static class TestHandler extends AbstractMethodHandler {

        @Override
        public String getMethod() {
            return "test/echo";
        }

        @Override
        public Object handle(Object params) {
            return params;
        }
    }

    private static class TestErrorHandler extends AbstractMethodHandler {

        @Override
        public String getMethod() {
            return "test/error";
        }

        @Override
        public Object handle(Object params) throws Exception {
            throw new RuntimeException("Test error");
        }
    }
}
