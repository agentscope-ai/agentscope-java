/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

import java.util.Collections;
import java.util.Map;

/**
 * WebSocket transport layer exception.
 *
 * <p>Wraps underlying transport errors with connection context to help diagnose issues. This
 * exception preserves the complete error chain while adding transport-specific context.
 *
 * <p>Context information includes:
 *
 * <ul>
 *   <li>WebSocket URL
 *   <li>Connection state (OPEN/CLOSED/CONNECTING)
 *   <li>Request headers (for authentication/configuration debugging)
 * </ul>
 */
public class WebSocketTransportException extends RuntimeException {

    private final String url;
    private final String connectionState;
    private final Map<String, String> headers;

    /**
     * Create a WebSocketTransportException.
     *
     * @param message Error message
     * @param cause Root cause exception
     * @param url WebSocket URL
     * @param connectionState Connection state (OPEN/CLOSED/CONNECTING)
     */
    public WebSocketTransportException(
            String message, Throwable cause, String url, String connectionState) {
        this(message, cause, url, connectionState, Collections.emptyMap());
    }

    /**
     * Create a WebSocketTransportException with headers.
     *
     * @param message Error message
     * @param cause Root cause exception
     * @param url WebSocket URL
     * @param connectionState Connection state
     * @param headers Request headers (for debugging)
     */
    public WebSocketTransportException(
            String message,
            Throwable cause,
            String url,
            String connectionState,
            Map<String, String> headers) {
        super(message, cause);
        this.url = url;
        this.connectionState = connectionState;
        this.headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
    }

    /**
     * Get the WebSocket URL.
     *
     * @return WebSocket URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Get the connection state.
     *
     * @return Connection state
     */
    public String getConnectionState() {
        return connectionState;
    }

    /**
     * Get the request headers.
     *
     * @return Request headers (immutable)
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    @Override
    public String getMessage() {
        return String.format("%s [url=%s, state=%s]", super.getMessage(), url, connectionState);
    }
}
