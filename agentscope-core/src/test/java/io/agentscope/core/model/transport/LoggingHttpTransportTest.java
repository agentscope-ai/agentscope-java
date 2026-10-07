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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for {@link LoggingHttpTransport}. */
@Tag("unit")
class LoggingHttpTransportTest {

    private static HttpRequest sampleRequest() {
        return HttpRequest.builder()
                .url("https://api.example.com/v1/chat")
                .method("POST")
                .header("Authorization", "Bearer sk-secret")
                .header("Content-Type", "application/json")
                .body("{\"model\":\"test\"}")
                .build();
    }

    private static HttpResponse sampleResponse(int status) {
        return HttpResponse.builder().statusCode(status).body("resp-body").build();
    }

    @Test
    void executeDelegatesAndReturnsResponse() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);
        HttpResponse result = transport.execute(request);

        assertSame(response, result);
        verify(delegate).execute(request);
    }

    @Test
    void executeNotifiesRequestAndResponseListeners() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        AtomicReference<HttpRequest> requestSeen = new AtomicReference<>();
        AtomicReference<HttpResponse> responseSeen = new AtomicReference<>();
        AtomicInteger responseCalls = new AtomicInteger();
        HttpTransportListener listener =
                new HttpTransportListener() {
                    @Override
                    public void onRequest(HttpRequest req) {
                        requestSeen.set(req);
                    }

                    @Override
                    public void onResponse(HttpRequest req, HttpResponse resp, long durationNanos) {
                        responseSeen.set(resp);
                        responseCalls.incrementAndGet();
                        assertTrue(durationNanos >= 0);
                    }
                };

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().listeners(listener).build());
        transport.execute(request);

        assertSame(request, requestSeen.get());
        assertSame(response, responseSeen.get());
        assertEquals(1, responseCalls.get());
    }

    @Test
    void executeFailureNotifiesOnFailureAndRethrows() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpTransportException failure =
                new HttpTransportException("connection refused", new RuntimeException("boom"));
        when(delegate.execute(request)).thenThrow(failure);

        AtomicReference<Throwable> failureSeen = new AtomicReference<>();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onFailure(
                                                    HttpRequest req,
                                                    Throwable error,
                                                    long durationNanos) {
                                                failureSeen.set(error);
                                                assertTrue(durationNanos >= 0);
                                            }
                                        })
                                .build());

        HttpTransportException thrown =
                assertThrows(HttpTransportException.class, () -> transport.execute(request));
        assertSame(failure, thrown);
        assertSame(failure, failureSeen.get());
    }

    @Test
    void streamDelegatesAndNotifiesRequestListener() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2"));

        AtomicInteger requestCalls = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onRequest(HttpRequest req) {
                                                requestCalls.incrementAndGet();
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request))
                .expectNext("data: 1", "data: 2")
                .verifyComplete();

        assertEquals(1, requestCalls.get());
    }

    @Test
    void streamErrorNotifiesOnFailure() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        RuntimeException streamError = new RuntimeException("stream broke");
        when(delegate.stream(request)).thenReturn(Flux.error(streamError));

        AtomicReference<Throwable> failureSeen = new AtomicReference<>();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onFailure(
                                                    HttpRequest req,
                                                    Throwable error,
                                                    long durationNanos) {
                                                failureSeen.set(error);
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request)).expectError(RuntimeException.class).verify();

        assertSame(streamError, failureSeen.get());
    }

    @Test
    void streamSynchronousDelegateFailureNotifiesOnFailureAndLogs() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        IllegalStateException syncFailure = new IllegalStateException("cannot build stream");
        when(delegate.stream(request)).thenThrow(syncFailure);

        AtomicReference<Throwable> failureSeen = new AtomicReference<>();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onFailure(
                                                    HttpRequest req,
                                                    Throwable error,
                                                    long durationNanos) {
                                                failureSeen.set(error);
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request))
                .expectError(IllegalStateException.class)
                .verify();

        assertSame(syncFailure, failureSeen.get());
        assertEquals(2, logEvents.list.size());
        ILoggingEvent failureEvent = logEvents.list.get(1);
        assertEquals(Level.WARN, failureEvent.getLevel());
        assertTrue(failureEvent.getFormattedMessage().contains("(streaming) failed after"));
    }

    @Test
    void streamSideEffectsDeferredUntilSubscriptionAndReplayedPerSubscription() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1"));

        AtomicInteger requestCalls = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onRequest(HttpRequest req) {
                                                requestCalls.incrementAndGet();
                                            }
                                        })
                                .build());

        Flux<String> deferred = transport.stream(request);

        assertEquals(0, requestCalls.get());
        assertEquals(0, logEvents.list.size());
        verify(delegate, never()).stream(request);

        StepVerifier.create(deferred).expectNextCount(1).verifyComplete();
        StepVerifier.create(deferred).expectNextCount(1).verifyComplete();

        assertEquals(2, requestCalls.get());
        verify(delegate, times(2)).stream(request);
    }

    @Test
    void streamDoesNotNotifyResponseListener() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1"));

        AtomicInteger responseCalls = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onResponse(
                                                    HttpRequest req,
                                                    HttpResponse resp,
                                                    long durationNanos) {
                                                responseCalls.incrementAndGet();
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request)).expectNextCount(1).verifyComplete();

        assertEquals(0, responseCalls.get());
    }

    @Test
    void listenerExceptionDoesNotBreakExecution() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        AtomicInteger responseCalls = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onRequest(HttpRequest req) {
                                                throw new IllegalStateException("listener boom");
                                            }

                                            @Override
                                            public void onResponse(
                                                    HttpRequest req,
                                                    HttpResponse resp,
                                                    long durationNanos) {
                                                responseCalls.incrementAndGet();
                                            }
                                        })
                                .build());

        assertSame(response, transport.execute(request));
        assertEquals(1, responseCalls.get());
    }

    @Test
    void logRequestsDisabledStillNotifiesListeners() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        AtomicInteger requestCalls = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .logRequests(false)
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onRequest(HttpRequest req) {
                                                requestCalls.incrementAndGet();
                                            }
                                        })
                                .build());

        assertSame(response, transport.execute(request));
        assertEquals(1, requestCalls.get());
    }

    @Test
    void closeDelegates() {
        HttpTransport delegate = mock(HttpTransport.class);
        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        transport.close();

        verify(delegate).close();
    }

    @Test
    void getDelegateReturnsWrappedTransport() {
        HttpTransport delegate = mock(HttpTransport.class);
        assertEquals(delegate, new LoggingHttpTransport(delegate).getDelegate());
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LoggingHttpTransport(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoggingHttpTransport(mock(HttpTransport.class), null));
    }

    @Test
    void multipleListenersAllNotified() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        AtomicInteger count = new AtomicInteger();
        HttpTransportListener first =
                new HttpTransportListener() {
                    @Override
                    public void onResponse(HttpRequest req, HttpResponse resp, long durationNanos) {
                        count.incrementAndGet();
                    }
                };
        HttpTransportListener second =
                new HttpTransportListener() {
                    @Override
                    public void onResponse(HttpRequest req, HttpResponse resp, long durationNanos) {
                        count.incrementAndGet();
                    }
                };

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().listeners(first, second).build());
        transport.execute(request);

        assertEquals(2, count.get());
    }

    @Test
    void configListenersListIsDefensivelyCopied() {
        HttpTransportListener listener = new HttpTransportListener() {};
        HttpTransportConfig config = HttpTransportConfig.builder().listeners(listener).build();

        List<HttpTransportListener> listeners = config.getListeners();
        assertEquals(1, listeners.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> listeners.add(new HttpTransportListener() {}));
    }

    // ===== DEBUG logging behavior (logback-classic test backend) =====

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> logEvents;

    @BeforeEach
    void attachLogCollector() {
        logbackLogger = (Logger) LoggerFactory.getLogger(LoggingHttpTransport.class);
        logEvents = new ListAppender<>();
        logEvents.start();
        logbackLogger.addAppender(logEvents);
        logbackLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogCollector() {
        logbackLogger.detachAppender(logEvents);
        logbackLogger.setLevel(null);
    }

    @Test
    void executeLogsSanitizedRequestAndResponseAtDebug() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logBodies(true).build());
        assertSame(response, transport.execute(request));

        String requestBody = "{\"model\":\"test\"}";
        String responseBody = "resp-body";
        assertEquals(2, logEvents.list.size());
        String requestLine = logEvents.list.get(0).getFormattedMessage();
        assertTrue(
                requestLine.contains(
                        "HTTP POST https://api.example.com/v1/chat reqHeaders="
                                + "{Authorization=***, Content-Type=application/json} reqBody=("
                                + requestBody.length()
                                + "/"
                                + requestBody.length()
                                + " chars) "
                                + requestBody));
        String responseLine = logEvents.list.get(1).getFormattedMessage();
        assertTrue(responseLine.contains("HTTP POST https://api.example.com/v1/chat -> 200 OK"));
        assertTrue(
                responseLine.contains(
                        "respBody=("
                                + responseBody.length()
                                + "/"
                                + responseBody.length()
                                + " chars) "
                                + responseBody));
    }

    @Test
    void executeLogsNonSuccessfulStatusWithoutOkSuffix() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(503);
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);
        assertSame(response, transport.execute(request));

        String responseLine = logEvents.list.get(1).getFormattedMessage();
        assertTrue(responseLine.contains(" -> 503 "));
        assertFalse(responseLine.contains(" OK"));
    }

    @Test
    void executeLogsNoBodyPlaceholderForNullBodies() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/models")
                        .method("GET")
                        .build();
        HttpResponse response = HttpResponse.builder().statusCode(204).build();
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logBodies(true).build());
        assertSame(response, transport.execute(request));

        assertEquals(2, logEvents.list.size());
        assertTrue(logEvents.list.get(0).getFormattedMessage().contains("reqBody=<no body>"));
        assertTrue(logEvents.list.get(1).getFormattedMessage().contains("respBody=<no body>"));
    }

    @Test
    void failureLogsAtWarnEvenWhenDebugDisabled() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpTransportException failure =
                new HttpTransportException("connection refused", new RuntimeException("boom"));
        when(delegate.execute(request)).thenThrow(failure);

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        assertThrows(HttpTransportException.class, () -> transport.execute(request));

        assertEquals(2, logEvents.list.size());
        ILoggingEvent failureEvent = logEvents.list.get(1);
        assertEquals(Level.WARN, failureEvent.getLevel());
        String failureLine = failureEvent.getFormattedMessage();
        assertTrue(failureLine.contains("HTTP POST https://api.example.com/v1/chat failed after"));
        assertFalse(failureLine.contains("connection refused"));
        assertEquals(
                HttpTransportException.class.getName(),
                failureEvent.getThrowableProxy().getClassName());
    }

    @Test
    void streamLogsRequestAndCompletionAtDebug() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2"));

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        StepVerifier.create(transport.stream(request))
                .expectNext("data: 1", "data: 2")
                .verifyComplete();

        assertEquals(2, logEvents.list.size());
        String requestLine = logEvents.list.get(0).getFormattedMessage();
        assertTrue(
                requestLine.contains(
                        "HTTP POST https://api.example.com/v1/chat (streaming) reqHeaders="));
        assertTrue(
                logEvents
                        .list
                        .get(1)
                        .getFormattedMessage()
                        .contains("(streaming) completed: 2 chunks"));
    }

    @Test
    void streamErrorLogsAtDebug() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        RuntimeException streamError = new RuntimeException("stream broke");
        when(delegate.stream(request)).thenReturn(Flux.error(streamError));

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        StepVerifier.create(transport.stream(request)).expectError(RuntimeException.class).verify();

        assertEquals(2, logEvents.list.size());
        ILoggingEvent failureEvent = logEvents.list.get(1);
        assertEquals(Level.WARN, failureEvent.getLevel());
        String failureLine = failureEvent.getFormattedMessage();
        assertTrue(
                failureLine.contains(
                        "HTTP POST https://api.example.com/v1/chat (streaming) failed after"));
        assertFalse(failureLine.contains("stream broke"));
        assertEquals(
                RuntimeException.class.getName(), failureEvent.getThrowableProxy().getClassName());
        assertEquals("stream broke", failureEvent.getThrowableProxy().getMessage());
    }

    @Test
    void debugDisabledSkipsExecuteLogsButNotListeners() throws Exception {
        logbackLogger.setLevel(Level.INFO);
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        AtomicInteger requestCalls = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onRequest(HttpRequest req) {
                                                requestCalls.incrementAndGet();
                                            }
                                        })
                                .build());

        assertSame(response, transport.execute(request));

        assertEquals(1, requestCalls.get());
        assertEquals(0, logEvents.list.size());
    }

    @Test
    void debugDisabledStillLogsFailureAtWarn() throws Exception {
        logbackLogger.setLevel(Level.INFO);
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.execute(request))
                .thenThrow(new HttpTransportException("boom", new RuntimeException("boom")));

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        assertThrows(HttpTransportException.class, () -> transport.execute(request));
        assertEquals(1, logEvents.list.size());
        assertEquals(Level.WARN, logEvents.list.get(0).getLevel());
        assertTrue(
                logEvents
                        .list
                        .get(0)
                        .getFormattedMessage()
                        .contains("HTTP POST https://api.example.com/v1/chat failed after"));
    }

    @Test
    void debugDisabledStillLogsStreamFailureAtWarn() {
        logbackLogger.setLevel(Level.INFO);
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request))
                .thenReturn(Flux.just("data: 1"), Flux.error(new RuntimeException("stream broke")));

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        StepVerifier.create(transport.stream(request)).expectNextCount(1).verifyComplete();
        StepVerifier.create(transport.stream(request)).expectError(RuntimeException.class).verify();

        assertEquals(1, logEvents.list.size());
        assertEquals(Level.WARN, logEvents.list.get(0).getLevel());
        assertTrue(
                logEvents.list.get(0).getFormattedMessage().contains("(streaming) failed after"));
    }

    @Test
    void logRequestsFalseSkipsStreamCompletionLog() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1"));

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logRequests(false).build());

        StepVerifier.create(transport.stream(request)).expectNextCount(1).verifyComplete();

        assertEquals(0, logEvents.list.size());
    }

    @Test
    void logRequestsFalseStillLogsFailureAtWarn() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.execute(request))
                .thenThrow(new HttpTransportException("boom", new RuntimeException("boom")));

        // logRequests governs the DEBUG traffic lines only; failures are controlled by the
        // independent logFailures switch (default true) and stay visible.
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logRequests(false).build());

        assertThrows(HttpTransportException.class, () -> transport.execute(request));
        assertEquals(1, logEvents.list.size());
        assertEquals(Level.WARN, logEvents.list.get(0).getLevel());
        assertTrue(logEvents.list.get(0).getFormattedMessage().contains("failed after"));
    }

    @Test
    void logFailuresFalseSkipsFailureLog() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.execute(request))
                .thenThrow(new HttpTransportException("boom", new RuntimeException("boom")));

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logFailures(false).build());

        assertThrows(HttpTransportException.class, () -> transport.execute(request));
        // The DEBUG request line is still emitted (logRequests is untouched); only the failure
        // WARN is suppressed.
        assertEquals(1, logEvents.list.size());
        assertEquals(Level.DEBUG, logEvents.list.get(0).getLevel());
    }

    @Test
    void logFailuresFalseKeepsDebugTrafficLines() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        // The two switches are independent: silencing failures must not silence DEBUG traffic.
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logFailures(false).build());

        assertSame(response, transport.execute(request));
        assertEquals(2, logEvents.list.size());
        assertTrue(logEvents.list.get(0).getFormattedMessage().contains("HTTP POST"));
        assertTrue(logEvents.list.get(1).getFormattedMessage().contains("200 OK"));
    }

    @Test
    void logFailuresFalseSkipsStreamFailureLog() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.error(new RuntimeException("stream broke")));

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logFailures(false).build());

        StepVerifier.create(transport.stream(request)).expectError(RuntimeException.class).verify();
        // Only the DEBUG request line remains; the failure WARN is suppressed.
        assertEquals(1, logEvents.list.size());
        assertEquals(Level.DEBUG, logEvents.list.get(0).getLevel());
    }

    @Test
    void listenerExceptionWarnsOnceThenLogsAtDebug() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onRequest(HttpRequest req) {
                                                throw new IllegalStateException("boom-request");
                                            }

                                            @Override
                                            public void onResponse(
                                                    HttpRequest req,
                                                    HttpResponse resp,
                                                    long durationNanos) {
                                                throw new IllegalStateException("boom-response");
                                            }
                                        })
                                .build());

        assertSame(response, transport.execute(request));

        assertEquals(4, logEvents.list.size());
        ILoggingEvent firstListenerError = logEvents.list.get(1);
        assertEquals(Level.WARN, firstListenerError.getLevel());
        assertTrue(firstListenerError.getFormattedMessage().contains("threw an exception"));
        assertTrue(firstListenerError.getFormattedMessage().contains("boom-request"));
        ILoggingEvent secondListenerError = logEvents.list.get(3);
        assertEquals(Level.DEBUG, secondListenerError.getLevel());
        assertTrue(secondListenerError.getFormattedMessage().contains("threw an exception"));
    }

    // ===== logBodies switch (body contents are opt-in) =====

    @Test
    void bodiesDisabledByDefaultLogsPlaceholdersWithoutContent() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);
        assertSame(response, transport.execute(request));

        assertEquals(2, logEvents.list.size());
        String requestLine = logEvents.list.get(0).getFormattedMessage();
        assertTrue(requestLine.contains("reqBody=<bodies disabled>"));
        assertFalse(requestLine.contains("{\"model\""));
        String responseLine = logEvents.list.get(1).getFormattedMessage();
        assertTrue(responseLine.contains("respBody=<bodies disabled>"));
        assertFalse(responseLine.contains("resp-body"));
    }

    @Test
    void logBodiesOptInLogsBodyContentWithRedaction() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/chat")
                        .method("POST")
                        .body("{\"api_key\":\"sk-secret123\",\"model\":\"gpt-4\"}")
                        .build();
        when(delegate.execute(request)).thenReturn(sampleResponse(200));

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate, HttpTransportConfig.builder().logBodies(true).build());
        transport.execute(request);

        String requestLine = logEvents.list.get(0).getFormattedMessage();
        assertFalse(requestLine.contains("sk-secret123"));
        assertTrue(requestLine.contains("\"api_key\":\"***\""));
        assertTrue(requestLine.contains("\"model\":\"gpt-4\""));
    }

    @Test
    void bodyRedactionAppliedBeforeTruncation() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        // Truncation point (150 chars) falls inside the raw secret value: redaction must run
        // first, otherwise a partially-truncated secret prefix would survive into the log.
        String body = "{\"q\":\"" + "x".repeat(120) + "\",\"api_key\":\"sk-super-secret-value\"}";
        HttpRequest request =
                HttpRequest.builder()
                        .url("https://api.example.com/v1/chat")
                        .method("POST")
                        .body(body)
                        .build();
        when(delegate.execute(request)).thenReturn(sampleResponse(200));

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .logBodies(true)
                                .logBodyMaxLength(150)
                                .build());
        transport.execute(request);

        String requestLine = logEvents.list.get(0).getFormattedMessage();
        assertFalse(requestLine.contains("sk-"));
        assertTrue(requestLine.contains("\"api_key\":\"***\""));
    }

    // ===== onStreamComplete listener notification =====

    @Test
    void streamCompletionNotifiesOnStreamCompleteListener() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2"));

        AtomicReference<Long> chunksSeen = new AtomicReference<>();
        AtomicReference<Long> durationSeen = new AtomicReference<>();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onStreamComplete(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                chunksSeen.set(chunkCount);
                                                durationSeen.set(durationNanos);
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request))
                .expectNext("data: 1", "data: 2")
                .verifyComplete();

        assertEquals(2L, chunksSeen.get());
        assertTrue(durationSeen.get() >= 0);
    }

    @Test
    void streamCompletionNotifiesListenerEvenWhenLoggingDisabled() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1"));

        AtomicInteger completions = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .logRequests(false)
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onStreamComplete(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                completions.incrementAndGet();
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request)).expectNextCount(1).verifyComplete();

        assertEquals(1, completions.get());
        assertEquals(0, logEvents.list.size());
    }

    @Test
    void streamErrorDoesNotNotifyOnStreamComplete() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        RuntimeException streamError = new RuntimeException("stream broke");
        when(delegate.stream(request)).thenReturn(Flux.error(streamError));

        AtomicInteger completions = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onStreamComplete(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                completions.incrementAndGet();
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request)).expectError(RuntimeException.class).verify();

        assertEquals(0, completions.get());
    }

    // ===== onStreamCancel listener notification =====

    @Test
    void streamCancelNotifiesOnStreamCancelListener() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2", "data: 3"));

        AtomicReference<Long> chunksSeen = new AtomicReference<>();
        AtomicReference<Long> durationSeen = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onStreamCancel(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                chunksSeen.set(chunkCount);
                                                durationSeen.set(durationNanos);
                                            }

                                            @Override
                                            public void onStreamComplete(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                completions.incrementAndGet();
                                            }

                                            @Override
                                            public void onFailure(
                                                    HttpRequest req,
                                                    Throwable error,
                                                    long durationNanos) {
                                                failures.incrementAndGet();
                                            }
                                        })
                                .build());

        // take(1) cancels the upstream subscription after the first element: exactly one
        // terminal callback may fire, and it must be onStreamCancel.
        StepVerifier.create(transport.stream(request).take(1))
                .expectNext("data: 1")
                .verifyComplete();

        assertEquals(1L, chunksSeen.get());
        assertTrue(durationSeen.get() >= 0);
        assertEquals(0, completions.get());
        assertEquals(0, failures.get());
    }

    @Test
    void streamCancelLogsAtDebug() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2"));

        LoggingHttpTransport transport = new LoggingHttpTransport(delegate);

        StepVerifier.create(transport.stream(request).take(1))
                .expectNext("data: 1")
                .verifyComplete();

        // request line + cancel line
        assertEquals(2, logEvents.list.size());
        ILoggingEvent cancelEvent = logEvents.list.get(1);
        assertEquals(Level.DEBUG, cancelEvent.getLevel());
        assertTrue(
                cancelEvent.getFormattedMessage().contains("(streaming) cancelled after 1 chunks"),
                "unexpected cancel log: " + cancelEvent.getFormattedMessage());
    }

    @Test
    void streamCancelSkipsDebugLogWhenDebugEnabled() {
        logbackLogger.setLevel(Level.INFO);
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2"));

        AtomicInteger cancels = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onStreamCancel(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                cancels.incrementAndGet();
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request).take(1)).expectNextCount(1).verifyComplete();

        // The cancel callback is dispatched regardless of the logger level; only the DEBUG
        // log line is suppressed.
        assertEquals(1, cancels.get());
        assertEquals(0, logEvents.list.size());
    }

    @Test
    void streamCancelNotifiesListenerEvenWhenLoggingDisabled() {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        when(delegate.stream(request)).thenReturn(Flux.just("data: 1", "data: 2"));

        AtomicInteger cancels = new AtomicInteger();
        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .logRequests(false)
                                .logFailures(false)
                                .listeners(
                                        new HttpTransportListener() {
                                            @Override
                                            public void onStreamCancel(
                                                    HttpRequest req,
                                                    long durationNanos,
                                                    long chunkCount) {
                                                cancels.incrementAndGet();
                                            }
                                        })
                                .build());

        StepVerifier.create(transport.stream(request).take(1)).expectNextCount(1).verifyComplete();

        assertEquals(1, cancels.get());
        assertEquals(0, logEvents.list.size());
    }

    // ===== listener error throttling (per listener class) =====

    @Test
    void listenerErrorsWarnOncePerListenerClass() throws Exception {
        HttpTransport delegate = mock(HttpTransport.class);
        HttpRequest request = sampleRequest();
        HttpResponse response = sampleResponse(200);
        when(delegate.execute(request)).thenReturn(response);

        LoggingHttpTransport transport =
                new LoggingHttpTransport(
                        delegate,
                        HttpTransportConfig.builder()
                                .listeners(new ThrowingListenerA(), new ThrowingListenerB())
                                .build());
        assertSame(response, transport.execute(request));

        List<ILoggingEvent> warns =
                logEvents.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(2, warns.size());
        assertTrue(warns.get(0).getFormattedMessage().contains(ThrowingListenerA.class.getName()));
        assertTrue(warns.get(1).getFormattedMessage().contains(ThrowingListenerB.class.getName()));
    }

    private static class ThrowingListenerA implements HttpTransportListener {
        @Override
        public void onRequest(HttpRequest request) {
            throw new IllegalStateException("a-request");
        }

        @Override
        public void onResponse(HttpRequest request, HttpResponse response, long durationNanos) {
            throw new IllegalStateException("a-response");
        }
    }

    private static class ThrowingListenerB implements HttpTransportListener {
        @Override
        public void onRequest(HttpRequest request) {
            throw new IllegalStateException("b-request");
        }

        @Override
        public void onResponse(HttpRequest request, HttpResponse response, long durationNanos) {
            throw new IllegalStateException("b-response");
        }
    }
}
