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

/**
 * Listener SPI for observing HTTP requests and responses at the transport layer.
 *
 * <p>Listeners are attached via {@link
 * HttpTransportConfig.Builder#listeners(HttpTransportListener, HttpTransportListener...)}
 * and are invoked synchronously by {@link LoggingHttpTransport} around every request.
 *
 * <p>Contract:
 * <ul>
 *   <li>Listeners are <b>read-only observers</b>. They must not mutate the request or response
 *       objects; transport implementations make no guarantee that mutations would take effect.</li>
 *   <li><b>Callbacks receive the non-sanitized (unredacted) request and response.</b> Sensitive
 *       headers, credentials in URLs or bodies are <em>not</em> stripped before delivery.
 *       Implementations must therefore never write this data to logs, traces, metrics, or any
 *       other sink without applying {@link HttpLogSanitizer} themselves ({@code
 *       redactHeaders(...)}, {@code sanitizeUrl(...)}, {@code redactBodyFields(...)}, {@code
 *       truncateBody(...)} are the recommended helpers for building a safe view).</li>
 *   <li>Exceptions thrown by a listener are caught and logged; they never affect the outcome of
 *       the HTTP call.</li>
 *   <li>{@code onRequest} is invoked on the calling thread for {@link HttpTransport#execute}.
 *       For {@link HttpTransport#stream} every side effect is deferred until subscription and
 *       replayed per subscription: {@code onRequest} fires once per subscriber on the subscribing
 *       thread, and subsequent streaming callbacks may run on Reactor scheduler threads.</li>
 * </ul>
 *
 * <p>Streaming semantics: {@link HttpTransport#stream} returns a {@code Flux<String>} of SSE/NDJSON
 * data lines, so there is no single {@link HttpResponse} object to report. {@link #onResponse} is
 * therefore <b>not</b> invoked for streaming requests. Instead, exactly one terminal callback is
 * dispatched per subscription: {@link #onStreamComplete} when the stream completes normally,
 * {@link #onFailure} when it errors, and {@link #onStreamCancel} when it is cancelled (user stop,
 * timeout, {@code take(n)}, downstream disposal). Note that the built-in completion <em>log
 * line</em> of {@link LoggingHttpTransport} is still subject to its log-level/switch
 * configuration, while the terminal callbacks are always dispatched.
 *
 * <p>All methods have default no-op implementations, so implementors only override what they need.
 */
public interface HttpTransportListener {

    /**
     * Invoked before the request is handed to the underlying transport.
     *
     * @param request the outgoing request
     */
    default void onRequest(HttpRequest request) {}

    /**
     * Invoked after a successful (any status code) synchronous response has been received.
     *
     * <p>Not invoked for streaming requests; see the interface javadoc.
     *
     * @param request the original request
     * @param response the received response
     * @param durationNanos wall-clock duration from request start to response completion
     */
    default void onResponse(HttpRequest request, HttpResponse response, long durationNanos) {}

    /**
     * Invoked when a request fails: connection errors, timeouts, or any exception thrown by the
     * underlying transport. For streaming requests this fires when the stream errors.
     *
     * @param request the original request
     * @param error the failure cause
     * @param durationNanos wall-clock duration from request start to the failure
     */
    default void onFailure(HttpRequest request, Throwable error, long durationNanos) {}

    /**
     * Invoked when a streaming request completes <b>normally</b> (i.e. the {@code
     * Flux<String>} emitted by {@link HttpTransport#stream} finishes without error). This is the
     * streaming counterpart of {@link #onResponse}; it fires in addition to — and after — the
     * built-in completion log line, and is dispatched regardless of the transport's logging
     * switches or logger level. It is not invoked when the stream errors ({@link #onFailure}
     * fires instead) or is cancelled ({@link #onStreamCancel} fires instead).
     *
     * @param request the original request
     * @param durationNanos wall-clock duration from request start to stream completion
     * @param chunkCount number of data lines emitted by the stream
     */
    default void onStreamComplete(HttpRequest request, long durationNanos, long chunkCount) {}

    /**
     * Invoked when a streaming request is <b>cancelled</b> before it completes: the subscriber
     * disposed the subscription (user stop, timeout, {@code take(n)}, downstream error or any
     * other disposal). This is the streaming counterpart of a cancelled synchronous call and the
     * most common non-success terminal path for LLM traffic — without it, an implementation
     * doing cost/latency accounting on this SPI would see {@link #onRequest} with no matching
     * terminal event and could not tell "still running" from "abandoned".
     *
     * <p>Like {@link #onStreamComplete}, this callback is dispatched regardless of the
     * transport's logging switches or logger level. Exactly one of {@link #onStreamComplete},
     * {@link #onFailure} and {@code onStreamCancel} fires per subscription; the chunk count
     * reflects the data lines emitted before the cancellation.
     *
     * @param request the original request
     * @param durationNanos wall-clock duration from request start to the cancellation
     * @param chunkCount number of data lines emitted by the stream before it was cancelled
     */
    default void onStreamCancel(HttpRequest request, long durationNanos, long chunkCount) {}
}
