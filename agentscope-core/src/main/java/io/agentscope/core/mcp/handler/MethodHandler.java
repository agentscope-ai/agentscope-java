package io.agentscope.core.mcp.handler;

import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import io.agentscope.core.mcp.transport.TransportException;

/**
 * Handler for MCP method calls.
 *
 * <p>Implementations handle specific MCP methods and return responses.
 */
public interface MethodHandler {

    /**
     * Get the method name this handler handles.
     *
     * @return the method name (e.g., "tools/list")
     */
    String getMethod();

    /**
     * Handle a method call and return the result.
     *
     * @param params the method parameters
     * @return the method result (will be wrapped in a JSON-RPC response)
     * @throws Exception if the method execution fails
     */
    Object handle(Object params) throws Exception;

    /**
     * Handle a message and return a response.
     *
     * @param message the incoming message
     * @return the response, or null for notifications (no response needed)
     * @throws TransportException if handling fails
     */
    JsonRpcResponse handleMessage(JsonRpcMessage message) throws TransportException;
}
