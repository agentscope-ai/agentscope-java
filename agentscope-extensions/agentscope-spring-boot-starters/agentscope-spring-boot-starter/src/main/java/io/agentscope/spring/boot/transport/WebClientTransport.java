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
package io.agentscope.spring.boot.transport;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.TransportConstants;
import io.agentscope.core.util.ExceptionUtils;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

/**
 * WebClient implementation of the HttpTransport interface.
 *
 * <p>This implementation uses WebClient for HTTP communication and supports:
 * <ul>
 *   <li>Synchronous HTTP requests</li>
 *   <li>Server-Sent Events (SSE) streaming</li>
 *   <li>HTTP/2 with fallback to HTTP/1.1</li>
 *   <li>Connection pooling (built-in)</li>
 *   <li>Configurable timeouts</li>
 *   <li>Configurable SSL ignore</li>
 *   <li>Configurable proxy</li>
 * </ul>
 *
 * <p>This implementation has no external dependencies beyond the JDK.
 */
public class WebClientTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(WebClientTransport.class);
    private static final String SSE_DONE_MARKER = "[DONE]";
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final WebClient client;
    private final HttpTransportConfig config;

    /**
     * Create a new instance for WebClientTransport.
     *
     * @param builder the WebClientTransport builder
     */
    protected WebClientTransport(WebClientTransport.Builder builder) {
        this.config = builder.config;
        this.client = builder.webClientBuilder.build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        if (closed.get()) {
            throw new HttpTransportException("Transport has been closed");
        }

        return buildWebClientRequest(request)
                .exchangeToMono(
                        response -> {
                            HttpResponse.Builder responseBuilder =
                                    HttpResponse.builder()
                                            .statusCode(response.statusCode().value())
                                            .headers(
                                                    response.headers()
                                                            .asHttpHeaders()
                                                            .toSingleValueMap());
                            return response.bodyToMono(String.class)
                                    .switchIfEmpty(Mono.just(""))
                                    .map(body -> responseBuilder.body(body).build());
                        })
                .onErrorMap(
                        e -> !(e instanceof HttpTransportException),
                        e -> {
                            Throwable cause =
                                    ExceptionUtils.getRootCause(e, HttpTransportException.class);
                            if (cause instanceof HttpTransportException) {
                                return cause;
                            }
                            return new HttpTransportException(
                                    "HTTP execute failed: " + e.getMessage(), e);
                        })
                .block(config.getReadTimeout());
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        if (closed.get()) {
            throw new HttpTransportException("Transport has been closed");
        }

        boolean isNdjson =
                TransportConstants.STREAM_FORMAT_NDJSON.equals(
                        request.getHeaders().get(TransportConstants.STREAM_FORMAT_HEADER));

        return buildWebClientRequest(request)
                .exchangeToFlux(
                        response -> {
                            if (response.statusCode().isError()) {
                                return response.bodyToMono(String.class)
                                        .flatMapMany(
                                                errorBody -> {
                                                    log.error(
                                                            "HTTP error: status={}, body={}",
                                                            response.statusCode(),
                                                            errorBody);
                                                    return Flux.error(
                                                            new HttpTransportException(
                                                                    "HTTP request failed with"
                                                                            + " status "
                                                                            + response.statusCode()
                                                                                    .value(),
                                                                    response.statusCode().value(),
                                                                    errorBody));
                                                });
                            }
                            return response.bodyToFlux(String.class).switchIfEmpty(Flux.just(""));
                        })
                .transform(flux -> isNdjson ? readNdJsonLines(flux) : readSseLines(flux))
                .onErrorMap(
                        e -> !(e instanceof HttpTransportException),
                        e -> {
                            Throwable cause =
                                    ExceptionUtils.getRootCause(e, HttpTransportException.class);
                            if (cause instanceof HttpTransportException) {
                                return cause;
                            }
                            return new HttpTransportException(
                                    "SSE/NDJSON stream failed: " + e.getMessage(), e);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private WebClient.RequestHeadersSpec<?> buildWebClientRequest(HttpRequest request) {
        Assert.notNull(request, "HttpRequest cannot be null");
        log.debug(
                "Executing HTTP request: method={}, url={}", request.getMethod(), request.getUrl());
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());

        WebClient.RequestBodySpec requestBodySpec =
                client.method(method)
                        .uri(request.getUrl())
                        .headers(
                                h -> {
                                    for (Map.Entry<String, String> e :
                                            request.getHeaders().entrySet()) {
                                        h.add(e.getKey(), e.getValue());
                                    }
                                });

        WebClient.RequestHeadersSpec<?> requestHeadersSpec;
        if (request.getBody() != null && !request.getBody().isEmpty()) {
            requestHeadersSpec = requestBodySpec.bodyValue(request.getBody());
        } else {
            requestHeadersSpec = requestBodySpec.body(BodyInserters.empty());
        }
        return requestHeadersSpec;
    }

    private Flux<String> readSseLines(Flux<String> stringFlux) {
        return stringFlux
                .takeWhile(data -> !SSE_DONE_MARKER.equals(data))
                .doOnNext(data -> log.debug("Received SSE data chunk: {}", data))
                .filter(data -> !data.isEmpty());
    }

    private Flux<String> readNdJsonLines(Flux<String> stringFlux) {
        return stringFlux
                .doOnNext(line -> log.debug("Received NDJSON line: {}", line))
                .filter(line -> !line.isEmpty());
    }

    @Override
    public void close() {
        closed.set(true);
        // WebClient does not require explicit cleanup - it is managed by Netty lifecycle.
    }

    /**
     * Get the WebClient instance used by this instance.
     *
     * @return the WebClient instance
     */
    public WebClient getClient() {
        return client;
    }

    /**
     * Get the configuration used by this instance.
     *
     * @return the configuration
     */
    public HttpTransportConfig getConfig() {
        return config;
    }

    /**
     * Mutate a new builder with the same configuration as this instance.
     *
     * @return a new Builder instance
     */
    public Builder mutate() {
        return new Builder().config(this.config).webClientBuilder(this.client.mutate());
    }

    /**
     * Create a new builder for WebClientTransport.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for WebClientTransport.
     */
    public static class Builder {

        private HttpTransportConfig config = HttpTransportConfig.defaults();
        private WebClient.Builder webClientBuilder = buildWebClientBuilder();

        public Builder() {}

        /**
         * Set the transport configuration.
         *
         * @param config the configuration
         * @return this builder
         */
        public Builder config(HttpTransportConfig config) {
            Assert.notNull(config, "HttpTransportConfig cannot be null");
            this.config = config;
            return this;
        }

        /**
         * Set the WebClient builder.
         *
         * @param webClientBuilder the WebClient builder
         * @return this builder
         */
        public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
            Assert.notNull(webClientBuilder, "WebClient.Builder cannot be null");
            this.webClientBuilder = webClientBuilder.clone();
            return this;
        }

        /**
         * Build the WebClientTransport.
         *
         * @return a new WebClientTransport instance
         */
        public WebClientTransport build() {
            return new WebClientTransport(this);
        }

        /**
         * Build a WebClient.Builder with the specified configuration.
         *
         * @return a new WebClient.Builder instance
         */
        private WebClient.Builder buildWebClientBuilder() {
            if (!(isClassPresent("reactor.netty.http.client.HttpClient")
                    && isClassPresent("reactor.netty.resources.ConnectionProvider"))) {
                return WebClient.builder();
            }

            // Build Reactor Netty HttpClient
            ConnectionProvider connectionProvider =
                    ConnectionProvider.builder("agentscope-connection-provider")
                            .maxConnections(config.getMaxConnections())
                            .pendingAcquireTimeout(config.getConnectTimeout())
                            .maxIdleTime(config.getMaxIdleTime())
                            .maxLifeTime(config.getKeepAliveDuration())
                            .evictInBackground(config.getEvictInBackground())
                            .build();

            HttpClient httpClient =
                    HttpClient.create(connectionProvider)
                            .keepAlive(true)
                            .compress(true)
                            .responseTimeout(config.getReadTimeout());

            // Configure SSL (optionally ignore certificate verification)
            httpClient = configureIgnoreSsl(httpClient);

            // Configure proxy
            httpClient = configureProxy(httpClient);

            return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
        }

        /**
         * Check if a class is present in the classpath.
         *
         * @param className the class name
         * @return true if the class is present, false otherwise
         */
        private boolean isClassPresent(String className) {
            return ClassUtils.isPresent(className, ClassUtils.getDefaultClassLoader());
        }

        /**
         * Convert to reactor proxy type
         *
         * @param type the proxy type
         * @return the reactor proxy type
         */
        private ProxyProvider.Proxy toReactorProxyType(ProxyType type) {
            return switch (type) {
                case HTTP -> ProxyProvider.Proxy.HTTP;
                case SOCKS4 -> ProxyProvider.Proxy.SOCKS4;
                case SOCKS5 -> ProxyProvider.Proxy.SOCKS5;
            };
        }

        /**
         * Configure ignore SSL
         *
         * @param httpClient the http client
         * @return the http client with ignore SSL configured
         */
        private HttpClient configureIgnoreSsl(HttpClient httpClient) {
            if (config.isIgnoreSsl()) {
                log.error(
                        "SSL certificate verification has been disabled for this Http client. This"
                            + " configuration must only be used for local development or testing"
                            + " with self-signed certificates. Do not disable SSL verification in"
                            + " production environments, as it exposes connections to"
                            + " man-in-the-middle attacks.");
                try {
                    SslContext sslContext =
                            SslContextBuilder.forClient()
                                    .trustManager(new TrustAllManager())
                                    .build();
                    httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
                } catch (Exception e) {
                    throw new HttpTransportException("Failed to create insecure SSL context", e);
                }
            }
            return httpClient;
        }

        /**
         * Configure proxy
         *
         * @param httpClient the http client
         * @return the http client with proxy configured
         */
        private HttpClient configureProxy(HttpClient httpClient) {
            if (config.getProxyConfig() != null) {
                httpClient =
                        httpClient.proxy(
                                spec -> {
                                    ProxyConfig proxyConfig = config.getProxyConfig();
                                    ProxyProvider.Builder builder =
                                            spec.type(toReactorProxyType(proxyConfig.getType()))
                                                    .host(proxyConfig.getHost())
                                                    .port(proxyConfig.getPort());

                                    Set<String> nonProxyHosts = proxyConfig.getNonProxyHosts();
                                    if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
                                        // Note: Reactor Netty does not support multiple non-proxy
                                        // hosts pattern
                                        String nonProxyPattern =
                                                nonProxyHosts.toArray(new String[0])[0];
                                        builder.nonProxyHosts(nonProxyPattern);
                                    }

                                    if (proxyConfig.hasAuthentication()
                                            && proxyConfig.getType() == ProxyType.HTTP) {
                                        builder.username(proxyConfig.getUsername())
                                                .password(s -> proxyConfig.getPassword());
                                    }
                                });
            }
            return httpClient;
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
    }
}
