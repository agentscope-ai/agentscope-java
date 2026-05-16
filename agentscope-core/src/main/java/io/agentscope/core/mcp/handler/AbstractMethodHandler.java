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
                return new JsonRpcResponse(request.getId(), result);
            } else if (message instanceof JsonRpcNotification) {
                JsonRpcNotification notification = (JsonRpcNotification) message;
                handle(notification.getParams());
                return null; // Notifications don't require responses
            }
            throw new TransportException("Unknown message type: " + message.getClass());
        } catch (Exception e) {
            if (message instanceof JsonRpcRequest) {
                JsonRpcRequest request = (JsonRpcRequest) message;
                JsonRpcError error =
                        new JsonRpcError(
                                JsonRpcError.ErrorCode.INTERNAL_ERROR,
                                "Internal server error: " + e.getMessage(),
                                e.toString());
                return new JsonRpcResponse(request.getId(), error);
            }
            throw new TransportException("Error handling notification", e);
        }
    }
}
