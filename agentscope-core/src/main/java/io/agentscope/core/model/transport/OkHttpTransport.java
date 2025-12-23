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
 * OkHttp implementation of the HttpTransport interface.
 *
 * <p>This implementation uses OkHttp for HTTP communication and supports:
 * <ul>
 *   <li>Synchronous HTTP requests</li>
 *   <li>Server-Sent Events (SSE) streaming</li>
 *   <li>Connection pooling</li>
 *   <li>Configurable timeouts</li>
 * </ul>
 */
public class OkHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(OkHttpTransport.class);
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    private final OkHttpClient client;
    private final HttpTransportConfig config;

    /**
     * Create a new OkHttpTransport with default configuration.
     */
    public OkHttpTransport() {
        this(HttpTransportConfig.defaults());
    }

    /**
     * Create a new OkHttpTransport with custom configuration.
     *
     * @param config the transport configuration
     */
    public OkHttpTransport(HttpTransportConfig config) {
        this.config = config;
        this.client = buildClient(config);
    }

    /**
     * Create a new OkHttpTransport with an existing OkHttpClient.
     *
     * <p>Use this constructor when you want to share an OkHttpClient instance
     * across multiple components.
     *
     * @param client the OkHttpClient to use
     * @param config the transport configuration (used for reference only)
     */
    public OkHttpTransport(OkHttpClient client, HttpTransportConfig config) {
        this.client = client;
        this.config = config;
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
                                        response.header(HttpResponse.HEADER_CONTENT_ENCODING);
                                if (contentEncodingHeader == null) {
                                    contentEncodingHeader = response.header(HttpResponse.HEADER_CONTENT_ENCODING.toLowerCase());
                                }

                                if (config.isAutoDecompress() && contentEncodingHeader != null) {
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
                .publishOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * Get the underlying OkHttpClient instance.
     *
     * <p>This can be useful for advanced configuration or debugging.
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
    public HttpTransportConfig getConfig() {
        return config;
    }

    private Request buildOkHttpRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.getUrl());

        // Add headers from request
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }

        // Add Accept-Encoding header from config if not already set
        if (config.isAcceptEncodingEnabled()
                && !request.getHeaders().containsKey(HttpRequest.HEADER_ACCEPT_ENCODING)) {
            builder.addHeader(
                    HttpRequest.HEADER_ACCEPT_ENCODING,
                    config.getAcceptEncoding().getHeaderValue());
        }

        // Add Content-Encoding header if compressing request body
        if (config.isRequestCompressionEnabled()
                && request.getBody() != null
                && !request.isCompressed()
                && !request.getHeaders().containsKey(HttpRequest.HEADER_CONTENT_ENCODING)) {
            builder.addHeader(
                    HttpRequest.HEADER_CONTENT_ENCODING,
                    config.getRequestCompression().getHeaderValue());
        }

        // Set method and body
        String method = request.getMethod().toUpperCase();

        // Determine the request body
        RequestBody requestBody = buildRequestBody(request);

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
     * Build the request body, applying compression from config if needed.
     */
    private RequestBody buildRequestBody(HttpRequest request) {
        // Check if request already has compressed body bytes
        if (request.hasBodyBytes()) {
            byte[] bodyBytes = request.getBodyBytes();
            return RequestBody.create(bodyBytes, JSON_MEDIA_TYPE);
        }

        String body = request.getBody();
        if (body == null) {
            return null;
        }

        // Apply compression from config if enabled and not already compressed
        if (config.isRequestCompressionEnabled() && !request.isCompressed()) {
            byte[] compressed = CompressionUtils.compress(body, config.getRequestCompression());
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
        String contentEncodingHeader = response.header(HttpResponse.HEADER_CONTENT_ENCODING);
        if (contentEncodingHeader == null) {
            // Try lowercase
            contentEncodingHeader = response.header(HttpResponse.HEADER_CONTENT_ENCODING.toLowerCase());
        }

        CompressionEncoding contentEncoding =
                CompressionEncoding.fromHeaderValue(contentEncodingHeader);
        builder.contentEncoding(contentEncoding);

        // Read response body
        ResponseBody responseBody = response.body();
        if (responseBody != null) {
            // Check if we need to handle decompression
            if (config.isAutoDecompress()
                    && contentEncoding != null
                    && contentEncoding != CompressionEncoding.NONE) {
                // Read raw bytes and let HttpResponse handle decompression
                byte[] bodyBytes = responseBody.bytes();
                builder.bodyBytes(bodyBytes);
                builder.autoDecompress(true);
            } else {
                // No compression or auto-decompress disabled, read as string
                builder.body(responseBody.string());
                builder.autoDecompress(false);
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
     * Create a new builder for OkHttpTransport.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OkHttpTransport.
     */
    public static class Builder {
        private HttpTransportConfig config = HttpTransportConfig.defaults();
        private OkHttpClient existingClient = null;

        /**
         * Set the transport configuration.
         *
         * @param config the configuration
         * @return this builder
         */
        public Builder config(HttpTransportConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Use an existing OkHttpClient instance.
         *
         * <p>When set, the configuration will only be used for reference,
         * and the provided client will be used as-is.
         *
         * @param client the existing OkHttpClient
         * @return this builder
         */
        public Builder client(OkHttpClient client) {
            this.existingClient = client;
            return this;
        }

        /**
         * Build the OkHttpTransport.
         *
         * @return a new OkHttpTransport instance
         */
        public OkHttpTransport build() {
            if (existingClient != null) {
                return new OkHttpTransport(existingClient, config);
            }
            return new OkHttpTransport(config);
        }
    }
}
