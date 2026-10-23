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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import reactor.core.publisher.Mono;

/**
 * Generic external API document parsing reader that supports integration with any third-party
 * document parsing service.
 *
 * <p>This reader is designed to be highly abstract and configurable, adapting to different APIs
 * through functional interfaces:
 * <ul>
 *   <li>Supports custom HTTP request construction (headers, body, method, etc.)</li>
 *   <li>Supports custom response parsing (extracting markdown from JSON/XML/other formats)</li>
 *   <li>Supports polling for asynchronous task completion</li>
 *   <li>Supports file upload or URL passing</li>
 * </ul>
 *
 * <p>Example usage - Integrating with MinerU Tianshu:
 * <pre>{@code
 * ExternalApiReader reader = ExternalApiReader.builder()
 *     .requestBuilder((filePath, client) -> {
 *         // Build task submission request
 *         String json = String.format(
 *             "{\"file_path\":\"%s\",\"engine\":\"pipeline\"}",
 *             filePath
 *         );
 *         return new Request.Builder()
 *             .url("http://localhost:8000/api/v1/tasks/submit")
 *             .header("Content-Type", "application/json")
 *             .post(RequestBody.create(json, MediaType.get("application/json")))
 *             .build();
 *     })
 *     .responseParser((response, client) -> {
 *         // Extract task_id from response, then poll for results
 *         String responseBody = response.body().string();
 *         // Parse JSON and poll...
 *         return markdown;
 *     })
 *     .chunkSize(512)
 *     .splitStrategy(SplitStrategy.PARAGRAPH)
 *     .overlapSize(50)
 *     .supportedFormats("pdf", "docx", "pptx", "xlsx", "txt")  // Specify supported formats
 *     .build();
 *
 * List<Document> docs = reader.read(ReaderInput.fromString("path/to/file.pdf")).block();
 * }</pre>
 *
 * <p>Example usage - Integrating with other APIs:
 * <pre>{@code
 * ExternalApiReader reader = ExternalApiReader.builder()
 *     .requestBuilder((filePath, client) -> {
 *         // Custom request building logic
 *         byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
 *         RequestBody body = RequestBody.create(fileBytes, MediaType.get("application/octet-stream"));
 *         return new Request.Builder()
 *             .url("https://api.example.com/parse")
 *             .header("Authorization", "Bearer YOUR_TOKEN")
 *             .post(body)
 *             .build();
 *     })
 *     .responseParser((response, client) -> {
 *         // Custom response parsing logic
 *         return response.body().string();
 *     })
 *     .build();
 * }</pre>
 */
public class ExternalApiReader extends AbstractChunkingReader {

    private final RequestBuilder requestBuilder;
    private final ResponseParser responseParser;
    private final OkHttpClient httpClient;
    private final int maxRetries;
    private final Duration retryDelay;
    private final List<String> supportedFormats;

