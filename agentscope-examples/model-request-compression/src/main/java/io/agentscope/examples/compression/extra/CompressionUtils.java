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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for HTTP request/response body compression and decompression.
 *
 * <p>This class provides static methods for compressing and decompressing data
 * using the compression algorithms defined in {@link CompressionEncoding}.
 *
 * <p>Supported compression formats:
 * <ul>
 *   <li><b>GZIP</b> - Always available (built into Java). The de facto standard for HTTP compression.</li>
 *   <li><b>Brotli</b> - Requires optional dependency: com.aayushatharva.brotli4j:brotli4j.
 *       Provides 20-26% better compression than GZIP.</li>
 *   <li><b>Zstd</b> - Requires optional dependency: com.github.luben:zstd-jni.
 *       Very fast compression/decompression with excellent ratios.</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * // Check if an encoding is available
 * if (CompressionUtils.isEncodingAvailable(CompressionEncoding.BROTLI)) {
 *     byte[] compressed = CompressionUtils.compress(jsonBody, CompressionEncoding.BROTLI);
 * }
 *
 * // Compress request body (falls back to GZIP if preferred encoding unavailable)
 * String jsonBody = "{\"messages\": [...]}";
 * byte[] compressed = CompressionUtils.compress(jsonBody, CompressionEncoding.GZIP);
 *
 * // Decompress response body
 * byte[] compressedResponse = ...;
 * String decompressed = CompressionUtils.decompressToString(compressedResponse, CompressionEncoding.GZIP);
 * }</pre>
 */
public final class CompressionUtils {

    private static final Logger log = LoggerFactory.getLogger(CompressionUtils.class);

    /** Default buffer size for compression/decompression operations. */
    private static final int BUFFER_SIZE = 8192;

    /** Whether Brotli4j library is available. */
    private static final boolean BROTLI_AVAILABLE;

    /** Whether Zstd-jni library is available. */
    private static final boolean ZSTD_AVAILABLE;

    static {
        BROTLI_AVAILABLE = checkBrotliAvailable();
        ZSTD_AVAILABLE = checkZstdAvailable();

        if (BROTLI_AVAILABLE) {
            log.debug("Brotli compression is available");
        }
        if (ZSTD_AVAILABLE) {
            log.debug("Zstd compression is available");
        }
    }

    private CompressionUtils() {
        // Utility class, no instantiation
    }

    /**
     * Check if a compression encoding is available at runtime.
     *
     * <p>GZIP is always available. Brotli and Zstd require optional dependencies.
     *
     * @param encoding the encoding to check
     * @return true if the encoding is available
     */
    public static boolean isEncodingAvailable(CompressionEncoding encoding) {
        if (encoding == null) {
            return false;
        }
        return switch (encoding) {
            case NONE, GZIP -> true;
            case BROTLI -> BROTLI_AVAILABLE;
            case ZSTD -> ZSTD_AVAILABLE;
        };
    }

    /**
     * Check if Brotli compression is available.
     *
     * @return true if Brotli is available
     */
    public static boolean isBrotliAvailable() {
        return BROTLI_AVAILABLE;
    }

    /**
     * Check if Zstd compression is available.
     *
     * @return true if Zstd is available
     */
    public static boolean isZstdAvailable() {
        return ZSTD_AVAILABLE;
    }

    /**
     * Compress a string using the specified encoding.
     *
     * <p>The string is converted to UTF-8 bytes before compression.
     *
     * @param data the string to compress
     * @param encoding the compression encoding to use
     * @return the compressed data as a byte array
     * @throws CompressionException if compression fails or encoding is unavailable
     */
    public static byte[] compress(String data, CompressionEncoding encoding) {
        if (data == null) {
            return null;
        }
        return compress(data.getBytes(StandardCharsets.UTF_8), encoding);
    }

    /**
     * Compress a byte array using the specified encoding.
     *
     * @param data the data to compress
     * @param encoding the compression encoding to use
     * @return the compressed data as a byte array
     * @throws CompressionException if compression fails or encoding is unavailable
     */
    public static byte[] compress(byte[] data, CompressionEncoding encoding) {
        if (data == null) {
            return null;
        }

        if (encoding == null || encoding == CompressionEncoding.NONE) {
            return data;
        }

        try {
            switch (encoding) {
                case GZIP:
                    return compressGzip(data);
                case BROTLI:
                    return BrotliHolder.compress(data);
                case ZSTD:
                    return ZstdHolder.compress(data);
                default:
                    return data;
            }
        } catch (IOException e) {
            throw new CompressionException("Failed to compress data using " + encoding, e);
        }
    }

