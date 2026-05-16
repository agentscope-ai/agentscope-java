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
