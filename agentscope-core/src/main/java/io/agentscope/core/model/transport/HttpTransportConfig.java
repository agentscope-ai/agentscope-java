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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for HTTP transport layer.
 *
 * <p>This class holds configuration options for HTTP client behavior such as
 * timeouts, connection pool settings, and retry policies.
 */
public class HttpTransportConfig {

    /** Default connect timeout: 30 seconds. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** Default read timeout: 5 minutes. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Default streaming response timeout: 5 minutes (request start to first emitted streaming
     * chunk).
     */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    /** Default streaming idle timeout: 5 minutes (maximum wait between data chunks). */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = DEFAULT_READ_TIMEOUT;

    /** Default write timeout: 30 seconds. */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    /** Default maximum number of characters logged per request/response body. */
    public static final int DEFAULT_LOG_BODY_MAX_LENGTH = 2048;

    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Duration streamIdleTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final int maxIdleConnections;
    private final Duration keepAliveDuration;
    private final boolean ignoreSsl;
    private final ProxyConfig proxyConfig;
    private final HttpVersion httpVersion;
    private final boolean logRequests;
    private final boolean logFailures;
    private final boolean logBodies;
    private final int logBodyMaxLength;
    private final List<HttpTransportListener> listeners;

    private HttpTransportConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.responseTimeout = builder.responseTimeout;
        this.streamIdleTimeout = builder.streamIdleTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.maxIdleConnections = builder.maxIdleConnections;
        this.keepAliveDuration = builder.keepAliveDuration;
        this.ignoreSsl = builder.ignoreSsl;
        this.proxyConfig = builder.proxyConfig;
        this.httpVersion = builder.httpVersion;
        this.logRequests = builder.logRequests;
        this.logFailures = builder.logFailures;
        this.logBodies = builder.logBodies;
        this.logBodyMaxLength = builder.logBodyMaxLength;
        this.listeners = List.copyOf(builder.listeners);
    }

    /**
     * Get the connect timeout.
     *
     * @return the connect timeout duration
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Get the response timeout for streaming requests.
     *
     * <p>For {@link JdkHttpTransport} streaming requests, this bounds the time from request start
     * until the first emitted SSE/NDJSON chunk. {@link OkHttpTransport} does not consume this
     * option; it relies on OkHttp's {@link #getReadTimeout()} for stream reads.
     *
     * @return the response timeout duration
     */
    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /**
     * Get the stream idle timeout.
     *
     * <p>For {@link JdkHttpTransport} streaming requests, this bounds the maximum wait between two
     * consecutive emitted SSE/NDJSON chunks. {@link OkHttpTransport} does not consume this option;
     * it relies on OkHttp's {@link #getReadTimeout()} for stream reads.
     *
     * @return the stream idle timeout duration
     */
    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    /**
     * Get the read timeout.
     *
     * <p>{@link OkHttpTransport} applies this as OkHttp's read timeout for both standard and
     * streaming requests. {@link JdkHttpTransport} applies this to non-streaming requests only; JDK
     * streaming requests use {@link #getResponseTimeout()} and {@link #getStreamIdleTimeout()}
     * instead.
     *
     * @return the read timeout duration
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * Get the write timeout.
     *
     * @return the write timeout duration
     */
    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    /**
     * Get the maximum number of idle connections in the pool.
     *
     * @return the max idle connections
     */
    public int getMaxIdleConnections() {
        return maxIdleConnections;
    }

    /**
     * Get the keep-alive duration for idle connections.
     *
     * @return the keep-alive duration
     */
    public Duration getKeepAliveDuration() {
        return keepAliveDuration;
    }

    /**
     * Get whether SSL certificate verification should be ignored.
     *
     * <p><b>Warning:</b> Setting this to true disables SSL certificate verification,
     * which makes the connection vulnerable to man-in-the-middle attacks.
     * This should only be used for testing or with trusted self-signed certificates.
     *
     * @return true to ignore SSL certificate verification, false otherwise
     */
    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    /**
     * Get the proxy configuration.
     *
     * @return the proxy configuration, or null if no proxy is configured
     */
    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    /**
     * Get the HTTP version.
     *
     * @return the HTTP version, or null if not configured (auto: HTTP/1.1 for cleartext,
     *     HTTP/2 for https)
     */
    public HttpVersion getHttpVersion() {
        return httpVersion;
    }

    /**
     * Get whether built-in DEBUG request/response logging is enabled.
     *
     * <p>Logging is on by default so that every transport is observable out of the box. Log output
     * is always sanitized by {@link HttpLogSanitizer}: sensitive headers are redacted, URLs and
     * userinfo are sanitized, and bodies are truncated to {@link #getLogBodyMaxLength()}
     * characters. Bodies themselves are only included when {@link #isLogBodies()} is enabled.
     *
     * <p>This option is consumed by {@link LoggingHttpTransport} only; plain transports such as
     * {@link JdkHttpTransport} or {@link OkHttpTransport} ignore it — to get a logging transport
     * from a config, use {@link HttpTransportFactory#createLogging(HttpTransportConfig)}.
     *
     * @return true if built-in logging is enabled
     */
    public boolean isLogRequests() {
        return logRequests;
    }

    /**
     * Get whether transport <b>failures</b> are logged at WARN level.
     *
     * <p>Default is {@code true}. Failure logging is independent of {@link #isLogRequests()}
     * (which governs the DEBUG request/response traffic lines): failures are WARN-level and not
     * gated on the logger level, so connection problems stay visible with a default logging
     * configuration even when DEBUG traffic logging is off — and operators wrapping calls in
     * retry loops can silence the failure noise via {@code logFailures(false)} without losing
     * the DEBUG traffic lines (or vice versa). To fully silence the built-in logger, disable
     * both switches.
     *
     * <p>This option is consumed by {@link LoggingHttpTransport} only; plain transports such as
     * {@link JdkHttpTransport} or {@link OkHttpTransport} ignore it — to get a logging transport
     * from a config, use {@link HttpTransportFactory#createLogging(HttpTransportConfig)}.
     *
     * @return true if failures are logged at WARN
     */
    public boolean isLogFailures() {
        return logFailures;
    }

    /**
     * Get whether request/response <b>body contents</b> are included in the built-in DEBUG logs.
     *
     * <p>Default is {@code false}: bodies are opt-in. Even when enabled, bodies are first
     * passed through {@link HttpLogSanitizer#redactBodyFields(String)} (sensitive-looking string
     * fields are replaced with {@code ***}) and then truncated to {@link #getLogBodyMaxLength()}
     * characters. URLs, headers and status/duration logging are controlled by {@link
     * #isLogRequests()} and stay enabled by default.
     *
     * @return true if body contents are logged
     */
    public boolean isLogBodies() {
        return logBodies;
    }

    /**
     * Get the maximum number of characters logged per request/response body.
     *
     * <p>Consumed by {@link LoggingHttpTransport} only.
     *
     * @return the maximum body length in characters
     */
    public int getLogBodyMaxLength() {
        return logBodyMaxLength;
    }

    /**
     * Get the registered transport listeners.
     *
     * <p>Consumed by {@link LoggingHttpTransport} only; listeners attached to a config passed
     * directly to a plain transport are silently ignored. To obtain a transport that consumes
     * this config, use {@link HttpTransportFactory#createLogging(HttpTransportConfig)}.
     *
     * @return an unmodifiable list of listeners, possibly empty
     */
    public List<HttpTransportListener> getListeners() {
        return listeners;
    }

    /**
     * Create a new builder for HttpTransportConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a default configuration.
     *
     * @return a default HttpTransportConfig instance
     */
    public static HttpTransportConfig defaults() {
        return builder().build();
    }

    /**
     * Builder for HttpTransportConfig.
     */
    public static class Builder {
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
        private Duration streamIdleTimeout = DEFAULT_STREAM_IDLE_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private int maxIdleConnections = 5;
        private Duration keepAliveDuration = Duration.ofMinutes(5);
        private boolean ignoreSsl = false;
        private ProxyConfig proxyConfig = null;
        private HttpVersion httpVersion = null;
        private boolean logRequests = true;
        private boolean logFailures = true;
        private boolean logBodies = false;
        private int logBodyMaxLength = DEFAULT_LOG_BODY_MAX_LENGTH;
        private final List<HttpTransportListener> listeners = new ArrayList<>();

        /**
         * Set the connect timeout.
         *
         * @param connectTimeout the connect timeout duration
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Set the response timeout for streaming requests.
         *
         * <p>For {@link JdkHttpTransport} streaming requests, this bounds the time from request
         * start until the first emitted SSE/NDJSON chunk. {@link OkHttpTransport} does not consume
         * this option; it relies on OkHttp's {@link #getReadTimeout()} for stream reads.
         *
         * @param responseTimeout the response timeout duration
         * @return this builder
         */
        public Builder responseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
            return this;
        }

        /**
         * Set the stream idle timeout.
         *
         * <p>For {@link JdkHttpTransport} streaming requests, this bounds the maximum wait between
         * two consecutive emitted SSE/NDJSON chunks. {@link OkHttpTransport} does not consume this
         * option; it relies on OkHttp's {@link #getReadTimeout()} for stream reads.
         *
         * @param streamIdleTimeout the stream idle timeout duration
         * @return this builder
         */
        public Builder streamIdleTimeout(Duration streamIdleTimeout) {
            this.streamIdleTimeout = streamIdleTimeout;
            return this;
        }

        /**
         * Set the read timeout.
         *
         * <p>{@link OkHttpTransport} applies this as OkHttp's read timeout for both standard and
         * streaming requests. {@link JdkHttpTransport} applies this to non-streaming requests only;
         * JDK streaming requests use {@link #getResponseTimeout()} and
         * {@link #getStreamIdleTimeout()} instead.
         *
         * @param readTimeout the read timeout duration
         * @return this builder
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * Set the write timeout.
         *
         * @param writeTimeout the write timeout duration
         * @return this builder
         */
        public Builder writeTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
            return this;
        }

        /**
         * Set the maximum number of idle connections in the pool.
         *
         * @param maxIdleConnections the max idle connections
         * @return this builder
         */
        public Builder maxIdleConnections(int maxIdleConnections) {
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }

        /**
         * Set the keep-alive duration for idle connections.
         *
         * @param keepAliveDuration the keep-alive duration
         * @return this builder
         */
        public Builder keepAliveDuration(Duration keepAliveDuration) {
            this.keepAliveDuration = keepAliveDuration;
            return this;
        }

        /**
         * Set whether to ignore SSL certificate verification.
         *
         * <p><b>Warning:</b> Setting this to true disables SSL certificate verification,
         * which makes the connection vulnerable to man-in-the-middle attacks.
         * This should only be used for testing or with trusted self-signed certificates.
         *
         * @param ignoreSsl true to ignore SSL certificate verification, false otherwise
         * @return this builder
         */
        public Builder ignoreSsl(boolean ignoreSsl) {
            this.ignoreSsl = ignoreSsl;
            return this;
        }

        /**
         * Set the proxy configuration.
         *
         * <p>Supports HTTP and SOCKS proxies. See {@link ProxyConfig} for details.
         *
         * @param proxyConfig the proxy configuration
         * @return this builder
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * Set the HTTP version, or null for automatic resolution (the default): cleartext
         * requests use HTTP/1.1, https requests use HTTP/2 via ALPN. Explicit {@link
         * HttpVersion#HTTP_2} on cleartext URLs opts in to an h2c upgrade, which some servers
         * (e.g. vLLM/uvicorn) do not handle. Any non-https scheme counts as cleartext. Only
         * {@link JdkHttpTransport} honors this option.
         *
         * @param httpVersion the HTTP version, or null for auto
         * @return this builder
         */
        public Builder httpVersion(HttpVersion httpVersion) {
            this.httpVersion = httpVersion;
            return this;
        }

        /**
         * Set whether built-in DEBUG request/response logging is enabled.
         *
         * <p>Default is {@code true} (logging on). Log output is always sanitized by {@link
         * HttpLogSanitizer}. This switch controls only the built-in DEBUG logs; registered {@link
         * HttpTransportListener}s are always notified.
         *
         * @param logRequests true to enable built-in DEBUG logging
         * @return this builder
         */
        public Builder logRequests(boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }

        /**
         * Set whether transport <b>failures</b> are logged at WARN level.
         *
         * <p>Default is {@code true}. This switch is independent of {@link #logRequests(boolean)}:
         * the failure line is WARN-level and not gated on the logger level, so failures stay
         * visible even when DEBUG traffic logging is disabled. Applications whose callers wrap
         * requests in their own retry loops can set this to {@code false} to avoid one WARN per
         * transient attempt, while keeping (or separately disabling) the DEBUG traffic lines.
         *
         * @param logFailures true to log failures at WARN level
         * @return this builder
         */
        public Builder logFailures(boolean logFailures) {
            this.logFailures = logFailures;
            return this;
        }

        /**
         * Set whether request/response <b>body contents</b> are included in the built-in DEBUG
         * logs.
         *
         * <p>Default is {@code false}: bodies are opt-in, and the logs stay limited to method,
         * sanitized URL, redacted headers, status and duration. When enabled, bodies are first
         * passed through {@link HttpLogSanitizer#redactBodyFields(String)} and then truncated to
         * {@link #logBodyMaxLength(int)} characters — they may still contain prompts and
         * business data, so only opt in where the log sink is trusted.
         *
         * @param logBodies true to include body contents in the built-in DEBUG logs
         * @return this builder
         */
        public Builder logBodies(boolean logBodies) {
            this.logBodies = logBodies;
            return this;
        }

        /**
         * Set the maximum number of characters logged per request/response body. Longer bodies are
         * truncated with an explicit marker; default is {@value #DEFAULT_LOG_BODY_MAX_LENGTH}.
         *
         * @param logBodyMaxLength the maximum body length in characters (values &lt; 0 are treated
         *     as 0)
         * @return this builder
         */
        public Builder logBodyMaxLength(int logBodyMaxLength) {
            this.logBodyMaxLength = logBodyMaxLength;
            return this;
        }

        /**
         * Register one or more transport listeners.
         *
         * <p>Listeners observe every request/response passing through a {@link
         * LoggingHttpTransport} configured with this config. Exceptions thrown by listeners are
         * caught and never affect the HTTP call. This method appends to previously registered
         * listeners.
         *
         * @param listener the first listener to register (must not be null)
         * @param more additional listeners; a null array is ignored, null elements are rejected
         * @return this builder
         * @throws NullPointerException if {@code listener} or any element of {@code more} is null
         */
        public Builder listeners(HttpTransportListener listener, HttpTransportListener... more) {
            Objects.requireNonNull(listener, "listener must not be null");
            this.listeners.add(listener);
            if (more != null) {
                for (HttpTransportListener l : more) {
                    Objects.requireNonNull(l, "listener must not be null");
                    this.listeners.add(l);
                }
            }
            return this;
        }

        /**
         * Build the HttpTransportConfig.
         *
         * @return a new HttpTransportConfig instance
         */
        public HttpTransportConfig build() {
            return new HttpTransportConfig(this);
        }
    }
}
