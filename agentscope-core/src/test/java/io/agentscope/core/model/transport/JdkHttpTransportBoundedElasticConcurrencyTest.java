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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Opt-in integration test that verifies concurrent SSE streams do not retain workers from the
 * shared bounded elastic scheduler while waiting for network data.
 *
 * <p>Run this test in a fresh JVM. For example:
 *
 * <pre>{@code
 * mvn -pl agentscope-core -am \
 *   -Dtest=JdkHttpTransportBoundedElasticConcurrencyTest \
 *   -Dagentscope.test.sse.concurrency.enabled=true \
 *   -Dagentscope.test.sse.requests=40 \
 *   -Dreactor.schedulers.defaultBoundedElasticSize=20 \
 *   -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=false \
 *   -DforkCount=1 -DreuseForks=false test
 * }</pre>
 */
@EnabledIfSystemProperty(named = "agentscope.test.sse.concurrency.enabled", matches = "true")
class JdkHttpTransportBoundedElasticConcurrencyTest {

    private static final String REQUEST_COUNT_PROPERTY = "agentscope.test.sse.requests";
    private static final String CHUNK_COUNT_PROPERTY = "agentscope.test.sse.chunks";
    private static final String CHUNK_INTERVAL_PROPERTY = "agentscope.test.sse.chunkIntervalMillis";
    private static final String BOUNDED_ELASTIC_SIZE_PROPERTY =
            "reactor.schedulers.defaultBoundedElasticSize";
    private static final String BOUNDED_ELASTIC_VIRTUAL_THREADS_PROPERTY =
            "reactor.schedulers.defaultBoundedElasticOnVirtualThreads";

    private static final int DEFAULT_REQUEST_COUNT = 40;
    private static final int DEFAULT_CHUNK_COUNT = 30;
    private static final long DEFAULT_CHUNK_INTERVAL_MILLIS = 1_000L;
    private static final long EARLY_WINDOW_MILLIS = 5_000L;
    private static final long MAX_CANARY_DELAY_MILLIS = 2_000L;

    private final AtomicInteger mockThreadId = new AtomicInteger();
    private final AtomicInteger serverAccepted = new AtomicInteger();
    private final AtomicInteger completedRequests = new AtomicInteger();
    private final AtomicLong canaryDelayMillis = new AtomicLong(-1L);
    private final AtomicReference<Throwable> clientFailure = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Throwable> serverFailures = new ConcurrentLinkedQueue<>();
    private final Map<Integer, AtomicInteger> chunkCounts = new ConcurrentHashMap<>();
    private final Map<Integer, Long> firstChunkTimesMillis = new ConcurrentHashMap<>();
    private final Set<Integer> firstChunkRequests = ConcurrentHashMap.newKeySet();
    private final Set<String> readerThreads = ConcurrentHashMap.newKeySet();

    private int requestCount;
    private int chunkCount;
    private int boundedElasticSize;
    private long chunkIntervalMillis;
    private long testStartNanos;
    private String endpoint;
    private HttpServer server;
    private ExecutorService modelExecutor;
    private ScheduledExecutorService monitorExecutor;
    private JdkHttpTransport transport;
    private CountDownLatch acceptedLatch;
    private CountDownLatch initialWaveLatch;
    private CountDownLatch firstChunkLatch;
    private CountDownLatch streamsTerminatedLatch;
    private CountDownLatch canaryExecutedLatch;
    private Disposable allStreams;

