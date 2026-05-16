package io.agentscope.core.mcp.transport;

import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;

/**
 * Transport abstraction for MCP protocol communication.
 *
 * <p>Implementations must handle sending and receiving JSON-RPC messages over different
 * transport mechanisms (e.g., stdio, TCP, WebSocket, etc.).
 */
public interface Transport extends AutoCloseable {

    /**
     * Send a JSON-RPC message to the remote endpoint.
     *
     * @param message the message to send
     * @throws TransportException if sending fails
     */
    void send(JsonRpcMessage message) throws TransportException;

    /**
     * Receive a JSON-RPC message from the remote endpoint.
     *
     * @return the received message
     * @throws TransportException if receiving fails or connection is closed
     */
    JsonRpcMessage receive() throws TransportException;

    /**
     * Send a request and wait for the corresponding response.
     *
     * @param request the request to send
     * @return the response
     * @throws TransportException if communication fails
     */
    JsonRpcResponse request(JsonRpcRequest request) throws TransportException;

    /**
     * Check if the transport is still connected.
     *
     * @return true if connected, false otherwise
     */
    boolean isConnected();

    /**
     * Close the transport connection.
     *
     * @throws Exception if closing fails
     */
    @Override
    void close() throws Exception;
}
