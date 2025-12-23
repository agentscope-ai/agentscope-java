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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP response encapsulation for the transport layer.
 *
 * <p>This class represents an HTTP response with status code, headers, and body.
 * Supports automatic decompression of compressed response bodies.
 *
 * <p>Decompression example:
 * <pre>{@code
 * // Response with compressed body
 * HttpResponse response = HttpResponse.builder()
 *     .statusCode(200)
 *     .header("Content-Encoding", "gzip")
 *     .bodyBytes(compressedData)
 *     .autoDecompress(true)
 *     .build();
 *
 * // Body is automatically decompressed
 * String body = response.getBody();
 * }</pre>
 */
public class HttpResponse {

    /** HTTP header name for content encoding. */
    public static final String HEADER_CONTENT_ENCODING = "Content-Encoding";

    private final int statusCode;
    private final Map<String, String> headers;
    private final String body;
    private final byte[] bodyBytes;
    private final CompressionEncoding contentEncoding;
    private final boolean wasDecompressed;

    private HttpResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.contentEncoding = builder.contentEncoding;
        this.wasDecompressed = builder.wasDecompressed;

        // Handle decompression if needed
        if (builder.bodyBytes != null
                && builder.autoDecompress
                && builder.contentEncoding != null
                && builder.contentEncoding != CompressionEncoding.NONE) {
            // Decompress the body
            byte[] decompressed =
                    CompressionUtils.decompress(builder.bodyBytes, builder.contentEncoding);
            this.bodyBytes = decompressed;
            this.body = new String(decompressed, StandardCharsets.UTF_8);
        } else if (builder.bodyBytes != null) {
            this.bodyBytes = builder.bodyBytes;
            this.body =
                    builder.body != null
                            ? builder.body
                            : new String(builder.bodyBytes, StandardCharsets.UTF_8);
        } else {
            this.bodyBytes =
                    builder.body != null ? builder.body.getBytes(StandardCharsets.UTF_8) : null;
            this.body = builder.body;
        }
    }

    /**
     * Get the HTTP status code.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Get the response headers.
     *
     * @return an unmodifiable map of headers
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get the response body as string.
     *
     * <p>If the response was compressed and autoDecompress was enabled,
     * this returns the decompressed body.
     *
     * @return the body string, or null if no body
     */
    public String getBody() {
        return body;
    }

    /**
     * Get the response body as bytes.
     *
     * <p>If the response was compressed and autoDecompress was enabled,
     * this returns the decompressed bytes.
     *
     * @return the body bytes, or null if no body
     */
    public byte[] getBodyBytes() {
        return bodyBytes;
    }

    /**
     * Get the content encoding of the original response.
     *
     * @return the content encoding, or null if not compressed
     */
    public CompressionEncoding getContentEncoding() {
        return contentEncoding;
    }

    /**
     * Check if the response body was decompressed.
     *
     * @return true if the body was decompressed
     */
    public boolean wasDecompressed() {
        return wasDecompressed;
    }

    /**
     * Check if the response indicates success (2xx status code).
     *
     * @return true if status code is 2xx
     */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Create a new builder for HttpResponse.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for HttpResponse.
     */
    public static class Builder {
        private int statusCode;
        private final Map<String, String> headers = new HashMap<>();
        private String body;
        private byte[] bodyBytes;
        private CompressionEncoding contentEncoding;
        private boolean autoDecompress = true;
        private boolean wasDecompressed = false;

        /**
         * Set the HTTP status code.
         *
         * @param statusCode the status code
         * @return this builder
         */
        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /**
         * Add a header to the response.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Add multiple headers to the response.
         *
         * @param headers the headers to add
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Set the response body as string.
         *
         * @param body the body string
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * Set the response body as bytes.
         *
         * <p>Use this for compressed or binary response bodies.
         *
         * @param bodyBytes the body bytes
         * @return this builder
         */
        public Builder bodyBytes(byte[] bodyBytes) {
            this.bodyBytes = bodyBytes;
            return this;
        }

        /**
         * Set the content encoding of the response.
         *
         * <p>This indicates how the response body is encoded/compressed.
         *
         * @param contentEncoding the content encoding
         * @return this builder
         */
        public Builder contentEncoding(CompressionEncoding contentEncoding) {
            this.contentEncoding = contentEncoding;
            return this;
        }

        /**
         * Parse and set the content encoding from a header value.
         *
         * @param headerValue the Content-Encoding header value
         * @return this builder
         */
        public Builder contentEncodingFromHeader(String headerValue) {
            this.contentEncoding = CompressionEncoding.fromHeaderValue(headerValue);
            return this;
        }

        /**
         * Enable or disable automatic decompression.
         *
         * <p>When enabled (default), the response body will be automatically
         * decompressed based on the Content-Encoding header.
         *
         * @param autoDecompress true to enable automatic decompression
         * @return this builder
         */
        public Builder autoDecompress(boolean autoDecompress) {
            this.autoDecompress = autoDecompress;
            return this;
        }

        /**
         * Build the HttpResponse.
         *
         * <p>If autoDecompress is enabled and the body is compressed,
         * the body will be decompressed during build.
         *
         * @return a new HttpResponse instance
         */
        public HttpResponse build() {
            // Try to detect content encoding from headers if not explicitly set
            if (contentEncoding == null && headers.containsKey(HEADER_CONTENT_ENCODING)) {
                contentEncoding =
                        CompressionEncoding.fromHeaderValue(headers.get(HEADER_CONTENT_ENCODING));
            }
            // Also check lowercase header name
            if (contentEncoding == null
                    && headers.containsKey(HEADER_CONTENT_ENCODING.toLowerCase())) {
                contentEncoding =
                        CompressionEncoding.fromHeaderValue(
                                headers.get(HEADER_CONTENT_ENCODING.toLowerCase()));
            }

            if (autoDecompress
                    && contentEncoding != null
                    && contentEncoding != CompressionEncoding.NONE) {
                wasDecompressed = true;
            }

            return new HttpResponse(this);
        }
    }
}