    /**
     * Decompress a byte array using the specified encoding.
     *
     * @param data the compressed data
     * @param encoding the compression encoding used
     * @return the decompressed data as a byte array
     * @throws CompressionException if decompression fails or encoding is unavailable
     */
    public static byte[] decompress(byte[] data, CompressionEncoding encoding) {
        if (data == null) {
            return null;
        }

        if (encoding == null || encoding == CompressionEncoding.NONE) {
            return data;
        }

        try {
            switch (encoding) {
                case GZIP:
                    return decompressGzip(data);
                case BROTLI:
                    return BrotliHolder.decompress(data);
                case ZSTD:
                    return ZstdHolder.decompress(data);
                default:
                    return data;
            }
        } catch (IOException e) {
            throw new CompressionException("Failed to decompress data using " + encoding, e);
        }
    }

    /**
     * Decompress a byte array to a UTF-8 string.
     *
     * @param data the compressed data
     * @param encoding the compression encoding used
     * @return the decompressed data as a UTF-8 string
     * @throws CompressionException if decompression fails
     */
    public static String decompressToString(byte[] data, CompressionEncoding encoding) {
        byte[] decompressed = decompress(data, encoding);
        if (decompressed == null) {
            return null;
        }
        return new String(decompressed, StandardCharsets.UTF_8);
    }

    /**
     * Decompress an input stream using the specified encoding.
     *
     * <p>This method wraps the input stream with the appropriate decompression
     * stream based on the encoding.
     *
     * @param inputStream the compressed input stream
     * @param encoding the compression encoding used
     * @return the decompression input stream
     * @throws CompressionException if creating the decompression stream fails
     */
    public static InputStream decompressStream(
            InputStream inputStream, CompressionEncoding encoding) {
        if (inputStream == null) {
            return null;
        }

        if (encoding == null || encoding == CompressionEncoding.NONE) {
            return inputStream;
        }

        try {
            switch (encoding) {
                case GZIP:
                    return new GZIPInputStream(inputStream, BUFFER_SIZE);
                case BROTLI:
                    return BrotliHolder.createInputStream(inputStream);
                case ZSTD:
                    return ZstdHolder.createInputStream(inputStream);
                default:
                    return inputStream;
            }
        } catch (IOException e) {
            throw new CompressionException(
                    "Failed to create decompression stream for " + encoding, e);
        }
    }

    /**
     * Check if the data appears to be GZIP compressed.
     *
     * <p>This checks for the GZIP magic number (0x1f8b) at the start of the data.
     *
     * @param data the data to check
     * @return true if the data appears to be GZIP compressed
     */
    public static boolean isGzipCompressed(byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        // GZIP magic number: 0x1f 0x8b
        return (data[0] == (byte) 0x1f) && (data[1] == (byte) 0x8b);
    }

    // ==================== GZIP Implementation ====================