    @BeforeEach
    void setUp() throws Exception {
        requestCount = Integer.getInteger(REQUEST_COUNT_PROPERTY, DEFAULT_REQUEST_COUNT);
        chunkCount = Integer.getInteger(CHUNK_COUNT_PROPERTY, DEFAULT_CHUNK_COUNT);
        chunkIntervalMillis = Long.getLong(CHUNK_INTERVAL_PROPERTY, DEFAULT_CHUNK_INTERVAL_MILLIS);

        assertTrue(requestCount > 0, REQUEST_COUNT_PROPERTY + " must be greater than zero");
        assertTrue(chunkCount > 0, CHUNK_COUNT_PROPERTY + " must be greater than zero");
        assertTrue(chunkIntervalMillis > 0, CHUNK_INTERVAL_PROPERTY + " must be greater than zero");

        String configuredSize = System.getProperty(BOUNDED_ELASTIC_SIZE_PROPERTY);
        assertFalse(
                configuredSize == null || configuredSize.isBlank(),
                "Run the test with -D" + BOUNDED_ELASTIC_SIZE_PROPERTY + "=<size>");

        int requestedBoundedElasticSize = Integer.parseInt(configuredSize);
        boundedElasticSize = Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE;
        assertEquals(
                requestedBoundedElasticSize,
                boundedElasticSize,
                "The boundedElastic size was initialized before the requested JVM property took"
                        + " effect. Run this test in a fresh forked JVM.");
        assertFalse(
                Boolean.getBoolean(BOUNDED_ELASTIC_VIRTUAL_THREADS_PROPERTY),
                "This experiment requires the platform-thread boundedElastic implementation");

        acceptedLatch = new CountDownLatch(requestCount);
        initialWaveLatch = new CountDownLatch(Math.min(requestCount, boundedElasticSize));
        firstChunkLatch = new CountDownLatch(requestCount);
        streamsTerminatedLatch = new CountDownLatch(1);
        canaryExecutedLatch = new CountDownLatch(1);
        testStartNanos = System.nanoTime();

        startMockModelServer();

        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .responseTimeout(Duration.ofMinutes(2))
                        .streamIdleTimeout(Duration.ofSeconds(5))
                        .httpVersion(HttpVersion.HTTP_1_1)
                        .build();
        transport = JdkHttpTransport.builder().config(config).build();

        monitorExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "sse-probe-monitor");
                            thread.setDaemon(true);
                            return thread;
                        });
        monitorExecutor.scheduleAtFixedRate(this::printProgress, 0, 1, TimeUnit.SECONDS);

        event(
                "CONFIG",
                "pid=%d requests=%d chunks=%d intervalMs=%d boundedElasticSize=%d"
                        + " virtualThreads=%s",
                ProcessHandle.current().pid(),
                requestCount,
                chunkCount,
                chunkIntervalMillis,
                boundedElasticSize,
                System.getProperty(BOUNDED_ELASTIC_VIRTUAL_THREADS_PROPERTY, "false"));
    }

    @AfterEach
    void tearDown() {
        if (allStreams != null) {
            allStreams.dispose();
        }
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (modelExecutor != null) {
            modelExecutor.shutdownNow();
        }
    }

    @Test
    void concurrentSseStreamsDoNotExhaustBoundedElasticCapacity() throws Exception {
        allStreams =
                Flux.range(1, requestCount)
                        .flatMap(requestId -> runOneStream(requestId).then(), requestCount)
                        .then()
                        .subscribe(
                                ignored -> {},
                                error -> {
                                    clientFailure.compareAndSet(null, error);
                                    event(
                                            "CLIENT_ERROR",
                                            "type=%s message=%s",
                                            error.getClass().getName(),
                                            error.getMessage());
                                    streamsTerminatedLatch.countDown();
                                },
                                () -> {
                                    event(
                                            "ALL_STREAMS_COMPLETE",
                                            "completed=%d elapsedMs=%d",
                                            completedRequests.get(),
                                            elapsedMillis());
                                    streamsTerminatedLatch.countDown();
                                });

        assertTrue(
                acceptedLatch.await(10, TimeUnit.SECONDS),
                () ->
                        "The mock model accepted only "
                                + serverAccepted.get()
                                + " of "
                                + requestCount
                                + " requests");
        assertEquals(requestCount, serverAccepted.get());

        assertTrue(
                initialWaveLatch.await(10, TimeUnit.SECONDS),
                "The initial reader wave did not receive its first chunks");
        enqueueBoundedElasticCanary();

        // Sample between model emissions. A non-blocking implementation may briefly dispatch
        // signals on boundedElastic, but no worker should wait inside BufferedReader.readLine().
        Thread.sleep(Math.min(2_500L, Math.max(250L, chunkIntervalMillis / 2)));

        Set<String> blockedReaders = captureBlockedReaderThreads();
        event(
                "THREAD_DUMP_SUMMARY",
                "blockedReaders=%d names=%s",
                blockedReaders.size(),
                blockedReaders.stream().sorted().toList());

        boolean allStreamsStartedInEarlyWindow =
                firstChunkLatch.await(EARLY_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        int firstChunksInEarlyWindow = firstChunkRequests.size();
        event(
                "EARLY_WINDOW",
                "allStarted=%s firstChunks=%d expected=%d",
                allStreamsStartedInEarlyWindow,
                firstChunksInEarlyWindow,
                requestCount);

        long streamDurationMillis = Math.multiplyExact(chunkCount, chunkIntervalMillis);
        long completionTimeoutMillis = Math.max(20_000L, streamDurationMillis + 20_000L);
        assertTrue(
                streamsTerminatedLatch.await(completionTimeoutMillis, TimeUnit.MILLISECONDS),
                () ->
                        "Streams did not terminate within "
                                + completionTimeoutMillis
                                + " ms; completed="
                                + completedRequests.get());

        assertNull(clientFailure.get(), () -> "Client stream failed: " + clientFailure.get());
        assertTrue(serverFailures.isEmpty(), () -> "Mock model failed: " + serverFailures.peek());
        assertEquals(requestCount, completedRequests.get());
        assertEquals(requestCount, firstChunkTimesMillis.size());
        chunkCounts.forEach(
                (requestId, count) ->
                        assertEquals(
                                chunkCount,
                                count.get(),
                                () -> "Unexpected chunk count for request " + requestId));

        assertTrue(
                canaryExecutedLatch.await(5, TimeUnit.SECONDS),
                "The boundedElastic canary never executed");

        List<Long> sortedFirstChunkTimes =
                firstChunkTimesMillis.values().stream().sorted(Comparator.naturalOrder()).toList();
        long slowestFirstChunkMillis = sortedFirstChunkTimes.get(sortedFirstChunkTimes.size() - 1);
        printResultSummary(sortedFirstChunkTimes, blockedReaders.size());

        assertTrue(
                allStreamsStartedInEarlyWindow,
                () ->
                        "Only "
                                + firstChunksInEarlyWindow
                                + " streams received a first chunk within the early window;"
                                + " expected "
                                + requestCount);
        assertEquals(
                0,
                blockedReaders.size(),
                "No boundedElastic worker should be blocked in BufferedReader.readLine()");
        assertTrue(
                slowestFirstChunkMillis < EARLY_WINDOW_MILLIS,
                () ->
                        "A stream took too long to receive its first chunk: "
                                + slowestFirstChunkMillis
                                + " ms");

        assertTrue(
                canaryDelayMillis.get() < MAX_CANARY_DELAY_MILLIS,
                () ->
                        "The scheduler canary should execute promptly but queued for "
                                + canaryDelayMillis.get()
                                + " ms");
    }

    private void startMockModelServer() throws IOException {
        modelExecutor =
                Executors.newFixedThreadPool(
                        requestCount + 8,
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "mock-model-" + mockThreadId.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(modelExecutor);
        server.createContext("/v1/chat/completions", this::handleMockModelRequest);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private void handleMockModelRequest(HttpExchange exchange) throws IOException {
        String requestId = exchange.getRequestHeaders().getFirst("X-Test-Request-Id");
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);

        int accepted = serverAccepted.incrementAndGet();
        acceptedLatch.countDown();
        event(
                "SERVER_ACCEPT",
                "request=%s accepted=%d thread=%s",
                requestId,
                accepted,
                Thread.currentThread().getName());

        try (OutputStream output = exchange.getResponseBody()) {
            for (int sequence = 1; sequence <= chunkCount; sequence++) {
                Thread.sleep(chunkIntervalMillis);
                String data =
                        "data: {\"requestId\":\"" + requestId + "\",\"seq\":" + sequence + "}\n\n";
                output.write(data.getBytes(StandardCharsets.UTF_8));
                output.flush();

                if (sequence == 1 || sequence == 10 || sequence == 20 || sequence == chunkCount) {
                    event(
                            "SERVER_SEND",
                            "request=%s seq=%d elapsedMs=%d",
                            requestId,
                            sequence,
                            elapsedMillis());
                }
            }
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            serverFailures.add(e);
            event("SERVER_INTERRUPTED", "request=%s", requestId);
        } catch (IOException e) {
            serverFailures.add(e);
            event("SERVER_ERROR", "request=%s message=%s", requestId, e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private Flux<String> runOneStream(int requestId) {
        HttpRequest request =
                HttpRequest.builder()
                        .url(endpoint)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("X-Test-Request-Id", Integer.toString(requestId))
                        .body("{\"stream\":true}")
                        .build();
        AtomicLong subscribedNanos = new AtomicLong();

        return transport.stream(request)
                .doOnSubscribe(
                        ignored -> {
                            subscribedNanos.set(System.nanoTime());
                            event(
                                    "CLIENT_SUBSCRIBE",
                                    "request=%d elapsedMs=%d",
                                    requestId,
                                    elapsedMillis());
                        })
                .doOnNext(
                        data -> {
                            int received =
                                    chunkCounts
                                            .computeIfAbsent(
                                                    requestId, ignored -> new AtomicInteger())
                                            .incrementAndGet();
                            String threadName = Thread.currentThread().getName();
                            readerThreads.add(threadName);

                            if (received == 1 && firstChunkRequests.add(requestId)) {
                                initialWaveLatch.countDown();
                                long firstChunkMillis =
                                        TimeUnit.NANOSECONDS.toMillis(
                                                System.nanoTime() - subscribedNanos.get());
                                firstChunkTimesMillis.put(requestId, firstChunkMillis);
                                firstChunkLatch.countDown();
                                event(
                                        "CLIENT_FIRST_CHUNK",
                                        "request=%d firstChunkMs=%d thread=%s data=%s",
                                        requestId,
                                        firstChunkMillis,
                                        threadName,
                                        data);
                            }
                        })
                .doOnComplete(
                        () -> {
                            int completed = completedRequests.incrementAndGet();
                            int received =
                                    chunkCounts.getOrDefault(requestId, new AtomicInteger()).get();
                            event(
                                    "CLIENT_COMPLETE",
                                    "request=%d chunks=%d completed=%d elapsedMs=%d",
                                    requestId,
                                    received,
                                    completed,
                                    elapsedMillis());
                        });
    }

    private void enqueueBoundedElasticCanary() {
        long enqueuedNanos = System.nanoTime();
        event("CANARY_ENQUEUE", "elapsedMs=%d", elapsedMillis());
        Schedulers.boundedElastic()
                .schedule(
                        () -> {
                            long delayMillis =
                                    TimeUnit.NANOSECONDS.toMillis(
                                            System.nanoTime() - enqueuedNanos);
                            canaryDelayMillis.set(delayMillis);
                            event(
                                    "CANARY_EXECUTE",
                                    "queueDelayMs=%d thread=%s",
                                    delayMillis,
                                    Thread.currentThread().getName());
                            canaryExecutedLatch.countDown();
                        });
    }

    private Set<String> captureBlockedReaderThreads() throws InterruptedException {
        Set<String> blockedReaders = ConcurrentHashMap.newKeySet();

        // A few samples avoid landing exactly in the small window where a worker is delivering a
        // chunk downstream instead of waiting for the next BufferedReader.readLine() call.
        for (int sample = 0; sample < 10; sample++) {
            Thread.getAllStackTraces()
                    .forEach(
                            (thread, stack) -> {
                                if (thread.getName().startsWith("boundedElastic-")
                                        && containsBufferedReaderRead(stack)) {
                                    blockedReaders.add(thread.getName());
                                }
                            });
            Thread.sleep(50L);
        }
        return blockedReaders;
    }

    private boolean containsBufferedReaderRead(StackTraceElement[] stack) {
        return Arrays.stream(stack)
                .anyMatch(
                        frame ->
                                frame.getClassName().equals("java.io.BufferedReader")
                                        && frame.getMethodName().startsWith("readLine"));
    }

    private void printProgress() {
        event(
                "MONITOR",
                "accepted=%d firstChunks=%d completed=%d readerThreads=%d",
                serverAccepted.get(),
                firstChunkRequests.size(),
                completedRequests.get(),
                readerThreads.size());
    }

    private void printResultSummary(List<Long> sortedFirstChunkTimes, int blockedReaderCount) {
        long fastest = sortedFirstChunkTimes.get(0);
        long slowest = sortedFirstChunkTimes.get(sortedFirstChunkTimes.size() - 1);

        event(
                "RESULT",
                "pool=%d requests=%d fastestFirstChunkMs=%d slowestFirstChunkMs=%d "
                        + "canaryDelayMs=%d signalThreads=%d blockedReaders=%d",
                boundedElasticSize,
                requestCount,
                fastest,
                slowest,
                canaryDelayMillis.get(),
                readerThreads.size(),
                blockedReaderCount);
    }

    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - testStartNanos);
    }

    private synchronized void event(String type, String format, Object... arguments) {
        String message = String.format(Locale.ROOT, format, arguments);
        System.out.printf(
                Locale.ROOT, "[SSE-PROBE][%6dms][%-19s] %s%n", elapsedMillis(), type, message);
    }
}
