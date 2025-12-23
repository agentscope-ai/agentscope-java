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
package io.agentscope.core.rag.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.rag.model.Document;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ExternalApiReader的单元测试和使用示例。
 */
@Tag("unit")
@DisplayName("ExternalApiReader Tests")
class ExternalApiReaderTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");

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

    @Test
    @DisplayName("Should successfully parse document via external API")
    void testSimpleSyncApi() throws Exception {
        // Mock API响应
        String mockMarkdown = "# Test Document\n\nThis is a test.";
        String mockResponse = String.format("{\"markdown\":\"%s\"}", mockMarkdown);
        mockServer.enqueue(
                new MockResponse()
                        .setBody(mockResponse)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString();

        // 创建Reader
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    String requestBody =
                                            String.format("{\"file_path\":\"%s\"}", filePath);
                                    return new Request.Builder()
                                            .url(baseUrl + "parse")
                                            .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    String body = response.body().string();
                                    JsonNode json = objectMapper.readTree(body);
                                    return json.get("markdown").asText();
                                })
                        .chunkSize(100)
                        .splitStrategy(SplitStrategy.PARAGRAPH)
                        .overlapSize(10)
                        .build();

        // 创建临时测试文件
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".txt");
        java.nio.file.Files.writeString(tempFile, "test content");

        try {
            // 执行解析
            ReaderInput input = ReaderInput.fromString(tempFile.toString());
            List<Document> documents = reader.read(input).block();

            // 验证结果
            assertNotNull(documents);
            assertFalse(documents.isEmpty());
            assertTrue(documents.get(0).getMetadata().getContentText().contains("Test Document"));

            // 验证请求
            RecordedRequest request = mockServer.takeRequest();
            assertEquals("POST", request.getMethod());
            assertTrue(request.getPath().contains("parse"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should handle API authentication")
    void testApiWithAuthentication() throws Exception {
        String mockResponse = "{\"markdown\":\"# Authenticated\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setBody(mockResponse)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString();
        String apiKey = "test-api-key";

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url(baseUrl + "parse")
                                            .header("Authorization", "Bearer " + apiKey)
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    String body = response.body().string();
                                    JsonNode json = objectMapper.readTree(body);
                                    return json.get("markdown").asText();
                                })
                        .build();

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".txt");
        java.nio.file.Files.writeString(tempFile, "test");

        try {
            ReaderInput input = ReaderInput.fromString(tempFile.toString());
            List<Document> documents = reader.read(input).block();

            assertNotNull(documents);

            // 验证认证头
            RecordedRequest request = mockServer.takeRequest();
            assertEquals("Bearer " + apiKey, request.getHeader("Authorization"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should handle multipart file upload")
    void testMultipartFileUpload() throws Exception {
        String mockResponse = "{\"markdown\":\"# Uploaded File\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setBody(mockResponse)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString();

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                                    java.io.File file = path.toFile();

                                    RequestBody fileBody =
                                            RequestBody.create(
                                                    file,
                                                    MediaType.get("application/octet-stream"));

                                    RequestBody multipartBody =
                                            new MultipartBody.Builder()
                                                    .setType(MultipartBody.FORM)
                                                    .addFormDataPart(
                                                            "file", file.getName(), fileBody)
                                                    .addFormDataPart("format", "markdown")
                                                    .build();

                                    return new Request.Builder()
                                            .url(baseUrl + "upload")
                                            .post(multipartBody)
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    String body = response.body().string();
                                    JsonNode json = objectMapper.readTree(body);
                                    return json.get("markdown").asText();
                                })
                        .build();

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".pdf");
        java.nio.file.Files.writeString(tempFile, "PDF content");

        try {
            ReaderInput input = ReaderInput.fromString(tempFile.toString());
            List<Document> documents = reader.read(input).block();

            assertNotNull(documents);

            // 验证是multipart请求
            RecordedRequest request = mockServer.takeRequest();
            assertTrue(request.getHeader("Content-Type").contains("multipart/form-data"));
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should handle async task polling")
    void testAsyncTaskPolling() throws Exception {
        String baseUrl = mockServer.url("/").toString();

        // Mock提交任务响应
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"task_id\":\"task-123\"}")
                        .setHeader("Content-Type", "application/json"));

        // Mock轮询响应 - 第一次pending
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"status\":\"pending\"}")
                        .setHeader("Content-Type", "application/json"));

        // Mock轮询响应 - 第二次completed
        mockServer.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"status\":\"completed\",\"result\":{\"markdown\":\"# Task"
                                        + " Result\"}}")
                        .setHeader("Content-Type", "application/json"));

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url(baseUrl + "submit")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    String body = response.body().string();
                                    JsonNode json = objectMapper.readTree(body);
                                    String taskId = json.get("task_id").asText();

                                    // 轮询任务状态
                                    for (int i = 0; i < 10; i++) {
                                        Request statusRequest =
                                                new Request.Builder()
                                                        .url(baseUrl + "status/" + taskId)
                                                        .get()
                                                        .build();

                                        try (Response statusResponse =
                                                client.newCall(statusRequest).execute()) {
                                            String statusBody = statusResponse.body().string();
                                            JsonNode statusJson = objectMapper.readTree(statusBody);
                                            String status = statusJson.get("status").asText();

                                            if ("completed".equals(status)) {
                                                return statusJson
                                                        .get("result")
                                                        .get("markdown")
                                                        .asText();
                                            }

                                            Thread.sleep(100); // 短暂等待
                                        }
                                    }
                                    throw new RuntimeException("Task timeout");
                                })
                        .build();

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".txt");
        java.nio.file.Files.writeString(tempFile, "test");

        try {
            ReaderInput input = ReaderInput.fromString(tempFile.toString());
            List<Document> documents = reader.read(input).block();

            assertNotNull(documents);
            assertTrue(documents.get(0).getMetadata().getContentText().contains("Task Result"));

            // 验证请求序列
            assertEquals("POST", mockServer.takeRequest().getMethod()); // submit
            assertEquals("GET", mockServer.takeRequest().getMethod()); // status check 1
            assertEquals("GET", mockServer.takeRequest().getMethod()); // status check 2
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should retry on failure")
    void testRetryOnFailure() throws Exception {
        String baseUrl = mockServer.url("/").toString();

        // 第一次失败
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        // 第二次成功
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"markdown\":\"# Success\"}")
                        .setHeader("Content-Type", "application/json"));

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url(baseUrl + "parse")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    String body = response.body().string();
                                    JsonNode json = objectMapper.readTree(body);
                                    return json.get("markdown").asText();
                                })
                        .maxRetries(2)
                        .retryDelay(Duration.ofMillis(100))
                        .build();

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".txt");
        java.nio.file.Files.writeString(tempFile, "test");

        try {
            ReaderInput input = ReaderInput.fromString(tempFile.toString());
            List<Document> documents = reader.read(input).block();

            assertNotNull(documents);
            assertEquals(2, mockServer.getRequestCount()); // 1次失败 + 1次成功
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("Should handle file not found")
    void testFileNotFound() {
        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url("http://example.com")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser((response, client) -> "")
                        .build();

        ReaderInput input = ReaderInput.fromString("/non/existent/file.pdf");

        assertThrows(Exception.class, () -> reader.read(input).block());
    }

    @Test
    @DisplayName("Should use custom interceptors")
    void testCustomInterceptors() throws Exception {
        String mockResponse = "{\"markdown\":\"# Test\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setBody(mockResponse)
                        .setHeader("Content-Type", "application/json"));

        String baseUrl = mockServer.url("/").toString();
        final boolean[] interceptorCalled = {false};

        ExternalApiReader reader =
                ExternalApiReader.builder()
                        .addInterceptor(
                                chain -> {
                                    interceptorCalled[0] = true;
                                    return chain.proceed(chain.request());
                                })
                        .requestBuilder(
                                (filePath, client) -> {
                                    return new Request.Builder()
                                            .url(baseUrl + "parse")
                                            .post(RequestBody.create("{}", JSON_MEDIA_TYPE))
                                            .build();
                                })
                        .responseParser(
                                (response, client) -> {
                                    String body = response.body().string();
                                    JsonNode json = objectMapper.readTree(body);
                                    return json.get("markdown").asText();
                                })
                        .build();

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".txt");
        java.nio.file.Files.writeString(tempFile, "test");

        try {
            ReaderInput input = ReaderInput.fromString(tempFile.toString());
            reader.read(input).block();

            assertTrue(interceptorCalled[0], "Interceptor should be called");
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }
}
