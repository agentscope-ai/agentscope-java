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
 * Supported compression encodings for HTTP requests and responses.
 *
 * <p>This enum defines the compression algorithms that can be used for:
 * <ul>
 *   <li>Request body compression (Content-Encoding header)</li>
 *   <li>Response body decompression (Accept-Encoding header)</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * CompressionConfig config = CompressionConfig.builder()
 *     .requestCompression(CompressionEncoding.GZIP)
 *     .acceptEncoding(CompressionEncoding.GZIP)
 *     .build();
 * }</pre>
 */
public enum CompressionEncoding {

    /**
     * No compression.
     */
    NONE("identity"),

    /**
     * GZIP compression (RFC 1952).
     *
     * <p>GZIP is the most widely supported compression format and provides
     * good compression ratios for text-based content like JSON. It is the
     * de facto standard for HTTP content compression and is supported by
     * virtually all HTTP servers and clients.
     *
     * <p>Always available (built into Java).
     */
    GZIP("gzip"),

    /**
     * Brotli compression (RFC 7932).
     *
     * <p>Brotli is a modern compression algorithm developed by Google that
     * typically achieves 20-26% better compression ratios than GZIP. It is
     * widely supported by modern browsers and CDNs.
     *
     * <p>Requires optional dependency: com.aayushatharva.brotli4j:brotli4j
     */
    BROTLI("br"),

    /**
     * Zstandard (Zstd) compression (RFC 8478).
     *
     * <p>Zstd is a fast compression algorithm developed by Facebook that
     * provides excellent compression ratios with very high compression and
     * decompression speeds. Ideal for high-performance scenarios.
     *
     * <p>Requires optional dependency: com.github.luben:zstd-jni
     */
    ZSTD("zstd");

    private final String headerValue;

    CompressionEncoding(String headerValue) {
        this.headerValue = headerValue;
    }

    /**
     * Get the HTTP header value for this compression encoding.
     *
     * <p>This value is used in Content-Encoding and Accept-Encoding headers.
     *
     * @return the HTTP header value (e.g., "gzip", "br", "zstd", "identity")
     */
    public String getHeaderValue() {
        return headerValue;
    }

    /**
     * Parse a compression encoding from an HTTP header value.
     *
     * <p>This method performs case-insensitive matching and returns NONE
     * for unrecognized values.
     *
     * @param headerValue the HTTP header value to parse
     * @return the corresponding CompressionEncoding, or NONE if not recognized
     */
    public static CompressionEncoding fromHeaderValue(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return NONE;
        }

        String normalized = headerValue.toLowerCase().trim();
        for (CompressionEncoding encoding : values()) {
            if (encoding.headerValue.equals(normalized)) {
                return encoding;
            }
        }

        return NONE;
    }

    /**
     * Build an Accept-Encoding header value with multiple encodings.
     *
     * <p>Creates a comma-separated list of encoding values suitable for
     * the Accept-Encoding HTTP header.
     *
     * @param encodings the encodings to include
     * @return the Accept-Encoding header value
     */
    public static String buildAcceptEncodingHeader(CompressionEncoding... encodings) {
        if (encodings == null || encodings.length == 0) {
            return NONE.getHeaderValue();
        }

        StringBuilder sb = new StringBuilder();
        for (CompressionEncoding encoding : encodings) {
            if (encoding != NONE) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(encoding.getHeaderValue());
            }
        }

        return !sb.isEmpty() ? sb.toString() : NONE.getHeaderValue();
    }

    @Override
    public String toString() {
        return headerValue;
    }
}
