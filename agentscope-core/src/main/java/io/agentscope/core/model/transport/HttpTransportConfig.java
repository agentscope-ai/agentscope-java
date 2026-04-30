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
package io.agentscope.core.model.transport;

import java.time.Duration;

/**
 * Configuration for HTTP transport layer.
 *
 * <p>This class holds configuration options for HTTP client behavior such as
 * timeouts, connection pool settings, and retry policies.
 */
public class HttpTransportConfig {

    /** Default connect timeout: 30 seconds. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** Default response timeout (TTFT): 5 minutes (Time To First Token for streaming). */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    /** Default stream idle timeout: 30 seconds (Maximum wait time between consecutive data chunks). */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /** Default read timeout: 5 minutes (Overall timeout for non-streaming calls). */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

    /** Default write timeout: 30 seconds. */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Duration streamIdleTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final int maxIdleConnections;
    private final Duration keepAliveDuration;
    private final boolean ignoreSsl;
    private final ProxyConfig proxyConfig;
    private final HttpVersion httpVersion;

    private HttpTransportConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.responseTimeout = builder.responseTimeout;
        this.streamIdleTimeout = builder.streamIdleTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.maxIdleConnections = builder.maxIdleConnections;
        this.keepAliveDuration = builder.keepAliveDuration;
        this.ignoreSsl = builder.ignoreSsl;
        this.proxyConfig = builder.proxyConfig;
        this.httpVersion = builder.httpVersion;
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
     * Get the response timeout (Time To First Token for streaming).
     *
     * @return the response timeout duration
     */
    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /**
     * Get the stream idle timeout (maximum time between two consecutive data chunks).
     *
     * @return the stream idle timeout duration
     */
    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    /**
     * Get the read timeout(for non-streaming).
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
     * Get the maximum number of idle connections in the pool.
     *
     * @return the max idle connections
     */
    public int getMaxIdleConnections() {
        return maxIdleConnections;
    }

    /**
     * Get the keep-alive duration for idle connections.
     *
     * @return the keep-alive duration
     */
    public Duration getKeepAliveDuration() {
        return keepAliveDuration;
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
     * Get the proxy configuration.
     *
     * @return the proxy configuration, or null if no proxy is configured
     */
    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    /**
     * Get the HTTP version.
     *
     * @return the HTTP version
     */
    public HttpVersion getHttpVersion() {
        return httpVersion;
    }

    /**
     * Create a new builder for HttpTransportConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a default configuration.
     *
     * @return a default HttpTransportConfig instance
     */
    public static HttpTransportConfig defaults() {
        return builder().build();
    }

    /**
     * Builder for HttpTransportConfig.
     */
    public static class Builder {
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
        private Duration streamIdleTimeout = DEFAULT_STREAM_IDLE_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private int maxIdleConnections = 5;
        private Duration keepAliveDuration = Duration.ofMinutes(5);
        private boolean ignoreSsl = false;
        private ProxyConfig proxyConfig = null;
        private HttpVersion httpVersion = HttpVersion.HTTP_2;

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
         * Set the response timeout (Time To First Byte).
         *
         * @param responseTimeout the response timeout duration
         * @return this builder
         */
        public Builder responseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
            return this;
        }

        /**
         * Set the stream idle timeout.
         *
         * @param streamIdleTimeout the stream idle timeout duration
         * @return this builder
         */
        public Builder streamIdleTimeout(Duration streamIdleTimeout) {
            this.streamIdleTimeout = streamIdleTimeout;
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
         * Set the maximum number of idle connections in the pool.
         *
         * @param maxIdleConnections the max idle connections
         * @return this builder
         */
        public Builder maxIdleConnections(int maxIdleConnections) {
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }

        /**
         * Set the keep-alive duration for idle connections.
         *
         * @param keepAliveDuration the keep-alive duration
         * @return this builder
         */
        public Builder keepAliveDuration(Duration keepAliveDuration) {
            this.keepAliveDuration = keepAliveDuration;
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
         * Set the HTTP version to use.
         *
         * @param httpVersion the HTTP version
         * @return this builder
         */
        public Builder httpVersion(HttpVersion httpVersion) {
            this.httpVersion = httpVersion;
            return this;
        }

        /**
         * Build the HttpTransportConfig.
         *
         * @return a new HttpTransportConfig instance
         */
        public HttpTransportConfig build() {
            return new HttpTransportConfig(this);
        }
    }
}
