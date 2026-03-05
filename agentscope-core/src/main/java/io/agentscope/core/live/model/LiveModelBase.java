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
package io.agentscope.core.live.model;

import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.formatter.LiveFormatter;
import io.agentscope.core.live.session.LiveSession;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for LiveModel implementations.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>✅ WebSocket connection establishment
 *   <li>✅ Session configuration sending
 *   <li>✅ LiveFormatter creation
 *   <li>❌ Reconnection logic (handled by LiveAgent)
 *   <li>❌ Tracing (handled by LiveAgent)
 * </ul>
 *
 * <p>Design principles:
 *
 * <ul>
 *   <li>Connection configuration (apiKey, baseUrl, etc.) is set via Builder and stored in Model
 *       instance
 *   <li>Session configuration (voice, instructions, etc.) is passed via connect(LiveConfig)
 *   <li>Tool definitions are passed via connect(LiveConfig, List&lt;ToolSchema&gt;)
 * </ul>
 */
public abstract class LiveModelBase implements LiveModel {

    protected final String modelName;
    protected final WebSocketClient webSocketClient;

    /**
     * Create LiveModelBase.
     *
     * @param modelName model name
     * @param webSocketClient WebSocket client
     */
    protected LiveModelBase(String modelName, WebSocketClient webSocketClient) {
        this.modelName = modelName;
        this.webSocketClient = webSocketClient;
    }

    @Override
    public final Mono<LiveSession> connect(LiveConfig config, List<ToolSchema> toolSchemas) {
        return doConnect(config, toolSchemas);
    }

    /**
     * Subclass implements specific connection logic.
     *
     * <p>Typical implementation flow:
     *
     * <ol>
     *   <li>Call {@link #buildWebSocketUrl()} to get connection URL
     *   <li>Call {@link #buildHeaders()} to get request headers
     *   <li>Use webSocketClient to create WebSocket connection
     *   <li>Call {@link #createFormatter()} to create message formatter
     *   <li>Pass toolSchemas to Formatter to build session configuration
     *   <li>Create LiveSessionImpl and return
     * </ol>
     *
     * @param config session configuration
     * @param toolSchemas tool definition list (can be null)
     * @return Mono of LiveSession
     */
    protected abstract Mono<LiveSession> doConnect(LiveConfig config, List<ToolSchema> toolSchemas);

    /**
     * Build WebSocket connection URL.
     *
     * <p>URL-related configuration (baseUrl) should be set in Builder and stored in subclass
     * fields.
     *
     * @return WebSocket URL
     */
    protected abstract String buildWebSocketUrl();

    /**
     * Build WebSocket request headers.
     *
     * @return header map
     */
    protected abstract Map<String, String> buildHeaders();

    /**
     * Create provider-specific LiveFormatter.
     *
     * @param <T> formatter message type (String for text protocols, byte[] for binary protocols)
     * @return LiveFormatter instance
     */
    protected abstract <T> LiveFormatter<T> createFormatter();

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Get the WebSocket client.
     *
     * @return WebSocket client instance
     */
    protected WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }
}
