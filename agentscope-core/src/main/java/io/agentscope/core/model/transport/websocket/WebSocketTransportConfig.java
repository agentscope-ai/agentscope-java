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

import io.agentscope.core.model.transport.ProxyConfig;
import java.time.Duration;

/**
 * Configuration for WebSocket clients.
 *
 * <p>This class holds configuration options for WebSocket client behavior such as
 * timeouts, heartbeat intervals, proxy settings, and SSL options.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * WebSocketTransportConfig config = WebSocketTransportConfig.builder()
 *     .connectTimeout(Duration.ofSeconds(30))
 *     .pingInterval(Duration.ofSeconds(30))
 *     .proxy(ProxyConfig.http("proxy.example.com", 8080))
 *     .build();
 *
 * WebSocketTransport client = OkHttpWebSocketTransport.create(config);
 * }</pre>
 */
public class WebSocketTransportConfig {

    /** Default connect timeout: 30 seconds. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** Default read timeout: 0 (no timeout for WebSocket). */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ZERO;

    /** Default write timeout: 30 seconds. */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    /** Default ping interval: 30 seconds. */
    public static final Duration DEFAULT_PING_INTERVAL = Duration.ofSeconds(30);

    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final Duration pingInterval;
    private final ProxyConfig proxyConfig;
    private final boolean ignoreSsl;

    private WebSocketTransportConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.pingInterval = builder.pingInterval;
        this.proxyConfig = builder.proxyConfig;
        this.ignoreSsl = builder.ignoreSsl;
    }

    /**
     * Get the connect timeout.
     *
     * @return the connect timeout duration
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Get the read timeout.
     *
     * @return the read timeout duration
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * Get the write timeout.
     *
     * @return the write timeout duration
     */
    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    /**
     * Get the ping interval for heartbeat (OkHttp only).
     *
     * @return the ping interval duration
     */
    public Duration getPingInterval() {
        return pingInterval;
    }

    /**
     * Get the proxy configuration.
     *
     * @return the proxy configuration, or null if no proxy is configured
     */
    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    /**
     * Get whether SSL certificate verification should be ignored.
     *
     * <p><b>Warning:</b> Setting this to true disables SSL certificate verification,
     * which makes the connection vulnerable to man-in-the-middle attacks.
     * This should only be used for testing or with trusted self-signed certificates.
     *
     * @return true to ignore SSL certificate verification, false otherwise
     */
    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    /**
     * Create a new builder for WebSocketTransportConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a default configuration.
     *
     * @return a default WebSocketTransportConfig instance
     */
    public static WebSocketTransportConfig defaults() {
        return builder().build();
    }

    /**
     * Builder for WebSocketTransportConfig.
     */
    public static class Builder {
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private Duration pingInterval = DEFAULT_PING_INTERVAL;
        private ProxyConfig proxyConfig = null;
        private boolean ignoreSsl = false;

        /**
         * Set the connect timeout.
         *
         * @param connectTimeout the connect timeout duration
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Set the read timeout.
         *
         * @param readTimeout the read timeout duration
         * @return this builder
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Set the write timeout.
         *
         * @param writeTimeout the write timeout duration
         * @return this builder
         */
        public Builder writeTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
            return this;
        }

        /**
         * Set the ping interval for heartbeat (OkHttp only).
         *
         * @param pingInterval the ping interval duration
         * @return this builder
         */
        public Builder pingInterval(Duration pingInterval) {
            this.pingInterval = pingInterval;
            return this;
        }

        /**
         * Set the proxy configuration.
         *
         * <p>Supports HTTP and SOCKS proxies. See {@link ProxyConfig} for details.
         *
         * @param proxyConfig the proxy configuration
         * @return this builder
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * Set whether to ignore SSL certificate verification.
         *
         * <p><b>Warning:</b> Setting this to true disables SSL certificate verification,
         * which makes the connection vulnerable to man-in-the-middle attacks.
         * This should only be used for testing or with trusted self-signed certificates.
         *
         * @param ignoreSsl true to ignore SSL certificate verification, false otherwise
         * @return this builder
         */
        public Builder ignoreSsl(boolean ignoreSsl) {
            this.ignoreSsl = ignoreSsl;
            return this;
        }

        /**
         * Build the WebSocketTransportConfig.
         *
         * @return a new WebSocketTransportConfig instance
         */
        public WebSocketTransportConfig build() {
            return new WebSocketTransportConfig(this);
        }
    }
}
