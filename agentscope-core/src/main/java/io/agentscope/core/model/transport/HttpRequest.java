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
 * HTTP request encapsulation for the transport layer.
 *
 * <p>This class represents an HTTP request with URL, method, headers, and body.
 * Use the builder pattern to construct requests.
 *
 * <p>Supports both string and binary body content, with optional compression.
 *
 * <p>Compression example:
 * <pre>{@code
 * HttpRequest request = HttpRequest.builder()
 *     .url("https://api.example.com/v1/chat")
 *     .method("POST")
 *     .header("Content-Type", "application/json")
 *     .body("{\"messages\": [...]}")
 *     .compressBody(CompressionEncoding.GZIP)
 *     .build();
 * }</pre>
 */
public class HttpRequest {

    /** HTTP header name for content encoding (request compression). */
    public static final String HEADER_CONTENT_ENCODING = "Content-Encoding";

    /** HTTP header name for accepted encodings (response compression). */
    public static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;
    private final byte[] bodyBytes;
    private final CompressionEncoding contentEncoding;

    private HttpRequest(Builder builder) {
        this.url = builder.url;
        this.method = builder.method;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.body = builder.body;
        this.bodyBytes = builder.bodyBytes;
        this.contentEncoding = builder.contentEncoding;
    }

    /**
     * Get the request URL.
     *
     * @return the URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Get the HTTP method.
     *
     * @return the method (GET, POST, etc.)
     */
    public String getMethod() {
        return method;
    }

    /**
     * Get the request headers.
     *
     * @return an unmodifiable map of headers
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get the request body as string.
     *
     * <p>If the body was set as bytes, this returns null.
     * Use {@link #getBodyBytes()} to get the raw bytes.
     *
     * @return the body string, or null if no body or body is binary
     */
    public String getBody() {
        return body;
    }

    /**
     * Get the request body as bytes.
     *
     * <p>If compression is enabled, this returns the compressed body.
     * If the body was set as string, it will be encoded as UTF-8.
     *
     * @return the body bytes, or null if no body
     */
    public byte[] getBodyBytes() {
        if (bodyBytes != null) {
            return bodyBytes;
        }
        if (body != null) {
            return body.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Get the content encoding for this request.
     *
     * @return the content encoding, or null if no compression
     */
    public CompressionEncoding getContentEncoding() {
        return contentEncoding;
    }

    /**
     * Check if this request has a compressed body. Refer to {@link Builder#compressBody}
     * @return true if the body is compressed
     */
    public boolean isCompressed() {
        return contentEncoding != null && contentEncoding != CompressionEncoding.NONE;
    }

    /**
     * Check if this request has body bytes (either compressed or raw binary).
     *
     * @return true if bodyBytes is set
     */
    public boolean hasBodyBytes() {
        return bodyBytes != null;
    }

    /**
     * Create a new builder for HttpRequest.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for HttpRequest.
     */
    public static class Builder {
        private String url;
        private String method = "GET";
        private final Map<String, String> headers = new HashMap<>();
        private String body;
        private byte[] bodyBytes;
        private CompressionEncoding contentEncoding;

        /**
         * Set the request URL.
         *
         * @param url the URL
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Set the HTTP method.
         *
         * @param method the method (GET, POST, etc.)
         * @return this builder
         */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /**
         * Add a header to the request.
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
         * Add multiple headers to the request.
         *
         * @param headers the headers to add
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Set the request body as string.
         *
         * @param body the body string
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            this.bodyBytes = null;
            return this;
        }

        /**
         * Set the request body as bytes.
         *
         * <p>Use this for binary content or pre-compressed data.
         *
         * @param bodyBytes the body bytes
         * @return this builder
         */
        public Builder bodyBytes(byte[] bodyBytes) {
            this.bodyBytes = bodyBytes;
            this.body = null;
            return this;
        }

        /**
         * Compress the body using the specified encoding.
         *
         * <p>This method compresses the current body (string or bytes) and:
         * <ul>
         *   <li>Stores the compressed data as bodyBytes</li>
         *   <li>Sets the Content-Encoding header</li>
         *   <li>Clears the string body</li>
         * </ul>
         *
         * <p>Example:
         * <pre>{@code
         * HttpRequest request = HttpRequest.builder()
         *     .url("https://api.example.com/v1/chat")
         *     .method("POST")
         *     .body("{\"messages\": [...]}")
         *     .compressBody(CompressionEncoding.GZIP)
         *     .build();
         * }</pre>
         *
         * @param encoding the compression encoding to use
         * @return this builder
         * @throws CompressionUtils.CompressionException if compression fails
         */
        public Builder compressBody(CompressionEncoding encoding) {
            if (encoding == null || encoding == CompressionEncoding.NONE) {
                return this;
            }

            byte[] dataToCompress = null;
            if (body != null) {
                dataToCompress = body.getBytes(StandardCharsets.UTF_8);
            } else if (bodyBytes != null) {
                dataToCompress = bodyBytes;
            }

            if (dataToCompress != null) {
                this.bodyBytes = CompressionUtils.compress(dataToCompress, encoding);
                this.body = null;
                this.contentEncoding = encoding;
                this.headers.put(HEADER_CONTENT_ENCODING, encoding.getHeaderValue());
            }

            return this;
        }

        /**
         * Set the Accept-Encoding header to indicate supported response compression.
         *
         * @param encoding the accepted compression encoding
         * @return this builder
         */
        public Builder acceptEncoding(CompressionEncoding encoding) {
            if (encoding != null && encoding != CompressionEncoding.NONE) {
                this.headers.put(HEADER_ACCEPT_ENCODING, encoding.getHeaderValue());
            }
            return this;
        }

        /**
         * Set the Accept-Encoding header with multiple encodings.
         *
         * @param encodings the accepted compression encodings
         * @return this builder
         */
        public Builder acceptEncodings(CompressionEncoding... encodings) {
            if (encodings != null && encodings.length > 0) {
                String headerValue = CompressionEncoding.buildAcceptEncodingHeader(encodings);
                if (!CompressionEncoding.NONE.getHeaderValue().equals(headerValue)) {
                    this.headers.put(HEADER_ACCEPT_ENCODING, headerValue);
                }
            }
            return this;
        }

        /**
         * Build the HttpRequest.
         *
         * @return a new HttpRequest instance
         */
        public HttpRequest build() {
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URL is required");
            }
            return new HttpRequest(this);
        }
    }
}
