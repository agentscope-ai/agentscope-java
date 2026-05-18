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
package io.agentscope.spring.boot.properties;

import io.agentscope.core.model.transport.websocket.WebSocketTransportConfig;
import io.agentscope.spring.boot.transport.TransportProviderType;
import java.time.Duration;

/**
 * Properties for WebSocket transport configuration that supports Spring Boot property binding.
 * This class acts as a bridge between Spring Boot's configuration properties and
 * the immutable WebSocketTransportConfig.
 */
public class WebSocketTransportProperties {

    private boolean enabled = true;

    private TransportProviderType.WebSocketType type = TransportProviderType.WebSocketType.JDK;

    private Duration connectTimeout = WebSocketTransportConfig.DEFAULT_CONNECT_TIMEOUT;

    private Duration readTimeout = WebSocketTransportConfig.DEFAULT_READ_TIMEOUT;

    private Duration writeTimeout = WebSocketTransportConfig.DEFAULT_WRITE_TIMEOUT;

    private Duration pingInterval = WebSocketTransportConfig.DEFAULT_PING_INTERVAL;

    private boolean ignoreSsl = false;

    private ProxyProperties proxy;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TransportProviderType.WebSocketType getType() {
        return type;
    }

    public void setType(TransportProviderType.WebSocketType type) {
        this.type = type;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public Duration getPingInterval() {
        return pingInterval;
    }

    public void setPingInterval(Duration pingInterval) {
        this.pingInterval = pingInterval;
    }

    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    public void setIgnoreSsl(boolean ignoreSsl) {
        this.ignoreSsl = ignoreSsl;
    }

    public ProxyProperties getProxy() {
        return proxy;
    }

    public void setProxy(ProxyProperties proxy) {
        this.proxy = proxy;
    }

    /**
     * Convert this properties object to WebSocketTransportConfig.
     *
     * @return a new WebSocketTransportConfig instance based on these properties
     */
    public WebSocketTransportConfig toTransportConfig() {
        return WebSocketTransportConfig.builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(writeTimeout)
                .pingInterval(pingInterval)
                .ignoreSsl(ignoreSsl)
                .proxy(proxy != null ? proxy.toProxyConfig() : null)
                .build();
    }
}