    private static byte[] compressGzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos, BUFFER_SIZE)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] decompressGzip(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length * 2);

        try (GZIPInputStream gzis = new GZIPInputStream(bais, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }

        return baos.toByteArray();
    }

    // ==================== Brotli Availability Check ====================

    private static boolean checkBrotliAvailable() {
        try {
            Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
            // Try to initialize - this will be done in the holder class
            return true;
        } catch (ClassNotFoundException e) {
            log.debug(
                    "Brotli compression not available: brotli4j library not found. "
                            + "Add dependency: com.aayushatharva.brotli4j:brotli4j");
            return false;
        } catch (NoClassDefFoundError e) {
            log.debug(
                    "Brotli compression not available: class definition error. Error: {}",
                    e.getMessage());
            return false;
        }
    }

    // ==================== Zstd Availability Check ====================

    private static boolean checkZstdAvailable() {
        try {
            Class.forName("com.github.luben.zstd.Zstd");
            return true;
        } catch (ClassNotFoundException e) {
            log.debug(
                    "Zstd compression not available: zstd-jni library not found. "
                            + "Add dependency: com.github.luben:zstd-jni");
            return false;
        } catch (NoClassDefFoundError e) {
            log.debug(
                    "Zstd compression not available: class definition error. Error: {}",
                    e.getMessage());
            return false;
        }
    }

    // ==================== Brotli Holder (Lazy Loading) ====================

    /**
     * Holder class for Brotli implementation.
     *
     * <p>This class is only loaded when Brotli operations are actually needed,
     * preventing ClassNotFoundException when the brotli4j dependency is not present.
     */
    private static final class BrotliHolder {

        private static final boolean INITIALIZED;
        private static final String INIT_ERROR;

        static {
            boolean initialized = false;
            String error = null;
            try {
                com.aayushatharva.brotli4j.Brotli4jLoader.ensureAvailability();
                initialized = true;
            } catch (UnsatisfiedLinkError e) {
                error = "Native library load failed: " + e.getMessage();
                log.debug("Brotli initialization failed: {}", error);
            } catch (Exception e) {
                error = e.getMessage();
                log.debug("Brotli initialization failed: {}", error);
            }
            INITIALIZED = initialized;
            INIT_ERROR = error;
        }

        static byte[] compress(byte[] data) throws IOException {
            ensureAvailable();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            try (com.aayushatharva.brotli4j.encoder.BrotliOutputStream bos =
                    new com.aayushatharva.brotli4j.encoder.BrotliOutputStream(baos)) {
                bos.write(data);
            }
            return baos.toByteArray();
        }

        static byte[] decompress(byte[] data) throws IOException {
            ensureAvailable();

            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length * 2);

            try (com.aayushatharva.brotli4j.decoder.BrotliInputStream bis =
                    new com.aayushatharva.brotli4j.decoder.BrotliInputStream(bais)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = bis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
            }

            return baos.toByteArray();
        }

        static InputStream createInputStream(InputStream inputStream) throws IOException {
            ensureAvailable();
            return new com.aayushatharva.brotli4j.decoder.BrotliInputStream(inputStream);
        }

        private static void ensureAvailable() {
            if (!BROTLI_AVAILABLE) {
                throw new CompressionException(
                        "Brotli compression is not available. "
                                + "Add dependency: com.aayushatharva.brotli4j:brotli4j");
            }
            if (!INITIALIZED) {
                throw new CompressionException("Brotli initialization failed: " + INIT_ERROR);
            }
        }
    }

    // ==================== Zstd Holder (Lazy Loading) ====================

    /**
     * Holder class for Zstd implementation.
     *
     * <p>This class is only loaded when Zstd operations are actually needed,
     * preventing ClassNotFoundException when the zstd-jni dependency is not present.
     */
    private static final class ZstdHolder {

        static byte[] compress(byte[] data) throws IOException {
            ensureAvailable();
            return com.github.luben.zstd.Zstd.compress(data);
        }

        static byte[] decompress(byte[] data) throws IOException {
            ensureAvailable();

            // Get the decompressed size (stored in the frame header)
            long decompressedSize = com.github.luben.zstd.Zstd.decompressedSize(data);
            if (decompressedSize <= 0) {
                // If size is unknown, use streaming decompression
                return decompressStreaming(data);
            }

            byte[] output = new byte[(int) decompressedSize];
            long result = com.github.luben.zstd.Zstd.decompress(output, data);
            if (com.github.luben.zstd.Zstd.isError(result)) {
                throw new IOException(
                        "Zstd decompression failed: "
                                + com.github.luben.zstd.Zstd.getErrorName(result));
            }
            return output;
        }

        private static byte[] decompressStreaming(byte[] data) throws IOException {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length * 2);

            try (com.github.luben.zstd.ZstdInputStream zis =
                    new com.github.luben.zstd.ZstdInputStream(bais)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = zis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
            }

            return baos.toByteArray();
        }

        static InputStream createInputStream(InputStream inputStream) throws IOException {
            ensureAvailable();
            return new com.github.luben.zstd.ZstdInputStream(inputStream);
        }

        private static void ensureAvailable() {
            if (!ZSTD_AVAILABLE) {
                throw new CompressionException(
                        "Zstd compression is not available. "
                                + "Add dependency: com.github.luben:zstd-jni");
            }
        }
    }

    /**
     * Exception thrown when compression or decompression fails.
     */
    public static class CompressionException extends RuntimeException {

        /**
         * Create a new CompressionException.
         *
         * @param message the error message
         */
        public CompressionException(String message) {
            super(message);
        }

        /**
         * Create a new CompressionException with a cause.
         *
         * @param message the error message
         * @param cause the underlying cause
         */
        public CompressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