    /**
     * Private constructor, use Builder pattern to create instances.
     */
    private ExternalApiReader(Builder builder) {
        super(builder.chunkSize, builder.splitStrategy, builder.overlapSize);
        this.requestBuilder = builder.requestBuilder;
        this.responseParser = builder.responseParser;
        this.maxRetries = builder.maxRetries;
        this.retryDelay = builder.retryDelay;
        this.supportedFormats =
                builder.supportedFormats != null && !builder.supportedFormats.isEmpty()
                        ? Collections.unmodifiableList(new ArrayList<>(builder.supportedFormats))
                        : Collections.emptyList();

        // Build OkHttpClient
        OkHttpClient.Builder clientBuilder =
                new OkHttpClient.Builder()
                        .connectTimeout(builder.connectTimeout)
                        .readTimeout(builder.readTimeout)
                        .writeTimeout(builder.writeTimeout);

        // Add custom interceptors
        if (builder.interceptors != null) {
            builder.interceptors.forEach(clientBuilder::addInterceptor);
        }

        this.httpClient = clientBuilder.build();
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            String filePath = input.asString();
                            validateFile(filePath);

                            // 1. Call external API to get markdown
                            String markdown = callExternalApi(filePath);

                            // 2. Use existing chunking logic to process markdown
                            List<String> chunks =
                                    TextChunker.chunkText(
                                            markdown, chunkSize, splitStrategy, overlapSize);

                            // 3. Create Document objects
                            return createDocuments(chunks, filePath);
                        })
                .onErrorMap(
                        throwable -> {
                            if (throwable instanceof ReaderException) {
                                return throwable;
                            }
                            return new ReaderException(
                                    "Failed to read document via external API", throwable);
                        });
    }

    @Override
    public List<String> getSupportedFormats() {
        // Return configured formats, or empty list if not specified
        // Empty list means the reader will attempt to process any file format
        return supportedFormats;
    }

    /**
     * Calls external API for document parsing.
     *
     * @param filePath File path
     * @return Parsed markdown text
     * @throws ReaderException if API call fails
     */
    private String callExternalApi(String filePath) throws ReaderException {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // Use custom requestBuilder to build request
                Request request = requestBuilder.buildRequest(filePath, httpClient);

                // Send request
                try (Response response = httpClient.newCall(request).execute()) {
                    // Check response status
                    if (response.isSuccessful()) {
                        // Use custom responseParser to parse response
                        return responseParser.parseResponse(response, httpClient);
                    } else {
                        String errorBody =
                                response.body() != null
                                        ? response.body().string()
                                        : "No response body";
                        throw new ReaderException(
                                "API returned error status: "
                                        + response.code()
                                        + ", body: "
                                        + errorBody);
                    }
                }

            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ReaderException("Request interrupted during retry", ie);
                    }
                }
            }
        }

        throw new ReaderException(
                "Failed to call external API after " + (maxRetries + 1) + " attempts",
                lastException);
    }

    /**
     * Validates if file exists.
     *
     * @param filePath The file path to validate
     * @throws ReaderException if file does not exist
     */
    private void validateFile(String filePath) throws ReaderException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new ReaderException("File does not exist: " + filePath);
        }
    }

    /**
     * Creates list of Document objects.
     */
    private List<Document> createDocuments(List<String> chunks, String filePath) {
        String docId = ReaderUtils.generateDocIdSHA256(filePath);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }

    /**
     * Creates Builder instance.
     *
     * @return A new {@link Builder} instance for constructing {@link ExternalApiReader}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Request builder functional interface.
     * Allows users to customize how to build HTTP requests.
     */
    @FunctionalInterface
    public interface RequestBuilder {
        /**
         * Builds HTTP request based on file path.
         *
         * @param filePath File path
         * @param httpClient OkHttpClient instance (can be used for complex scenarios like file upload)
         * @return Built OkHttp Request
         * @throws Exception if building fails
         */
        Request buildRequest(String filePath, OkHttpClient httpClient) throws Exception;
    }

    /**
     * Response parser functional interface.
     * Allows users to customize how to extract markdown from API response.
     */
    @FunctionalInterface
    public interface ResponseParser {
        /**
         * Parses markdown text from HTTP response.
         *
         * @param response OkHttp Response object
         * @param httpClient OkHttpClient instance (can be used for subsequent requests like polling task status)
         * @return Parsed markdown text
         * @throws Exception if parsing fails
         */
        String parseResponse(Response response, OkHttpClient httpClient) throws Exception;
    }

    /**
     * Builder class for constructing ExternalApiReader instances.
     */
    public static class Builder {
        private RequestBuilder requestBuilder;
        private ResponseParser responseParser;
        private int chunkSize = 512;
        private SplitStrategy splitStrategy = SplitStrategy.PARAGRAPH;
        private int overlapSize = 50;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofMinutes(5);
        private Duration writeTimeout = Duration.ofMinutes(5);
        private int maxRetries = 3;
        private Duration retryDelay = Duration.ofSeconds(2);
        private List<okhttp3.Interceptor> interceptors;
        private List<String> supportedFormats;

        /**
         * Sets the request builder.
         */
        public Builder requestBuilder(RequestBuilder requestBuilder) {
            this.requestBuilder = requestBuilder;
            return this;
        }

        /**
         * Sets the response parser.
         */
        public Builder responseParser(ResponseParser responseParser) {
            this.responseParser = responseParser;
            return this;
        }

        /**
         * Sets the chunk size.
         */
        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        /**
         * Sets the split strategy.
         */
        public Builder splitStrategy(SplitStrategy splitStrategy) {
            this.splitStrategy = splitStrategy;
            return this;
        }

        /**
         * Sets the overlap size.
         */
        public Builder overlapSize(int overlapSize) {
            this.overlapSize = overlapSize;
            return this;
        }

        /**
         * Sets the connection timeout.
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Sets the read timeout.
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Sets the write timeout.
         */
        public Builder writeTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
            return this;
        }

        /**
         * Sets the maximum number of retries.
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the retry delay.
         */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        /**
         * Adds an OkHttp interceptor (for logging, authentication, etc.).
         */
        public Builder addInterceptor(okhttp3.Interceptor interceptor) {
            if (this.interceptors == null) {
                this.interceptors = new ArrayList<>();
            }
            this.interceptors.add(interceptor);
            return this;
        }

        /**
         * Sets the supported file formats.
         *
         * <p>If not set or empty, the reader will attempt to process any file format,
         * relying on the external API to handle format validation.
         *
         * @param formats List of supported file extensions (e.g., "pdf", "docx", "txt")
         * @return this builder
         */
        public Builder supportedFormats(List<String> formats) {
            this.supportedFormats = formats;
            return this;
        }

        /**
         * Sets the supported file formats (varargs version).
         *
         * <p>If not set or empty, the reader will attempt to process any file format,
         * relying on the external API to handle format validation.
         *
         * @param formats Supported file extensions (e.g., "pdf", "docx", "txt")
         * @return this builder
         */
        public Builder supportedFormats(String... formats) {
            this.supportedFormats = Arrays.asList(formats);
            return this;
        }

        /**
         * Builds the ExternalApiReader instance.
         */
        public ExternalApiReader build() {
            if (requestBuilder == null) {
                throw new IllegalArgumentException("Request builder cannot be null");
            }
            if (responseParser == null) {
                throw new IllegalArgumentException("Response parser cannot be null");
            }
            return new ExternalApiReader(this);
        }
    }
}
