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
package io.agentscope.core.model.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Pure JDK implementation of the HttpTransport interface.
 *
 * <p>This implementation uses JDK's built-in HttpClient (java.net.http.HttpClient)
 * for HTTP communication and supports:
 * <ul>
 *   <li>Synchronous HTTP requests</li>
 *   <li>Server-Sent Events (SSE) streaming</li>
 *   <li>HTTP/2 with fallback to HTTP/1.1</li>
 *   <li>Connection pooling (built-in)</li>
 *   <li>Configurable timeouts</li>
 * </ul>
 *
 * <p>This implementation has no external dependencies beyond the JDK.
 */
public class JdkHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpTransport.class);
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    private final HttpClient client;
    private final HttpTransportConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a new JdkHttpTransport with default configuration.
     */
    public JdkHttpTransport() {
        this(HttpTransportConfig.defaults());
    }

    /**
     * Create a new JdkHttpTransport with custom configuration.
     *
     * @param config the transport configuration
     * @throws NullPointerException if config is null
     */
    public JdkHttpTransport(HttpTransportConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.client = buildClient(config);
    }

    /**
     * Create a new JdkHttpTransport with an existing HttpClient.
     *
     * <p>Use this constructor when you want to share an HttpClient instance
     * across multiple components.
     *
     * @param client the HttpClient to use
     * @param config the transport configuration (used for reference only)
     * @throws NullPointerException if client or config is null
     */
    public JdkHttpTransport(HttpClient client, HttpTransportConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    private HttpClient buildClient(HttpTransportConfig config) {
        return HttpClient.newBuilder()
                .version(Version.HTTP_2)
                .followRedirects(Redirect.NORMAL)
                .connectTimeout(config.getConnectTimeout())
                .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        if (closed.get()) {
            throw new HttpTransportException("Transport has been closed");
        }

        java.net.http.HttpRequest jdkRequest = buildJdkRequest(request);

        try {
            java.net.http.HttpResponse<String> response =
                    client.send(jdkRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
            return buildHttpResponse(response);
        } catch (IOException e) {
            throw new HttpTransportException("HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpTransportException("HTTP request interrupted", e);
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        if (closed.get()) {
            return Flux.error(new HttpTransportException("Transport has been closed"));
        }

        java.net.http.HttpRequest jdkRequest = buildJdkRequest(request);

        return Flux.<String>create(
                        sink -> {
                            InputStream inputStream = null;
                            BufferedReader reader = null;
                            try {
                                java.net.http.HttpResponse<InputStream> response =
                                        client.send(
                                                jdkRequest,
                                                java.net.http.HttpResponse.BodyHandlers
                                                        .ofInputStream());

                                int statusCode = response.statusCode();
                                if (statusCode < 200 || statusCode >= 300) {
                                    String errorBody = readInputStream(response.body());
                                    sink.error(
                                            new HttpTransportException(
                                                    "HTTP request failed with status " + statusCode,
                                                    statusCode,
                                                    errorBody));
                                    return;
                                }

                                inputStream = response.body();
                                if (inputStream == null) {
                                    sink.complete();
                                    return;
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

                                    if (line.isEmpty()) {
                                        continue;
                                    }

                                    if (line.startsWith(SSE_DATA_PREFIX)) {
                                        String data =
                                                line.substring(SSE_DATA_PREFIX.length()).trim();

                                        if (SSE_DONE_MARKER.equals(data)) {
                                            log.debug("Received SSE [DONE] marker");
                                            break;
                                        }

                                        if (!data.isEmpty()) {
                                            sink.next(data);
                                        }
                                    }
                                }

                                sink.complete();
                            } catch (IOException e) {
                                if (!sink.isCancelled()) {
                                    sink.error(
                                            new HttpTransportException(
                                                    "SSE stream read failed: " + e.getMessage(),
                                                    e));
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                if (!sink.isCancelled()) {
                                    sink.error(
                                            new HttpTransportException(
                                                    "SSE stream interrupted", e));
                                }
                            } finally {
                                // Close both resources to handle case where reader creation fails
                                closeQuietly(reader);
                                closeQuietly(inputStream);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        closed.set(true);
        // HttpClient does not require explicit cleanup - it manages its own resources
    }

    /**
     * Get the underlying HttpClient instance.
     *
     * <p>This can be useful for advanced configuration or debugging.
     *
     * @return the HttpClient
     */
    public HttpClient getClient() {
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

    /**
     * Check if this transport has been closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    private java.net.http.HttpRequest buildJdkRequest(HttpRequest request) {
        URI uri;
        try {
            uri = URI.create(request.getUrl());
        } catch (IllegalArgumentException e) {
            throw new HttpTransportException("Invalid URL: " + request.getUrl(), e);
        }

        java.net.http.HttpRequest.Builder builder =
                java.net.http.HttpRequest.newBuilder().uri(uri).timeout(config.getReadTimeout());

        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        String method = request.getMethod().toUpperCase();
        String body = request.getBody();

        switch (method) {
            case "GET":
                builder.GET();
                break;
            case "POST":
                builder.POST(bodyPublisher(body));
                break;
            case "PUT":
                builder.PUT(bodyPublisher(body));
                break;
            case "DELETE":
                builder.method("DELETE", bodyPublisher(body));
                break;
            default:
                builder.method(method, bodyPublisher(body));
        }

        return builder.build();
    }

    private java.net.http.HttpRequest.BodyPublisher bodyPublisher(String body) {
        return body != null
                ? java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : java.net.http.HttpRequest.BodyPublishers.noBody();
    }

    private HttpResponse buildHttpResponse(java.net.http.HttpResponse<String> response) {
        HttpResponse.Builder builder =
                HttpResponse.builder().statusCode(response.statusCode()).body(response.body());

        response.headers()
                .map()
                .forEach(
                        (name, values) -> {
                            if (!values.isEmpty()) {
                                builder.header(name, values.get(0));
                            }
                        });

        return builder.build();
    }

    private String readInputStream(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
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
     * Create a new builder for JdkHttpTransport.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for JdkHttpTransport.
     */
    public static class Builder {
        private HttpTransportConfig config = HttpTransportConfig.defaults();
        private HttpClient existingClient = null;

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
         * Use an existing HttpClient instance.
         *
         * <p>When set, the configuration will only be used for reference,
         * and the provided client will be used as-is.
         *
         * @param client the existing HttpClient
         * @return this builder
         */
        public Builder client(HttpClient client) {
            this.existingClient = client;
            return this;
        }

        /**
         * Build the JdkHttpTransport.
         *
         * @return a new JdkHttpTransport instance
         */
        public JdkHttpTransport build() {
            if (existingClient != null) {
                return new JdkHttpTransport(existingClient, config);
            }
            return new JdkHttpTransport(config);
        }
    }
}
