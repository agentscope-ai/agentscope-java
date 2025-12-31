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

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * OkHttp transport implementation with compression support.
 *
 * <p>This class extends the standard HTTP transport functionality with:
 * <ul>
 *   <li>Request body compression (GZIP, Brotli, Zstd)</li>
 *   <li>Response body decompression</li>
 *   <li>SSE stream decompression</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * CompressionConfig compressionConfig = CompressionConfig.builder()
 *     .enableGzipCompression()
 *     .build();
 *
 * CompressingOkHttpTransport transport = CompressingOkHttpTransport.builder()
 *     .compressionConfig(compressionConfig)
 *     .build();
 *
 * // Use with your model client
 * DashScopeChatModel model = DashScopeChatModel.builder()
 *     .apiKey("your-api-key")
 *     .httpTransport(transport)
 *     .build();
 * }</pre>
 */
public class CompressingOkHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(CompressingOkHttpTransport.class);
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    /** HTTP header name for content encoding. */
    public static final String HEADER_CONTENT_ENCODING = "Content-Encoding";

    /** HTTP header name for accepted encodings. */
    public static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";

    private final OkHttpClient client;
    private final HttpTransportConfig transportConfig;
    private final CompressionConfig compressionConfig;

    /**
     * Create a new CompressingOkHttpTransport with default configuration.
     */
    public CompressingOkHttpTransport() {
        this(HttpTransportConfig.defaults(), CompressionConfig.defaults());
    }

    /**
     * Create a new CompressingOkHttpTransport with custom configuration.
     *
     * @param transportConfig the transport configuration
     * @param compressionConfig the compression configuration
     */
    public CompressingOkHttpTransport(
            HttpTransportConfig transportConfig, CompressionConfig compressionConfig) {
        this.transportConfig = transportConfig;
        this.compressionConfig = compressionConfig;
        this.client = buildClient(transportConfig);
    }

    /**
     * Create a new CompressingOkHttpTransport with an existing OkHttpClient.
     *
     * @param client the OkHttpClient to use
     * @param transportConfig the transport configuration (used for reference only)
     * @param compressionConfig the compression configuration
     */
    public CompressingOkHttpTransport(
            OkHttpClient client,
            HttpTransportConfig transportConfig,
            CompressionConfig compressionConfig) {
        this.client = client;
        this.transportConfig = transportConfig;
        this.compressionConfig = compressionConfig;
    }

    private OkHttpClient buildClient(HttpTransportConfig config) {
        return new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getWriteTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .connectionPool(
                        new ConnectionPool(
                                config.getMaxIdleConnections(),
                                config.getKeepAliveDuration().toMillis(),
                                TimeUnit.MILLISECONDS))
                .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        Request okHttpRequest = buildOkHttpRequest(request);

        try (Response response = client.newCall(okHttpRequest).execute()) {
            return buildHttpResponse(response);
        } catch (IOException e) {
            throw new HttpTransportException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        Request okHttpRequest = buildOkHttpRequest(request);

        return Flux.<String>create(
                        sink -> {
                            Response response = null;
                            BufferedReader reader = null;
                            try {
                                response = client.newCall(okHttpRequest).execute();

                                if (!response.isSuccessful()) {
                                    String errorBody = getResponseBodyString(response);
                                    sink.error(
                                            new HttpTransportException(
                                                    "HTTP request failed with status "
                                                            + response.code(),
                                                    response.code(),
                                                    errorBody));
                                    return;
                                }

                                ResponseBody body = response.body();
                                if (body == null) {
                                    sink.complete();
                                    return;
                                }

                                // Handle compressed streams
                                InputStream inputStream = body.byteStream();
                                String contentEncodingHeader =
                                        response.header(HEADER_CONTENT_ENCODING);
                                if (contentEncodingHeader == null) {
                                    contentEncodingHeader =
                                            response.header(HEADER_CONTENT_ENCODING.toLowerCase());
                                }

                                if (compressionConfig.isAutoDecompress()
                                        && contentEncodingHeader != null) {
                                    CompressionEncoding encoding =
                                            CompressionEncoding.fromHeaderValue(
                                                    contentEncodingHeader);
                                    if (encoding != CompressionEncoding.NONE) {
                                        inputStream =
                                                CompressionUtils.decompressStream(
                                                        inputStream, encoding);
                                        log.debug("Decompressing SSE stream with {}", encoding);
                                    }
                                }

                                reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        inputStream, StandardCharsets.UTF_8));

                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (sink.isCancelled()) {
                                        break;
                                    }

                                    // Skip empty lines
                                    if (line.isEmpty()) {
                                        continue;
                                    }

                                    // Parse SSE data lines
                                    if (line.startsWith(SSE_DATA_PREFIX)) {
                                        String data =
                                                line.substring(SSE_DATA_PREFIX.length()).trim();

                                        // Check for stream end marker
                                        if (SSE_DONE_MARKER.equals(data)) {
                                            log.debug("Received SSE [DONE] marker");
                                            break;
                                        }

                                        if (!data.isEmpty()) {
                                            sink.next(data);
                                        }
                                    }
                                    // Skip other SSE fields (event:, id:, retry:, comments)
                                }

                                sink.complete();
                            } catch (IOException e) {
                                if (!sink.isCancelled()) {
                                    sink.error(
                                            new HttpTransportException(
                                                    "SSE stream read failed: " + e.getMessage(),
                                                    e));
                                }
                            } finally {
                                closeQuietly(reader);
                                if (response != null) {
                                    closeQuietly(response.body());
                                }
                                closeQuietly(response);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * Get the underlying OkHttpClient instance.
     *
     * @return the OkHttpClient
     */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * Get the transport configuration.
     *
     * @return the configuration
     */
    public HttpTransportConfig getTransportConfig() {
        return transportConfig;
    }

    /**
     * Get the compression configuration.
     *
     * @return the compression configuration
     */
    public CompressionConfig getCompressionConfig() {
        return compressionConfig;
    }

    private Request buildOkHttpRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.getUrl());

        // Add headers from request
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }

        // Add Accept-Encoding header from config if not already set
        if (compressionConfig.isAcceptEncodingEnabled()
                && !request.getHeaders().containsKey(HEADER_ACCEPT_ENCODING)) {
            builder.addHeader(
                    HEADER_ACCEPT_ENCODING, compressionConfig.getAcceptEncoding().getHeaderValue());
        }

        // Set method and body
        String method = request.getMethod().toUpperCase();
        String body = request.getBody();

        // Determine the request body - apply compression if enabled
        RequestBody requestBody = buildRequestBody(body);

        // Add Content-Encoding header if compressing request body
        if (compressionConfig.isRequestCompressionEnabled()
                && body != null
                && !request.getHeaders().containsKey(HEADER_CONTENT_ENCODING)) {
            builder.addHeader(
                    HEADER_CONTENT_ENCODING,
                    compressionConfig.getRequestCompression().getHeaderValue());
        }

        switch (method) {
            case "GET":
                builder.get();
                break;
            case "POST":
                builder.post(
                        requestBody != null
                                ? requestBody
                                : RequestBody.create("", JSON_MEDIA_TYPE));
                break;
            case "PUT":
                builder.put(
                        requestBody != null
                                ? requestBody
                                : RequestBody.create("", JSON_MEDIA_TYPE));
                break;
            case "DELETE":
                if (requestBody != null) {
                    builder.delete(requestBody);
                } else {
                    builder.delete();
                }
                break;
            default:
                builder.method(method, requestBody);
        }

        return builder.build();
    }

    /**
     * Build the request body, applying compression if enabled.
     */
    private RequestBody buildRequestBody(String body) {
        if (body == null) {
            return null;
        }

        // Apply compression from config if enabled
        if (compressionConfig.isRequestCompressionEnabled()) {
            byte[] compressed =
                    CompressionUtils.compress(body, compressionConfig.getRequestCompression());
            return RequestBody.create(compressed, JSON_MEDIA_TYPE);
        }

        return RequestBody.create(body, JSON_MEDIA_TYPE);
    }

    private HttpResponse buildHttpResponse(Response response) throws IOException {
        HttpResponse.Builder builder = HttpResponse.builder().statusCode(response.code());

        // Copy headers
        for (String name : response.headers().names()) {
            builder.header(name, response.header(name));
        }

        // Get content encoding from response headers
        String contentEncodingHeader = response.header(HEADER_CONTENT_ENCODING);
        if (contentEncodingHeader == null) {
            // Try lowercase
            contentEncodingHeader = response.header(HEADER_CONTENT_ENCODING.toLowerCase());
        }

        CompressionEncoding contentEncoding =
                CompressionEncoding.fromHeaderValue(contentEncodingHeader);

        // Read response body
        ResponseBody responseBody = response.body();
        if (responseBody != null) {
            // Check if we need to handle decompression
            if (compressionConfig.isAutoDecompress()
                    && contentEncoding != null
                    && contentEncoding != CompressionEncoding.NONE) {
                // Read raw bytes and decompress
                byte[] compressedBytes = responseBody.bytes();
                byte[] decompressedBytes =
                        CompressionUtils.decompress(compressedBytes, contentEncoding);
                String decompressedBody = new String(decompressedBytes, StandardCharsets.UTF_8);
                builder.body(decompressedBody);
                log.debug("Decompressed response body with {}", contentEncoding);
            } else {
                // No compression or auto-decompress disabled, read as string
                builder.body(responseBody.string());
            }
        }

        return builder.build();
    }

    private String getResponseBodyString(Response response) {
        try {
            ResponseBody body = response.body();
            return body != null ? body.string() : null;
        } catch (IOException e) {
            log.warn("Failed to read response body: {}", e.getMessage());
            return null;
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Error closing resource: {}", e.getMessage());
            }
        }
    }

    /**
     * Create a new builder for CompressingOkHttpTransport.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for CompressingOkHttpTransport.
     */
    public static class Builder {
        private HttpTransportConfig transportConfig = HttpTransportConfig.defaults();
        private CompressionConfig compressionConfig = CompressionConfig.defaults();
        private OkHttpClient existingClient = null;

        /**
         * Set the transport configuration.
         *
         * @param config the configuration
         * @return this builder
         */
        public Builder transportConfig(HttpTransportConfig config) {
            this.transportConfig = config;
            return this;
        }

        /**
         * Set the compression configuration.
         *
         * @param config the compression configuration
         * @return this builder
         */
        public Builder compressionConfig(CompressionConfig config) {
            this.compressionConfig = config;
            return this;
        }

        /**
         * Use an existing OkHttpClient instance.
         *
         * @param client the existing OkHttpClient
         * @return this builder
         */
        public Builder client(OkHttpClient client) {
            this.existingClient = client;
            return this;
        }

        /**
         * Enable GZIP compression.
         *
         * <p>This is a convenience method that enables GZIP compression for
         * both requests and responses.
         *
         * @return this builder
         */
        public Builder enableGzipCompression() {
            this.compressionConfig = CompressionConfig.gzip();
            return this;
        }

        /**
         * Build the CompressingOkHttpTransport.
         *
         * @return a new CompressingOkHttpTransport instance
         */
        public CompressingOkHttpTransport build() {
            if (existingClient != null) {
                return new CompressingOkHttpTransport(
                        existingClient, transportConfig, compressionConfig);
            }
            return new CompressingOkHttpTransport(transportConfig, compressionConfig);
        }
    }
}
