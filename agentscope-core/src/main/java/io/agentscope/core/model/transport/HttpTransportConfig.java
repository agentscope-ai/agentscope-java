/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.model.transport;

import java.time.Duration;

/**
 * Configuration for HTTP transport layer.
 *
 * <p>This class holds configuration options for HTTP client behavior such as
 * timeouts, connection pool settings, retry policies, and compression settings.
 *
 * <p>Compression configuration example:
 * <pre>{@code
 * HttpTransportConfig config = HttpTransportConfig.builder()
 *     .requestCompression(CompressionEncoding.GZIP)  // Compress request body
 *     .acceptEncoding(CompressionEncoding.GZIP)      // Accept compressed responses
 *     .build();
 * }</pre>
 */
public class HttpTransportConfig {

    /** Default connect timeout: 30 seconds. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** Default read timeout: 5 minutes (for long-running model calls). */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

    /** Default write timeout: 30 seconds. */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final int maxIdleConnections;
    private final Duration keepAliveDuration;
    private final CompressionEncoding requestCompression;
    private final CompressionEncoding acceptEncoding;
    private final boolean autoDecompress;

    private HttpTransportConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.maxIdleConnections = builder.maxIdleConnections;
        this.keepAliveDuration = builder.keepAliveDuration;
        this.requestCompression = builder.requestCompression;
        this.acceptEncoding = builder.acceptEncoding;
        this.autoDecompress = builder.autoDecompress;
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
     * Get the compression encoding for request body.
     *
     * <p>When set, the request body will be compressed using this encoding
     * and the Content-Encoding header will be added automatically.
     *
     * @return the request compression encoding, or NONE if compression is disabled
     */
    public CompressionEncoding getRequestCompression() {
        return requestCompression;
    }

    /**
     * Get the accepted compression encoding for responses.
     *
     * <p>When set, the Accept-Encoding header will be added to requests,
     * indicating that the client can accept compressed responses.
     *
     * @return the accept encoding, or NONE if not specified
     */
    public CompressionEncoding getAcceptEncoding() {
        return acceptEncoding;
    }

    /**
     * Check if automatic response decompression is enabled.
     *
     * <p>When enabled, responses with Content-Encoding header will be
     * automatically decompressed based on the encoding.
     *
     * @return true if automatic decompression is enabled
     */
    public boolean isAutoDecompress() {
        return autoDecompress;
    }

    /**
     * Check if request compression is enabled.
     *
     * @return true if request compression is enabled
     */
    public boolean isRequestCompressionEnabled() {
        return requestCompression != null && requestCompression != CompressionEncoding.NONE;
    }

    /**
     * Check if Accept-Encoding header should be sent.
     *
     * @return true if Accept-Encoding should be sent
     */
    public boolean isAcceptEncodingEnabled() {
        return acceptEncoding != null && acceptEncoding != CompressionEncoding.NONE;
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
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private int maxIdleConnections = 5;
        private Duration keepAliveDuration = Duration.ofMinutes(5);
        private CompressionEncoding requestCompression = CompressionEncoding.NONE;
        private CompressionEncoding acceptEncoding = CompressionEncoding.NONE;
        private boolean autoDecompress = true;

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
         * Set the compression encoding for request body.
         *
         * <p>When set, the request body will be compressed using this encoding
         * and the Content-Encoding header will be added automatically.
         *
         * <p>Example:
         * <pre>{@code
         * HttpTransportConfig config = HttpTransportConfig.builder()
         *     .requestCompression(CompressionEncoding.GZIP)
         *     .build();
         * }</pre>
         *
         * @param requestCompression the compression encoding for requests
         * @return this builder
         */
        public Builder requestCompression(CompressionEncoding requestCompression) {
            this.requestCompression =
                    requestCompression != null ? requestCompression : CompressionEncoding.NONE;
            return this;
        }

        /**
         * Set the accepted compression encoding for responses.
         *
         * <p>When set, the Accept-Encoding header will be added to requests,
         * indicating that the client can accept compressed responses.
         *
         * <p>Example:
         * <pre>{@code
         * HttpTransportConfig config = HttpTransportConfig.builder()
         *     .acceptEncoding(CompressionEncoding.GZIP)
         *     .build();
         * }</pre>
         *
         * @param acceptEncoding the accepted compression encoding
         * @return this builder
         */
        public Builder acceptEncoding(CompressionEncoding acceptEncoding) {
            this.acceptEncoding =
                    acceptEncoding != null ? acceptEncoding : CompressionEncoding.NONE;
            return this;
        }

        /**
         * Enable or disable automatic response decompression.
         *
         * <p>When enabled (default), responses with Content-Encoding header
         * will be automatically decompressed based on the encoding.
         *
         * @param autoDecompress true to enable automatic decompression
         * @return this builder
         */
        public Builder autoDecompress(boolean autoDecompress) {
            this.autoDecompress = autoDecompress;
            return this;
        }

        /**
         * Enable GZIP compression for both requests and responses.
         *
         * <p>This is a convenience method that sets:
         * <ul>
         *   <li>requestCompression to GZIP</li>
         *   <li>acceptEncoding to GZIP</li>
         *   <li>autoDecompress to true</li>
         * </ul>
         *
         * @return this builder
         */
        public Builder enableGzipCompression() {
            this.requestCompression = CompressionEncoding.GZIP;
            this.acceptEncoding = CompressionEncoding.GZIP;
            this.autoDecompress = true;
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
