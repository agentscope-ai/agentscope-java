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
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    JdkHttpTransport() {
        this(HttpTransportConfig.defaults());
    }

    /**
     * Create a new JdkHttpTransport with custom configuration.
     *
     * @param config the transport configuration
     * @throws NullPointerException if config is null
     */
    JdkHttpTransport(HttpTransportConfig config) {
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

    private static HttpClient buildClient(HttpTransportConfig config) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .version(Version.HTTP_2)
                        .followRedirects(Redirect.NORMAL)
                        .connectTimeout(config.getConnectTimeout());

        if (config.isIgnoreSsl()) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                        null, new TrustManager[] {new TrustAllManager()}, new SecureRandom());
                builder.sslContext(sslContext);
                log.error(
                        "SSL certificate verification has been disabled for this WebSocket client."
                            + " This configuration must only be used for local development or"
                            + " testing with self-signed certificates. Do not disable SSL"
                            + " verification in production environments, as it exposes connections"
                            + " to man-in-the-middle attacks.");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new HttpTransportException("Failed to create insecure SSL context", e);
            }
        }

        // Configure proxy
        if (config.getProxyConfig() != null) {
            ProxyConfig proxyConfig = config.getProxyConfig();

            if (proxyConfig.getNonProxyHosts() != null
                    && !proxyConfig.getNonProxyHosts().isEmpty()) {
                builder.proxy(new NonProxyHostsSelector(proxyConfig));
            } else {
                builder.proxy(
                        ProxySelector.of(
                                new InetSocketAddress(
                                        proxyConfig.getHost(), proxyConfig.getPort())));
            }

            // Note: JDK HttpClient does not support SOCKS5 authentication directly.
            // For HTTP proxy authentication, use Authenticator.
            if (proxyConfig.hasAuthentication() && proxyConfig.getType() == ProxyType.HTTP) {
                final String username = proxyConfig.getUsername();
                final String password = proxyConfig.getPassword();
                builder.authenticator(
                        new java.net.Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                if (getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication(
                                            username, password.toCharArray());
                                }
                                return null;
                            }
                        });
            }
        }

        return builder.build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        if (closed.get()) {
            throw new HttpTransportException("Transport has been closed");
        }

        var jdkRequest = buildJdkRequest(request);

        try {
            var response = client.send(jdkRequest, BodyHandlers.ofString());
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

        var jdkRequest = buildJdkRequest(request);

        // Check status code and read error body immediately when CompletableFuture completes
        // to avoid stream being closed before we can read it
        CompletableFuture<java.net.http.HttpResponse<InputStream>> future =
                client.sendAsync(jdkRequest, BodyHandlers.ofInputStream())
                        .thenApply(
                                response -> {
                                    int statusCode = response.statusCode();
                                    if (statusCode < 200 || statusCode >= 300) {
                                        // Read error body immediately while stream is still open
                                        String errorBody = readInputStream(response.body());
                                        log.warn(
                                                "HTTP request failed. URL: {} | Status: {} | Error:"
                                                        + " {}",
                                                request.getUrl(),
                                                statusCode,
                                                errorBody);
                                        throw new CompletionException(
                                                new HttpTransportException(
                                                        "HTTP request failed with status "
                                                                + statusCode
                                                                + " | "
                                                                + errorBody,
                                                        statusCode,
                                                        errorBody));
                                    }
                                    return response;
                                });

        return Mono.fromCompletionStage(future)
                .flatMapMany(response -> processStreamResponse(response, request))
                .onErrorMap(
                        e -> !(e instanceof HttpTransportException),
                        e -> {
                            Throwable cause = e instanceof CompletionException ? e.getCause() : e;
                            if (cause instanceof HttpTransportException) {
                                return (HttpTransportException) cause;
                            }
                            return new HttpTransportException(
                                    "SSE/NDJSON stream failed: " + e.getMessage(), e);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<String> processStreamResponse(
            java.net.http.HttpResponse<InputStream> response, HttpRequest request) {
        InputStream inputStream = response.body();
        if (inputStream == null) {
            return Flux.empty();
        }

        // Check if the request has the NDJSON format header
        boolean isNdjson =
                TransportConstants.STREAM_FORMAT_NDJSON.equals(
                        request.getHeaders().get(TransportConstants.STREAM_FORMAT_HEADER));

        // Use Flux.using to manage resource lifecycle
        return Flux.using(
                () ->
                        new BufferedReader(
                                new InputStreamReader(inputStream, StandardCharsets.UTF_8)),
                reader -> isNdjson ? readNdJsonLines(reader) : readSseLines(reader),
                this::closeQuietly);
    }

    private Flux<String> readSseLines(BufferedReader reader) {
        return Flux.fromStream(reader.lines())
                .filter(line -> line.startsWith(SSE_DATA_PREFIX))
                .map(line -> line.substring(SSE_DATA_PREFIX.length()).trim())
                .takeWhile(data -> !SSE_DONE_MARKER.equals(data))
                .doOnNext(data -> log.debug("Received SSE data chunk"))
                .filter(data -> !data.isEmpty());
    }

    private Flux<String> readNdJsonLines(BufferedReader reader) {
        return Flux.fromStream(reader.lines())
                .doOnNext(line -> log.debug("Received NDJSON line"))
                .filter(line -> !line.isEmpty());
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
    HttpClient getClient() {
        return client;
    }

    /**
     * Get the transport configuration.
     *
     * @return the configuration
     */
    HttpTransportConfig getConfig() {
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

        var builder =
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

    /**
     * A TrustManager that trusts all certificates.
     *
     * <p><b>Warning:</b> This disables SSL certificate verification and should only be used for
     * testing or with trusted self-signed certificates.
     */
    private static class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Trust all client certificates
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trust all server certificates
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * ProxySelector that respects non-proxy hosts configuration.
     */
    private static class NonProxyHostsSelector extends ProxySelector {
        private final ProxyConfig proxyConfig;
        private final List<Proxy> proxyList;

        NonProxyHostsSelector(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            this.proxyList = Collections.singletonList(proxyConfig.toJavaProxy());
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null || uri.getHost() == null) {
                return proxyList;
            }
            if (proxyConfig.shouldBypass(uri.getHost())) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            return proxyList;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            log.warn("Proxy connection failed: uri={}, address={}", uri, sa, ioe);
        }
    }
}
