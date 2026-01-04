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

/**
 * MinerU Tianshu API integration example.
 *
 * <p>MinerU Tianshu is an enterprise-grade document parsing service that supports converting
 * PDF/Office documents to Markdown format.
 * Project URL: https://github.com/magicyuan876/mineru-tianshu
 *
 * <p>Key features:
 * <ul>
 *   <li>Supports multiple document formats: PDF, DOCX, PPTX, XLSX, etc.</li>
 *   <li>Supports multiple parsing engines: pipeline, vlm-transformers, vlm-vllm-engine, paddleocr-vl</li>
 *   <li>Supports OCR recognition for 109+ languages</li>
 *   <li>Supports formula, table, and image recognition</li>
 *   <li>Asynchronous task processing with support for large files</li>
 * </ul>
 *
 * <p>Authentication:
 * <ul>
 *   <li>Uses X-API-Key request header to pass API key</li>
 *   <li>Example: X-API-Key: your-api-key-here</li>
 * </ul>
 *
 * <p>API endpoints:
 * <ul>
 *   <li>POST /api/v1/tasks/submit - Submit parsing task (multipart/form-data)</li>
 *   <li>GET /api/v1/tasks/{task_id} - Query task status</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This is an example class, not a test class.
 * It will not be executed during CI/CD test runs.
 */
public final class MinerUTianshuReaderExample {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Private constructor to prevent instantiation
    private MinerUTianshuReaderExample() {
        throw new UnsupportedOperationException(
                "This is an example class and cannot be instantiated");
    }

    /**
     * Creates a basic MinerU Tianshu Reader.
     *
     * <p>Uses default configuration:
     * <ul>
     *   <li>API URL: http://localhost:8000</li>
     *   <li>Parsing engine: pipeline</li>
     *   <li>Parsing method: auto</li>
     *   <li>No authentication</li>
     * </ul>
     *
     * @return ExternalApiReader instance
     */
    public static ExternalApiReader createBasicReader() {
        return createReader("http://localhost:8000", null, "pipeline", "auto", null);
    }

    /**
     * Creates a MinerU Tianshu Reader with authentication.
     *
     * @param apiUrl API base URL (e.g., http://localhost:8000)
     * @param apiKey API key, passed via X-API-Key request header
     * @return ExternalApiReader instance
     */
    public static ExternalApiReader createReaderWithAuth(String apiUrl, String apiKey) {
        return createReader(apiUrl, apiKey, "pipeline", "auto", null);
    }

    /**
     * Creates a fully configured MinerU Tianshu Reader.
     *
     * @param apiUrl API base URL (e.g., http://localhost:8000)
     * @param apiKey API key, can be null
     * @param engine Parsing engine:
     *               - "pipeline": MinerU standard pipeline (recommended)
     *               - "vlm-transformers": VLM mode (using transformers)
     *               - "vlm-vllm-engine": VLM mode (using vllm)
     *               - "paddleocr-vl": PaddleOCR engine (109+ languages)
     * @param parseMethod Parsing method:
     *                    - "auto": Auto-select (recommended)
     *                    - "ocr": Force OCR
     *                    - "txt": Text extraction
     * @param languages Language list, e.g., "zh,en", can be null to use default
     * @return ExternalApiReader instance
     */
    public static ExternalApiReader createReader(
            String apiUrl, String apiKey, String engine, String parseMethod, String languages) {

        return ExternalApiReader.builder()
                // Build multipart file upload request
                .requestBuilder(
                        (filePath, client) -> {
                            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                            java.io.File file = path.toFile();

                            // Create file RequestBody
                            RequestBody fileBody =
                                    RequestBody.create(
                                            file, MediaType.get("application/octet-stream"));

                            // Build multipart request body
                            MultipartBody.Builder multipartBuilder =
                                    new MultipartBody.Builder()
                                            .setType(MultipartBody.FORM)
                                            .addFormDataPart("file", file.getName(), fileBody)
                                            .addFormDataPart(
                                                    "engine", engine != null ? engine : "pipeline")
                                            .addFormDataPart(
                                                    "parse_method",
                                                    parseMethod != null ? parseMethod : "auto");

                            // Add optional language parameter
                            if (languages != null && !languages.isEmpty()) {
                                multipartBuilder.addFormDataPart("languages", languages);
                            }

                            RequestBody requestBody = multipartBuilder.build();

                            // Build HTTP request
                            Request.Builder requestBuilder =
                                    new Request.Builder()
                                            .url(apiUrl + "/api/v1/tasks/submit")
                                            .post(requestBody);

                            // Add authentication header - use X-API-Key
                            if (apiKey != null && !apiKey.isEmpty()) {
                                requestBuilder.header("X-API-Key", apiKey);
                            }

                            return requestBuilder.build();
                        })
                // Parse response and poll task status
                .responseParser(
                        (response, client) -> {
                            String responseBody = response.body().string();
                            JsonNode jsonResponse = objectMapper.readTree(responseBody);

                            // Get task_id
                            String taskId = jsonResponse.get("task_id").asText();

                            // Poll task status until complete
                            return pollTaskUntilComplete(taskId, apiUrl, apiKey, client);
                        })
                // Chunking configuration
                .chunkSize(512)
                .splitStrategy(SplitStrategy.PARAGRAPH)
                .overlapSize(50)
                // Retry configuration
                .maxRetries(3)
                .retryDelay(Duration.ofSeconds(2))
                // Timeout configuration (larger files need more time)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(10))
                .writeTimeout(Duration.ofMinutes(5))
                // Supported formats (based on MinerU Tianshu capabilities)
                .supportedFormats(
                        "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "txt", "md", "html",
                        "png", "jpg", "jpeg")
                .build();
    }

