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
package io.agentscope.examples.compression.extra;

/**
 * Configuration for HTTP compression.
 *
 * <p>This class holds configuration options for HTTP request/response compression.
 *
 * <p>Compression configuration example:
 * <pre>{@code
 * CompressionConfig config = CompressionConfig.builder()
 *     .requestCompression(CompressionEncoding.GZIP)  // Compress request body
 *     .acceptEncoding(CompressionEncoding.GZIP)      // Accept compressed responses
 *     .autoDecompress(true)                          // Auto decompress responses
 *     .build();
 * }</pre>
 */
public class CompressionConfig {

    private final CompressionEncoding requestCompression;
    private final CompressionEncoding acceptEncoding;
    private final boolean autoDecompress;

    private CompressionConfig(Builder builder) {
        this.requestCompression = builder.requestCompression;
        this.acceptEncoding = builder.acceptEncoding;
        this.autoDecompress = builder.autoDecompress;
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
     * Create a new builder for CompressionConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a default configuration (no compression).
     *
     * @return a default CompressionConfig instance
     */
    public static CompressionConfig defaults() {
        return builder().build();
    }

    /**
     * Create a GZIP compression configuration.
     *
     * <p>This enables GZIP compression for both requests and responses.
     *
     * @return a GZIP enabled CompressionConfig instance
     */
    public static CompressionConfig gzip() {
        return builder().enableGzipCompression().build();
    }

    /**
     * Builder for CompressionConfig.
     */
    public static class Builder {
        private CompressionEncoding requestCompression = CompressionEncoding.NONE;
        private CompressionEncoding acceptEncoding = CompressionEncoding.NONE;
        private boolean autoDecompress = true;

        /**
         * Set the compression encoding for request body.
         *
         * <p>When set, the request body will be compressed using this encoding
         * and the Content-Encoding header will be added automatically.
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
         * Enable full GZIP compression for both requests and responses.
         *
         * <p>Same as {@link #enableGzipCompression()}.
         *
         * @return this builder
         */
        public Builder enableFullGzipCompression() {
            return enableGzipCompression();
        }

        /**
         * Build the CompressionConfig.
         *
         * @return a new CompressionConfig instance
         */
        public CompressionConfig build() {
            return new CompressionConfig(this);
        }
    }
}
