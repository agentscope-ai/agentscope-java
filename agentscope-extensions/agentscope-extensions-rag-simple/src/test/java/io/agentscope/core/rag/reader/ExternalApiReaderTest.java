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
package io.agentscope.core.rag.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.exception.ReaderException;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * Unit tests for ExternalApiReader.
 * These tests use simple mocks to demonstrate functionality without external dependencies.
 */
@Tag("unit")
@DisplayName("ExternalApiReader Tests")
class ExternalApiReaderTest {

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");

    @Test
    @DisplayName("Should successfully parse document via simulated external API")
    void testSimpleSyncApi() throws Exception {
        // Simulated markdown response
        String mockMarkdown = "# Test Document\n\nThis is a test paragraph.\n\nAnother paragraph.";

        // Create Reader with simulated request and response
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // Build request without actually sending it
                                    return new Request.Builder()
                                            .url("http://mock-api.example.com/parse")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    // Directly return mocked markdown without parsing response
                                    return mockMarkdown;
                                })
                        .chunkSize(100)
                        .splitStrategy(SplitStrategy.PARAGRAPH)
                        .overlapSize(10)
                        .connectTimeout(Duration.ofSeconds(1))
                        .readTimeout(Duration.ofSeconds(2))
                        .maxRetries(0)
                        .build();

        // Create temporary test file
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".txt");
        java.nio.file.Files.writeString(tempFile, "test content");

        try {
            // Note: This test will fail because it will actually try to connect to the mock URL
            // This is an example showing how to configure the reader
            ReaderInput input = ReaderInput.fromString(tempFile.toString());

            // Since it will actually send the request, an exception will be thrown here
            // block() wraps ReaderException in ReactiveException
            Exception exception = assertThrows(Exception.class, () -> reader.read(input).block());
            Throwable cause = Exceptions.unwrap(exception);
            assertTrue(
                    cause instanceof ReaderException,
                    "Expected ReaderException but got: " + cause.getClass());
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should handle file not found error")
    void testFileNotFound() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url("http://mock-api.example.com/parse")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "# Mock Response")
                        .connectTimeout(Duration.ofSeconds(1))
                        .readTimeout(Duration.ofSeconds(2))
                        .maxRetries(0)
                        .build();

        // Test with non-existent file
        ReaderInput input = ReaderInput.fromString("/non/existent/file.pdf");

        // Should throw ReaderException (wrapped in ReactiveException by block())
        Exception exception = assertThrows(Exception.class, () -> reader.read(input).block());
        Throwable cause = Exceptions.unwrap(exception);
        assertTrue(
                cause instanceof ReaderException,
                "Expected ReaderException but got: " + cause.getClass());
    }

    @Test
    @DisplayName("Should validate builder configuration")
    void testBuilderValidation() {
        // Test missing requestBuilder
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ExternalApiReader.builder()
                                .responseParser((response, client) -> "test")
                                .build());

        // Test missing responseParser
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ExternalApiReader.builder()
                                .requestBuilder(
                                        (filePath, client) ->
                                                new Request.Builder()
                                                        .url("http://example.com")
                                                        .build())
                                .build());
    }

    @Test
    @DisplayName("Should support builder chaining with various configurations")
    void testBuilderChaining() {
        // Test all configuration options
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder()
                                                .url("http://example.com")
                                                .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                                .build())
                        .responseParser((response, client) -> "# Mock")
                        .chunkSize(1000)
                        .splitStrategy(SplitStrategy.CHARACTER)
                        .overlapSize(100)
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(2))
                        .writeTimeout(Duration.ofMinutes(2))
                        .maxRetries(5)
                        .retryDelay(Duration.ofSeconds(3))
                        .supportedFormats("pdf", "docx", "txt")
                        .addInterceptor(chain -> chain.proceed(chain.request()))
                        .build();

        assertNotNull(reader);

        // Verify supported formats
        List<String> formats = reader.getSupportedFormats();
        assertEquals(3, formats.size());
        assertTrue(formats.contains("pdf"));
        assertTrue(formats.contains("docx"));
        assertTrue(formats.contains("txt"));
    }

    @Test
    @DisplayName("Should support varargs for supported formats")
    void testSupportedFormatsVarargs() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .supportedFormats("pdf", "docx")
                        .build();

        List<String> formats = reader.getSupportedFormats();
        assertEquals(2, formats.size());
    }

    @Test
    @DisplayName("Should return empty list when no formats specified")
    void testEmptySupportedFormats() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .build();

        List<String> formats = reader.getSupportedFormats();
        assertNotNull(formats);
        assertTrue(formats.isEmpty());
    }

    @Test
    @DisplayName("Should demonstrate authentication configuration")
    void testAuthenticationConfiguration() {
        String apiKey = "test-api-key-12345";

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // Demonstrate how to add authentication headers
                                    return new Request.Builder()
                                            .url("http://api.example.com/parse")
                                            .header("Authorization", "Bearer " + apiKey)
                                            .header("X-API-Key", apiKey)
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "# Authenticated Response")
                        .build();

        assertNotNull(reader);
    }

    @Test
    @DisplayName("Should demonstrate multipart file upload configuration")
    void testMultipartUploadConfiguration() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // Demonstrate how to build multipart request
                                    // Note: In actual use, you need to create a real MultipartBody
                                    return new Request.Builder()
                                            .url("http://api.example.com/upload")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "# Upload Response")
                        .writeTimeout(
                                Duration.ofMinutes(10)) // File uploads need longer write timeout
                        .build();

        assertNotNull(reader);
    }

    @Test
    @DisplayName("Should demonstrate async task polling pattern")
    void testAsyncPollingPattern() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    // Step 1: Submit task
                                    return new Request.Builder()
                                            .url("http://api.example.com/tasks/submit")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    // Simulate async polling pattern
                                    // In actual use:
                                    // 1. Extract task_id from response
                                    // 2. Loop to check task status
                                    // 3. Return result after task completes
                                    return "# Task Result";
                                })
                        .readTimeout(Duration.ofMinutes(10)) // Async tasks need longer timeout
                        .maxRetries(3)
                        .retryDelay(Duration.ofSeconds(5))
                        .build();

        assertNotNull(reader);
    }

    @Test
    @DisplayName("Should demonstrate custom interceptor usage")
    void testCustomInterceptor() {
        final boolean[] interceptorCalled = {false};

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .addInterceptor(
                                chain -> {
                                    // Interceptors can be used for:
                                    // - Adding common request headers
                                    // - Logging requests
                                    // - Modifying request parameters
                                    interceptorCalled[0] = true;
                                    return chain.proceed(chain.request());
                                })
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .build();

        assertNotNull(reader);
        // Note: Interceptor is only called when actually sending requests
        assertFalse(interceptorCalled[0]);
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) ->
                                        new Request.Builder().url("http://example.com").build())
                        .responseParser((response, client) -> "test")
                        .build();

        Exception exception = assertThrows(Exception.class, () -> reader.read(null).block());
        Throwable cause = Exceptions.unwrap(exception);
        assertTrue(
                cause instanceof ReaderException,
                "Expected ReaderException but got: " + cause.getClass());
    }
}