    /**
     * Polls MinerU Tianshu task status until completion.
     *
     * @param taskId Task ID
     * @param apiUrl API base URL
     * @param apiKey API key, can be null
     * @param client OkHttpClient instance
     * @return Parsed markdown text
     * @throws Exception if task fails or times out
     */
    private static String pollTaskUntilComplete(
            String taskId, String apiUrl, String apiKey, okhttp3.OkHttpClient client)
            throws Exception {

        // Build status query request
        Request.Builder requestBuilder =
                new Request.Builder().url(apiUrl + "/api/v1/tasks/" + taskId).get();

        // Add authentication header - use X-API-Key
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        Request statusRequest = requestBuilder.build();

        // Poll for up to 5 minutes (150 attempts * 2 seconds)
        int maxAttempts = 150;
        for (int i = 0; i < maxAttempts; i++) {
            try (Response statusResponse = client.newCall(statusRequest).execute()) {
                if (!statusResponse.isSuccessful()) {
                    throw new RuntimeException(
                            "Failed to query task status: " + statusResponse.code());
                }

                String statusBody = statusResponse.body().string();
                JsonNode statusJson = objectMapper.readTree(statusBody);
                String status = statusJson.get("status").asText();

                if ("completed".equals(status)) {
                    // Task completed, return markdown result
                    JsonNode result = statusJson.get("result");
                    if (result != null && result.has("markdown")) {
                        return result.get("markdown").asText();
                    } else {
                        throw new RuntimeException("No markdown result in response");
                    }
                } else if ("failed".equals(status)) {
                    // Task failed
                    String error =
                            statusJson.has("error")
                                    ? statusJson.get("error").asText()
                                    : "Unknown error";
                    throw new RuntimeException("Task failed: " + error);
                }

                // Wait 2 seconds before retry
                Thread.sleep(2000);
            }
        }

        throw new RuntimeException("Task timeout after 5 minutes");
    }

    /**
     * Usage example - Basic usage.
     */
    public static void exampleBasicUsage() throws Exception {
        // Create reader
        ExternalApiReader reader = createBasicReader();

        // Read document
        ReaderInput input = ReaderInput.fromString("/path/to/document.pdf");
        List<Document> documents = reader.read(input).block();

        // Process results
        System.out.println("Parsed " + documents.size() + " document chunks");
        documents.forEach(
                doc -> {
                    System.out.println("DocID: " + doc.getMetadata().getDocId());
                    System.out.println("ChunkID: " + doc.getMetadata().getChunkId());
                    String text = doc.getMetadata().getContentText();
                    String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                    System.out.println("Content: " + preview);
                    System.out.println("---");
                });
    }

    /**
     * Usage example - With authentication.
     */
    public static void exampleWithAuthentication() throws Exception {
        String apiUrl = "http://localhost:8000";
        String apiKey = "your-api-key-here";

        ExternalApiReader reader = createReaderWithAuth(apiUrl, apiKey);

        ReaderInput input = ReaderInput.fromString("/path/to/document.pdf");
        List<Document> documents = reader.read(input).block();

        System.out.println("Successfully parsed " + documents.size() + " document chunks");
    }

    /**
     * Usage example - Advanced configuration.
     */
    public static void exampleAdvancedConfiguration() throws Exception {
        String apiUrl = "http://localhost:8000";
        String apiKey = "your-api-key-here";

        // Use VLM engine with Chinese and English support
        ExternalApiReader reader =
                createReader(
                        apiUrl,
                        apiKey,
                        "vlm-transformers", // Use VLM engine
                        "auto", // Auto-select parsing method
                        "zh,en" // Support Chinese and English
                        );

        ReaderInput input = ReaderInput.fromString("/path/to/document.pdf");
        List<Document> documents = reader.read(input).block();

        System.out.println("Parsed " + documents.size() + " chunks using VLM engine");
    }

    /**
     * Usage example - PaddleOCR engine (supports 109+ languages).
     */
    public static void examplePaddleOCR() throws Exception {
        ExternalApiReader reader =
                createReader(
                        "http://localhost:8000",
                        null,
                        "paddleocr-vl", // PaddleOCR engine
                        "ocr", // Force OCR
                        "zh,en,ja,ko" // Support Chinese, English, Japanese, Korean
                        );

        ReaderInput input = ReaderInput.fromString("/path/to/multilingual.pdf");
        List<Document> documents = reader.read(input).block();

        System.out.println("Parsed " + documents.size() + " chunks using PaddleOCR");
    }

    /**
     * Usage example - Batch processing multiple documents.
     */
    public static void exampleBatchProcessing() throws Exception {
        ExternalApiReader reader = createBasicReader();

        String[] filePaths = {"/path/to/doc1.pdf", "/path/to/doc2.docx", "/path/to/doc3.pptx"};

        for (String filePath : filePaths) {
            try {
                ReaderInput input = ReaderInput.fromString(filePath);
                List<Document> documents = reader.read(input).block();
                System.out.println(filePath + " -> " + documents.size() + " chunks");
            } catch (Exception e) {
                System.err.println("Failed to parse: " + filePath + " - " + e.getMessage());
            }
        }
    }

    /**
     * Main method - Run examples.
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== MinerU Tianshu Reader Usage Examples ===\n");

        // Choose which example to run
        // exampleBasicUsage();
        // exampleWithAuthentication();
        // exampleAdvancedConfiguration();
        // examplePaddleOCR();
        // exampleBatchProcessing();

        System.out.println("\nNote: Please ensure MinerU Tianshu service is running");
        System.out.println("Start command: docker-compose up or python backend/start_all.py");
        System.out.println("API docs: http://localhost:8000/docs");
    }
}
