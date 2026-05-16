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
            JsonRpcResponse response = new JsonRpcResponse(request.getId(), error);
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
