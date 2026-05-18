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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.mcp.message.JsonRpcError;
import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcNotification;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import io.agentscope.core.mcp.transport.TransportException;

/**
 * Abstract base class for method handlers.
 *
 * <p>Provides common logic for handling requests and notifications.
 */
public abstract class AbstractMethodHandler implements MethodHandler {

    protected ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public JsonRpcResponse handleMessage(JsonRpcMessage message) throws TransportException {
        try {
            if (message instanceof JsonRpcRequest) {
                JsonRpcRequest request = (JsonRpcRequest) message;
                Object result = handle(request.getParams());
                return new JsonRpcResponse(request.getId().orElse(null), result);
            } else if (message instanceof JsonRpcNotification) {
                JsonRpcNotification notification = (JsonRpcNotification) message;
                handle(notification.getParams());
                return null; // Notifications don't require responses
            }
            throw new TransportException("Unknown message type: " + message.getClass());
        } catch (TransportException te) {
            throw te; // Re-throw transport exceptions
        } catch (Exception e) {
            if (message instanceof JsonRpcRequest) {
                JsonRpcRequest request = (JsonRpcRequest) message;
                JsonRpcError error =
                        new JsonRpcError(
                                JsonRpcError.ErrorCode.INTERNAL_ERROR,
                                "Internal server error: " + e.getMessage(),
                                e.toString());
                return new JsonRpcResponse(request.getId().orElse(null), error);
            }
            throw new TransportException("Error handling notification", e);
        }
    }
}
