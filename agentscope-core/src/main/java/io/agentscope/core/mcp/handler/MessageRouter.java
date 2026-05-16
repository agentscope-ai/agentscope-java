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

import io.agentscope.core.mcp.message.JsonRpcError;
import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import io.agentscope.core.mcp.transport.Transport;
import io.agentscope.core.mcp.transport.TransportException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Routes incoming messages to appropriate handlers.
 *
 * <p>Manages request/response correlation and error handling for MCP protocol messages.
 */
public class MessageRouter {

    private static final Logger logger = Logger.getLogger(MessageRouter.class.getName());

    private final Transport transport;
    private final HandlerRegistry handlerRegistry;

    public MessageRouter(Transport transport, HandlerRegistry handlerRegistry) {
        this.transport = transport;
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * Process an incoming message.
     *
     * @param message the incoming message
     * @throws TransportException if processing fails
     */
    public void handleMessage(JsonRpcMessage message) throws TransportException {
        String method = message.getMethod().orElse(null);

        if (method == null) {
            logger.warning("Received message without method");
            return;
        }

        Optional<MethodHandler> handler = handlerRegistry.get(method);
        if (!handler.isPresent()) {
            handleUnknownMethod(message, method);
            return;
        }

        JsonRpcResponse response = handler.get().handleMessage(message);
        if (response != null && message instanceof JsonRpcRequest) {
            // Only send response for requests, not notifications
            transport.send(response);
        }
    }

    /**
     * Start processing messages from the transport.
     *
     * <p>This method runs indefinitely until the transport is closed or an error occurs.
     *
     * @throws TransportException if an error occurs
     */
    public void processMessages() throws TransportException {
        while (transport.isConnected()) {
            try {
                JsonRpcMessage message = transport.receive();
                handleMessage(message);
            } catch (TransportException e) {
                if (transport.isConnected()) {
                    logger.warning("Error processing message: " + e.getMessage());
                }
            }
        }
    }

    private void handleUnknownMethod(JsonRpcMessage message, String method)
            throws TransportException {
        if (message instanceof JsonRpcRequest) {
            JsonRpcRequest request = (JsonRpcRequest) message;
            JsonRpcError error =
                    new JsonRpcError(
                            JsonRpcError.ErrorCode.METHOD_NOT_FOUND, "Method not found: " + method);
            JsonRpcResponse response = new JsonRpcResponse(request.getId().orElse(null), error);
            transport.send(response);
        }
    }

    /**
     * Register a method handler.
     *
     * @param method the method name
     * @param handler the handler
     */
    public void register(String method, MethodHandler handler) {
        handlerRegistry.register(method, handler);
    }
}
