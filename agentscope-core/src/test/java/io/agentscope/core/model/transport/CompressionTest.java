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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for HTTP compression functionality.
 */
class CompressionTest {

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    // ==================== CompressionEncoding Tests ====================

    @Test
    void testCompressionEncodingHeaderValues() {
        assertEquals("identity", CompressionEncoding.NONE.getHeaderValue());
        assertEquals("gzip", CompressionEncoding.GZIP.getHeaderValue());
        assertEquals("br", CompressionEncoding.BROTLI.getHeaderValue());
        assertEquals("zstd", CompressionEncoding.ZSTD.getHeaderValue());
    }

    @Test
    void testFromHeaderValue() {
        assertEquals(CompressionEncoding.GZIP, CompressionEncoding.fromHeaderValue("gzip"));
        assertEquals(CompressionEncoding.GZIP, CompressionEncoding.fromHeaderValue("GZIP"));
        assertEquals(CompressionEncoding.GZIP, CompressionEncoding.fromHeaderValue("  gzip  "));
        assertEquals(CompressionEncoding.BROTLI, CompressionEncoding.fromHeaderValue("br"));
        assertEquals(CompressionEncoding.ZSTD, CompressionEncoding.fromHeaderValue("zstd"));
        assertEquals(CompressionEncoding.NONE, CompressionEncoding.fromHeaderValue("identity"));
        assertEquals(CompressionEncoding.NONE, CompressionEncoding.fromHeaderValue(null));
        assertEquals(CompressionEncoding.NONE, CompressionEncoding.fromHeaderValue(""));
        assertEquals(CompressionEncoding.NONE, CompressionEncoding.fromHeaderValue("unknown"));
    }

    @Test
    void testBuildAcceptEncodingHeader() {
        assertEquals(
                "gzip", CompressionEncoding.buildAcceptEncodingHeader(CompressionEncoding.GZIP));
        assertEquals(
                "gzip, br",
                CompressionEncoding.buildAcceptEncodingHeader(
                        CompressionEncoding.GZIP, CompressionEncoding.BROTLI));
        assertEquals(
                "gzip, br, zstd",
                CompressionEncoding.buildAcceptEncodingHeader(
                        CompressionEncoding.GZIP,
                        CompressionEncoding.BROTLI,
                        CompressionEncoding.ZSTD));
        assertEquals(
                "identity",
                CompressionEncoding.buildAcceptEncodingHeader(CompressionEncoding.NONE));
        assertEquals("identity", CompressionEncoding.buildAcceptEncodingHeader());
    }

    // ==================== CompressionUtils Availability Tests ====================

    @Test
    void testGzipAlwaysAvailable() {
        assertTrue(CompressionUtils.isEncodingAvailable(CompressionEncoding.GZIP));
        assertTrue(CompressionUtils.isEncodingAvailable(CompressionEncoding.NONE));
    }

    @Test
    void testBrotliAvailabilityCheck() {
        // This just tests the check doesn't throw
        boolean available = CompressionUtils.isBrotliAvailable();
        assertEquals(available, CompressionUtils.isEncodingAvailable(CompressionEncoding.BROTLI));
    }

    @Test
    void testZstdAvailabilityCheck() {
        // This just tests the check doesn't throw
        boolean available = CompressionUtils.isZstdAvailable();
        assertEquals(available, CompressionUtils.isEncodingAvailable(CompressionEncoding.ZSTD));
    }

    // ==================== GZIP Compression Tests ====================

