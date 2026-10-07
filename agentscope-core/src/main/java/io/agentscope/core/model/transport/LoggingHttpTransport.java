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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Decorator that adds uniform, sanitized request/response logging and a {@link
 * HttpTransportListener} SPI to any {@link HttpTransport}.
 *
 * <p>Everything that flows through this framework's transport layer can be wrapped with this
 * decorator; {@link HttpTransportFactory#getDefault()} does so automatically for the lazily created
 * default transport, which makes all model clients (OpenAI, DashScope, Ollama, ...) uniformly
 * observable without any configuration.
 *
 * <p>Logging behavior (controlled by {@link HttpTransportConfig.Builder#logRequests(boolean)},
 * {@link HttpTransportConfig.Builder#logFailures(boolean)} and {@link
 * HttpTransportConfig.Builder#logBodies(boolean)}):
 * <ul>
 *   <li>One line per request: method, sanitized URL, redacted headers — body contents only if
 *       {@code logBodies(true)};</li>
 *   <li>One line per synchronous response: status and duration — body contents only if {@code
 *       logBodies(true)};</li>
 *   <li>One line per <b>failure, logged at WARN level</b> (regardless of the logger level, so
 *       connection problems are always visible; suppressed only by {@code logFailures(false)},
 *       independently of {@code logRequests}); the error is attached as the log event's throwable
 *       instead of being stringified into the message;</li>
 *   <li>For streaming requests only the request line plus stream completion/cancel/error lines
 *       are emitted — SSE/NDJSON chunk payloads are never logged.</li>
 * </ul>
 *
 * <p>Sanitization is always applied to log output: sensitive headers (Authorization, Cookie, ...)
 * are redacted to {@code ***}, URLs are sanitized (sensitive query parameters and userinfo
 * stripped), and when body logging is opted in, bodies are first passed through {@link
 * HttpLogSanitizer#redactBodyFields(String)} and then truncated to {@link
 * HttpTransportConfig.Builder#logBodyMaxLength(int)} characters.
 *
 * <p>{@link HttpTransportListener} callbacks receive the untouched original request/response
 * objects, <b>not</b> the sanitized log view; listeners that log must sanitize themselves (see
 * the {@link HttpTransportListener} contract). For streaming requests exactly one terminal
 * callback is dispatched per subscription — {@link
 * HttpTransportListener#onStreamComplete(HttpRequest, long, long)} on normal completion, {@link
 * HttpTransportListener#onStreamCancel(HttpRequest, long, long)} on cancellation, {@link
 * HttpTransportListener#onFailure(HttpRequest, Throwable, long)} on error — independent of the
 * logging switches and logger level.
 */
public class LoggingHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingHttpTransport.class);

    private final HttpTransport delegate;
    private final boolean logRequests;
    private final boolean logFailures;
    private final boolean logBodies;
    private final int logBodyMaxLength;
    private final List<HttpTransportListener> listeners;

    /** Listener classes already warned about, so at most one WARN is logged per listener type. */
    private final Set<Class<?>> warnedListeners = ConcurrentHashMap.newKeySet();

    /**
     * Wrap a transport with default configuration.
     *
     * @param delegate the transport to wrap
     */
    public LoggingHttpTransport(HttpTransport delegate) {
        this(delegate, HttpTransportConfig.defaults());
    }

    /**
     * Wrap a transport with explicit configuration.
     *
     * @param delegate the transport to wrap
     * @param config logging configuration (log switch, body switch, body limit, listeners)
     */
    public LoggingHttpTransport(HttpTransport delegate, HttpTransportConfig config) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate transport must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.delegate = delegate;
        this.logRequests = config.isLogRequests();
        this.logFailures = config.isLogFailures();
        this.logBodies = config.isLogBodies();
        this.logBodyMaxLength = config.getLogBodyMaxLength();
        this.listeners = List.copyOf(config.getListeners());
    }

    /**
     * Get the wrapped delegate transport.
     *
     * @return the delegate transport
     */
    public HttpTransport getDelegate() {
        return delegate;
    }

    /**
     * Get whether body contents are included in the built-in DEBUG logs.
     *
     * @return true if body contents are logged
     */
    public boolean isLogBodiesEnabled() {
        return logBodies;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        logRequest(request, false);
        notifyListeners(listener -> listener.onRequest(request));
        long startNanos = System.nanoTime();
        try {
            HttpResponse response = delegate.execute(request);
            long durationNanos = System.nanoTime() - startNanos;
            logExecuteResponse(request, response, durationNanos);
            notifyListeners(listener -> listener.onResponse(request, response, durationNanos));
            return response;
        } catch (RuntimeException e) {
            // HttpTransportException extends RuntimeException, so this covers transport failures
            long durationNanos = System.nanoTime() - startNanos;
            logFailure(request, e, durationNanos, false);
            notifyListeners(listener -> listener.onFailure(request, e, durationNanos));
            throw e;
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        // Deferred per subscription: request logging, listener notification, timing and the chunk
        // counter belong to one concrete subscription, so a never-subscribed Flux emits no phantom
        // events and each subscription of a cold stream gets fresh state.
        return Flux.defer(
                () -> {
                    long startNanos = System.nanoTime();
                    logRequest(request, true);
                    notifyListeners(listener -> listener.onRequest(request));
                    AtomicInteger chunkCount = new AtomicInteger();
                    try {
                        return delegate.stream(request)
                                .doOnNext(chunk -> chunkCount.incrementAndGet())
                                .doOnComplete(
                                        () -> {
                                            long durationNanos = System.nanoTime() - startNanos;
                                            notifyListeners(
                                                    listener ->
                                                            listener.onStreamComplete(
                                                                    request,
                                                                    durationNanos,
                                                                    chunkCount.get()));
                                            if (!logRequests || !log.isDebugEnabled()) {
                                                return;
                                            }
                                            log.debug(
                                                    "HTTP {} {} (streaming) completed: {} chunks,"
                                                            + " {} ms",
                                                    request.getMethod(),
                                                    safeUrl(request),
                                                    chunkCount.get(),
                                                    msSince(startNanos));
                                        })
                                .doOnCancel(
                                        () -> {
                                            long durationNanos = System.nanoTime() - startNanos;
                                            notifyListeners(
                                                    listener ->
                                                            listener.onStreamCancel(
                                                                    request,
                                                                    durationNanos,
                                                                    chunkCount.get()));
                                            if (!logRequests || !log.isDebugEnabled()) {
                                                return;
                                            }
                                            log.debug(
                                                    "HTTP {} {} (streaming) cancelled after {}"
                                                            + " chunks, {} ms",
                                                    request.getMethod(),
                                                    safeUrl(request),
                                                    chunkCount.get(),
                                                    msSince(startNanos));
                                        })
                                .doOnError(
                                        e -> {
                                            long durationNanos = System.nanoTime() - startNanos;
                                            logFailure(request, e, durationNanos, true);
                                            notifyListeners(
                                                    listener ->
                                                            listener.onFailure(
                                                                    request, e, durationNanos));
                                        });
                    } catch (RuntimeException e) {
                        // delegate.stream() threw synchronously instead of signalling onError
                        long durationNanos = System.nanoTime() - startNanos;
                        logFailure(request, e, durationNanos, true);
                        notifyListeners(listener -> listener.onFailure(request, e, durationNanos));
                        throw e;
                    }
                });
    }

    @Override
    public void close() {
        delegate.close();
    }

    private void logRequest(HttpRequest request, boolean streaming) {
        if (!logRequests || !log.isDebugEnabled()) {
            return;
        }
        Map<String, String> safeHeaders = HttpLogSanitizer.redactHeaders(request.getHeaders());
        log.debug(
                "HTTP {} {}{} reqHeaders={} reqBody={}",
                request.getMethod(),
                safeUrl(request),
                streaming ? " (streaming)" : "",
                safeHeaders,
                describeBody(request.getBody()));
    }

    private void logExecuteResponse(
            HttpRequest request, HttpResponse response, long durationNanos) {
        if (!logRequests || !log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "HTTP {} {} -> {} ({} ms) respBody={}",
                request.getMethod(),
                safeUrl(request),
                response.getStatusCode() + (response.isSuccessful() ? " OK" : ""),
                durationNanos / 1_000_000,
                describeBody(response.getBody()));
    }

    private void logFailure(
            HttpRequest request, Throwable error, long durationNanos, boolean streaming) {
        // WARN, not gated on the logger level: connection failures must be visible with the
        // default logging configuration. Controlled by its own logFailures switch, independent
        // of logRequests (which governs the DEBUG traffic lines), so retry-heavy callers can
        // silence failure noise without losing traffic logging, or vice versa.
        if (!logFailures) {
            return;
        }
        log.warn(
                "HTTP {} {}{} failed after {} ms",
                request.getMethod(),
                safeUrl(request),
                streaming ? " (streaming)" : "",
                durationNanos / 1_000_000,
                error);
    }

    private static String safeUrl(HttpRequest request) {
        return HttpLogSanitizer.sanitizeUrl(request.getUrl());
    }

    private String describeBody(String body) {
        if (!logBodies) {
            return "<bodies disabled>";
        }
        if (body == null) {
            return "<no body>";
        }
        // Redact before truncating: a truncation cut could otherwise split a sensitive value so
        // that its partially-surviving prefix would never match the redaction pattern.
        String redacted = HttpLogSanitizer.redactBodyFields(body);
        String shown = HttpLogSanitizer.truncateBody(redacted, logBodyMaxLength);
        int shownLength = Math.min(redacted.length(), Math.max(logBodyMaxLength, 0));
        return "(" + shownLength + "/" + redacted.length() + " chars) " + shown;
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void notifyListeners(Consumer<HttpTransportListener> action) {
        if (listeners.isEmpty()) {
            return;
        }
        for (HttpTransportListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // One WARN per listener class per transport instance; repeated failures of the
                // same listener degrade to DEBUG so noisy listeners cannot flood the logs.
                if (warnedListeners.add(listener.getClass())) {
                    log.warn(
                            "HttpTransportListener {} threw an exception (further listener errors"
                                    + " will be logged at DEBUG): {}",
                            listener.getClass().getName(),
                            e.getMessage(),
                            e);
                } else {
                    log.debug("HttpTransportListener threw an exception", e);
                }
            }
        }
    }
}