    @Test
    void testGzipCompressDecompress() {
        String original = "{\"messages\": [{\"role\": \"user\", \"content\": \"Hello, world!\"}]}";

        // Compress
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.GZIP);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);

        // Decompress
        String decompressed =
                CompressionUtils.decompressToString(compressed, CompressionEncoding.GZIP);
        assertEquals(original, decompressed);
    }

    @Test
    void testCompressWithNone() {
        String original = "No compression applied";
        byte[] result = CompressionUtils.compress(original, CompressionEncoding.NONE);
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void testCompressNull() {
        assertNull(CompressionUtils.compress((String) null, CompressionEncoding.GZIP));
        assertNull(CompressionUtils.compress((byte[]) null, CompressionEncoding.GZIP));
    }

    @Test
    void testDecompressNull() {
        assertNull(CompressionUtils.decompress(null, CompressionEncoding.GZIP));
        assertNull(CompressionUtils.decompressToString(null, CompressionEncoding.GZIP));
    }

    @Test
    void testIsGzipCompressed() {
        String original = "Test data";
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.GZIP);

        assertTrue(CompressionUtils.isGzipCompressed(compressed));
        assertFalse(CompressionUtils.isGzipCompressed(original.getBytes(StandardCharsets.UTF_8)));
        assertFalse(CompressionUtils.isGzipCompressed(null));
        assertFalse(CompressionUtils.isGzipCompressed(new byte[] {0x00}));
    }

    @Test
    void testDecompressStream() throws Exception {
        String original = "Stream decompression test";
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.GZIP);

        InputStream compressedStream = new ByteArrayInputStream(compressed);
        InputStream decompressedStream =
                CompressionUtils.decompressStream(compressedStream, CompressionEncoding.GZIP);

        byte[] result = decompressedStream.readAllBytes();
        assertEquals(original, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void testDecompressStreamWithNone() throws Exception {
        String original = "No decompression";
        InputStream inputStream =
                new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8));

        InputStream result =
                CompressionUtils.decompressStream(inputStream, CompressionEncoding.NONE);
        assertEquals(original, new String(result.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void testLargeDataGzipCompression() {
        // Create a large JSON-like payload
        StringBuilder sb = new StringBuilder("{\"data\": [");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":")
                    .append(i)
                    .append(",\"value\":\"test value ")
                    .append(i)
                    .append("\"}");
        }
        sb.append("]}");
        String original = sb.toString();

        // GZIP compression
        byte[] gzipCompressed = CompressionUtils.compress(original, CompressionEncoding.GZIP);
        assertNotNull(gzipCompressed);
        assertTrue(
                gzipCompressed.length < original.getBytes(StandardCharsets.UTF_8).length,
                "GZIP should compress large repetitive data");

        String gzipDecompressed =
                CompressionUtils.decompressToString(gzipCompressed, CompressionEncoding.GZIP);
        assertEquals(original, gzipDecompressed);
    }

    // ==================== Brotli Compression Tests ====================

    static boolean brotliAvailable() {
        return CompressionUtils.isBrotliAvailable();
    }

    @Test
    @EnabledIf("brotliAvailable")
    void testBrotliCompressDecompress() {
        String original = "{\"messages\": [{\"role\": \"user\", \"content\": \"Hello, Brotli!\"}]}";

        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.BROTLI);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);

        String decompressed =
                CompressionUtils.decompressToString(compressed, CompressionEncoding.BROTLI);
        assertEquals(original, decompressed);
    }

    @Test
    @EnabledIf("brotliAvailable")
    void testBrotliLargeDataCompression() {
        StringBuilder sb = new StringBuilder("{\"data\": [");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":")
                    .append(i)
                    .append(",\"value\":\"test value for brotli ")
                    .append(i)
                    .append("\"}");
        }
        sb.append("]}");
        String original = sb.toString();

        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.BROTLI);
        assertNotNull(compressed);
        assertTrue(
                compressed.length < original.getBytes(StandardCharsets.UTF_8).length,
                "Brotli should compress large repetitive data");

        String decompressed =
                CompressionUtils.decompressToString(compressed, CompressionEncoding.BROTLI);
        assertEquals(original, decompressed);
    }

    @Test
    @EnabledIf("brotliAvailable")
    void testBrotliDecompressStream() throws Exception {
        String original = "Stream decompression test for Brotli";
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.BROTLI);

        InputStream compressedStream = new ByteArrayInputStream(compressed);
        InputStream decompressedStream =
                CompressionUtils.decompressStream(compressedStream, CompressionEncoding.BROTLI);

        byte[] result = decompressedStream.readAllBytes();
        assertEquals(original, new String(result, StandardCharsets.UTF_8));
    }

    // ==================== Zstd Compression Tests ====================

    static boolean zstdAvailable() {
        return CompressionUtils.isZstdAvailable();
    }

    @Test
    @EnabledIf("zstdAvailable")
    void testZstdCompressDecompress() {
        String original = "{\"messages\": [{\"role\": \"user\", \"content\": \"Hello, Zstd!\"}]}";

        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.ZSTD);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);

        String decompressed =
                CompressionUtils.decompressToString(compressed, CompressionEncoding.ZSTD);
        assertEquals(original, decompressed);
    }

    @Test
    @EnabledIf("zstdAvailable")
    void testZstdLargeDataCompression() {
        StringBuilder sb = new StringBuilder("{\"data\": [");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":")
                    .append(i)
                    .append(",\"value\":\"test value for zstd ")
                    .append(i)
                    .append("\"}");
        }
        sb.append("]}");
        String original = sb.toString();

        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.ZSTD);
        assertNotNull(compressed);
        assertTrue(
                compressed.length < original.getBytes(StandardCharsets.UTF_8).length,
                "Zstd should compress large repetitive data");

        String decompressed =
                CompressionUtils.decompressToString(compressed, CompressionEncoding.ZSTD);
        assertEquals(original, decompressed);
    }

    @Test
    @EnabledIf("zstdAvailable")
    void testZstdDecompressStream() throws Exception {
        String original = "Stream decompression test for Zstd";
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.ZSTD);

        InputStream compressedStream = new ByteArrayInputStream(compressed);
        InputStream decompressedStream =
                CompressionUtils.decompressStream(compressedStream, CompressionEncoding.ZSTD);

        byte[] result = decompressedStream.readAllBytes();
        assertEquals(original, new String(result, StandardCharsets.UTF_8));
    }

    // ==================== HttpTransportConfig Compression Tests ====================

    @Test
    void testHttpTransportConfigCompressionDefaults() {
        HttpTransportConfig config = HttpTransportConfig.defaults();

        assertEquals(CompressionEncoding.NONE, config.getRequestCompression());
        assertEquals(CompressionEncoding.NONE, config.getAcceptEncoding());
        assertTrue(config.isAutoDecompress());
        assertFalse(config.isRequestCompressionEnabled());
        assertFalse(config.isAcceptEncodingEnabled());
    }

    @Test
    void testHttpTransportConfigWithCompression() {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .requestCompression(CompressionEncoding.GZIP)
                        .acceptEncoding(CompressionEncoding.GZIP)
                        .autoDecompress(true)
                        .build();

        assertEquals(CompressionEncoding.GZIP, config.getRequestCompression());
        assertEquals(CompressionEncoding.GZIP, config.getAcceptEncoding());
        assertTrue(config.isAutoDecompress());
        assertTrue(config.isRequestCompressionEnabled());
        assertTrue(config.isAcceptEncodingEnabled());
    }

    @Test
    void testHttpTransportConfigEnableGzipCompression() {
        HttpTransportConfig config = HttpTransportConfig.builder().enableGzipCompression().build();

        assertEquals(CompressionEncoding.GZIP, config.getRequestCompression());
        assertEquals(CompressionEncoding.GZIP, config.getAcceptEncoding());
        assertTrue(config.isAutoDecompress());
    }

    // ==================== HttpRequest Compression Tests ====================

    @Test
    void testHttpRequestWithCompressedBody() {
        String body = "{\"messages\": [{\"role\": \"user\", \"content\": \"Hello\"}]}";

        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/chat")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body(body)
                        .compressBody(CompressionEncoding.GZIP)
                        .build();

        assertTrue(request.isCompressed());
        assertEquals(CompressionEncoding.GZIP, request.getContentEncoding());
        assertTrue(request.hasBodyBytes());
        assertNull(request.getBody());

        byte[] compressedBytes = request.getBodyBytes();
        String decompressed =
                CompressionUtils.decompressToString(compressedBytes, CompressionEncoding.GZIP);
        assertEquals(body, decompressed);

        assertEquals("gzip", request.getHeaders().get(HttpRequest.HEADER_CONTENT_ENCODING));
    }

    @Test
    void testHttpRequestWithAcceptEncoding() {
        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/chat")
                        .method("GET")
                        .acceptEncoding(CompressionEncoding.GZIP)
                        .build();

        assertEquals("gzip", request.getHeaders().get(HttpRequest.HEADER_ACCEPT_ENCODING));
    }

    @Test
    void testHttpRequestWithMultipleAcceptEncodings() {
        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/chat")
                        .method("GET")
                        .acceptEncodings(
                                CompressionEncoding.GZIP,
                                CompressionEncoding.BROTLI,
                                CompressionEncoding.ZSTD)
                        .build();

        assertEquals(
                "gzip, br, zstd", request.getHeaders().get(HttpRequest.HEADER_ACCEPT_ENCODING));
    }

    @Test
    void testHttpRequestNoCompression() {
        String body = "{\"test\": \"value\"}";

        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/chat")
                        .method("POST")
                        .body(body)
                        .build();

        assertFalse(request.isCompressed());
        assertNull(request.getContentEncoding());
        assertFalse(request.hasBodyBytes());
        assertEquals(body, request.getBody());
    }

    // ==================== HttpResponse Decompression Tests ====================

    @Test
    void testHttpResponseAutoDecompress() {
        String original = "{\"result\": \"success\"}";
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.GZIP);

        HttpResponse response =
                HttpResponse.builder()
                        .statusCode(200)
                        .header(HttpResponse.HEADER_CONTENT_ENCODING, "gzip")
                        .bodyBytes(compressed)
                        .autoDecompress(true)
                        .build();

        assertTrue(response.isSuccessful());
        assertTrue(response.wasDecompressed());
        assertEquals(CompressionEncoding.GZIP, response.getContentEncoding());
        assertEquals(original, response.getBody());
    }

    @Test
    void testHttpResponseNoAutoDecompress() {
        String original = "{\"result\": \"success\"}";
        byte[] compressed = CompressionUtils.compress(original, CompressionEncoding.GZIP);

        HttpResponse response =
                HttpResponse.builder()
                        .statusCode(200)
                        .header(HttpResponse.HEADER_CONTENT_ENCODING, "gzip")
                        .bodyBytes(compressed)
                        .autoDecompress(false)
                        .build();

        assertTrue(response.isSuccessful());
        assertFalse(response.wasDecompressed());
        assertArrayEquals(compressed, response.getBodyBytes());
    }

    @Test
    void testHttpResponsePlainText() {
        String body = "{\"result\": \"success\"}";

        HttpResponse response = HttpResponse.builder().statusCode(200).body(body).build();

        assertTrue(response.isSuccessful());
        assertFalse(response.wasDecompressed());
        assertNull(response.getContentEncoding());
        assertEquals(body, response.getBody());
    }

    // ==================== OkHttpTransport Compression Integration Tests ====================

    @Test
    void testOkHttpTransportRequestCompression() throws Exception {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10))
                        .requestCompression(CompressionEncoding.GZIP)
                        .acceptEncoding(CompressionEncoding.GZIP)
                        .build();

        OkHttpTransport transport = new OkHttpTransport(config);

        try {
            mockServer.enqueue(
                    new MockResponse().setResponseCode(200).setBody("{\"response\": \"ok\"}"));

            String requestBody =
                    "{\"messages\": [{\"role\": \"user\", \"content\": \"Test message\"}]}";
            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/v1/chat").toString())
                            .method("POST")
                            .header("Content-Type", "application/json")
                            .body(requestBody)
                            .build();

            HttpResponse response = transport.execute(request);
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());

            RecordedRequest recorded = mockServer.takeRequest();
            assertEquals("gzip", recorded.getHeader("Content-Encoding"));
            assertEquals("gzip", recorded.getHeader("Accept-Encoding"));

            Buffer buffer = recorded.getBody();
            byte[] compressedBody = buffer.readByteArray();
            assertTrue(CompressionUtils.isGzipCompressed(compressedBody));

            String decompressed =
                    CompressionUtils.decompressToString(compressedBody, CompressionEncoding.GZIP);
            assertEquals(requestBody, decompressed);
        } finally {
            transport.close();
        }
    }

    @Test
    void testOkHttpTransportResponseDecompression() throws Exception {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10))
                        .acceptEncoding(CompressionEncoding.GZIP)
                        .autoDecompress(true)
                        .build();

        OkHttpTransport transport = new OkHttpTransport(config);

        try {
            String responseBody = "{\"result\": \"success\", \"data\": \"compressed response\"}";
            byte[] compressedBody =
                    CompressionUtils.compress(responseBody, CompressionEncoding.GZIP);

            mockServer.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setHeader("Content-Encoding", "gzip")
                            .setBody(new Buffer().write(compressedBody)));

            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/v1/response").toString())
                            .method("GET")
                            .build();

            HttpResponse response = transport.execute(request);
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.wasDecompressed());
            assertEquals(responseBody, response.getBody());
        } finally {
            transport.close();
        }
    }

    @Test
    void testPreCompressedRequest() throws Exception {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10))
                        .build();

        OkHttpTransport transport = new OkHttpTransport(config);

        try {
            mockServer.enqueue(
                    new MockResponse().setResponseCode(200).setBody("{\"response\": \"ok\"}"));

            String requestBody =
                    "{\"messages\": [{\"role\": \"user\", \"content\": \"Pre-compressed\"}]}";

            HttpRequest request =
                    HttpRequest.builder()
                            .url(mockServer.url("/v1/chat").toString())
                            .method("POST")
                            .header("Content-Type", "application/json")
                            .body(requestBody)
                            .compressBody(CompressionEncoding.GZIP)
                            .build();

            HttpResponse response = transport.execute(request);
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());

            RecordedRequest recorded = mockServer.takeRequest();
            assertEquals("gzip", recorded.getHeader("Content-Encoding"));

            byte[] sentBody = recorded.getBody().readByteArray();
            assertTrue(CompressionUtils.isGzipCompressed(sentBody));

            String decompressed =
                    CompressionUtils.decompressToString(sentBody, CompressionEncoding.GZIP);
            assertEquals(requestBody, decompressed);
        } finally {
            transport.close();
        }
    }
}
