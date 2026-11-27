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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class HttpTransportStreamSchedulerTest {

    @Test
    void streamSchedulerRejectsNull() {
        assertThrows(
                NullPointerException.class, () -> JdkHttpTransport.builder().streamScheduler(null));
        assertThrows(
                NullPointerException.class, () -> OkHttpTransport.builder().streamScheduler(null));
    }

    @Test
    void customStreamSchedulerExecutesReadsAndRemainsCallerOwned() throws Exception {
        for (boolean jdk : List.of(true, false)) {
            Scheduler customScheduler = Schedulers.newSingle("custom-stream-scheduler");
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(
                        new MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody("data: chunk-1\n\ndata: chunk-2\n\ndata: [DONE]\n\n"));

                HttpTransport transport = buildTransport(jdk, customScheduler);
                try {
                    HttpRequest request =
                            HttpRequest.builder()
                                    .url(server.url("/stream").toString())
                                    .method("GET")
                                    .build();
                    List<String> threadNames = new CopyOnWriteArrayList<>();
                    List<String> chunks =
                            transport.stream(request)
                                    .doOnNext(
                                            chunk ->
                                                    threadNames.add(
                                                            Thread.currentThread().getName()))
                                    .collectList()
                                    .block(Duration.ofSeconds(5));

                    assertEquals(List.of("chunk-1", "chunk-2"), chunks);
                    assertFalse(threadNames.isEmpty());
                    for (String name : threadNames) {
                        assertNotNull(name);
                        assertTrue(
                                name.startsWith("custom-stream-scheduler"),
                                "Expected custom-stream-scheduler thread, got: " + name);
                    }
                } finally {
                    transport.close();
                }
                assertFalse(
                        customScheduler.isDisposed(),
                        "Transport close() must not dispose caller-owned streamScheduler");
            } finally {
                customScheduler.dispose();
            }
        }
    }

    @Test
    void customStreamSchedulerHandlesHttpErrorResponse() throws Exception {
        for (boolean jdk : List.of(true, false)) {
            Scheduler customScheduler = Schedulers.newSingle("custom-stream-error");
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream failure"));

                HttpTransport transport = buildTransport(jdk, customScheduler);
                try {
                    HttpRequest request =
                            HttpRequest.builder()
                                    .url(server.url("/stream").toString())
                                    .method("GET")
                                    .build();
                    StepVerifier.create(transport.stream(request))
                            .expectErrorMatches(
                                    error ->
                                            error instanceof HttpTransportException hte
                                                    && Integer.valueOf(500)
                                                            .equals(hte.getStatusCode())
                                                    && "upstream failure"
                                                            .equals(hte.getResponseBody()))
                            .verify(Duration.ofSeconds(5));
                } finally {
                    transport.close();
                }
                assertFalse(
                        customScheduler.isDisposed(),
                        "Transport close() must not dispose caller-owned streamScheduler");
            } finally {
                customScheduler.dispose();
            }
        }
    }

    @Test
    @Timeout(30)
    void jdkCustomStreamSchedulerCancelsStalledSseCleanly() throws Exception {
        Scheduler customScheduler = Schedulers.newSingle("custom-stream-cancel");
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Socket> accepted = new AtomicReference<>();
            CountDownLatch peerSawEof = new CountDownLatch(1);
            Thread writer =
                    new Thread(
                            () -> serveOneChunkThenStall(server, accepted, peerSawEof),
                            "stalling-sse-server");
            writer.setDaemon(true);
            writer.start();

            HttpTransportConfig config =
                    HttpTransportConfig.builder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .readTimeout(Duration.ofSeconds(30))
                            .responseTimeout(Duration.ofSeconds(5))
                            .streamIdleTimeout(Duration.ofSeconds(30))
                            .build();
            JdkHttpTransport transport =
                    JdkHttpTransport.builder()
                            .config(config)
                            .streamScheduler(customScheduler)
                            .build();
            try {
                HttpRequest request =
                        HttpRequest.builder()
                                .url("http://127.0.0.1:" + server.getLocalPort() + "/stream")
                                .method("POST")
                                .header("Content-Type", "application/json")
                                .body("{}")
                                .build();

                CountDownLatch gotChunk = new CountDownLatch(1);
                AtomicReference<String> chunkThread = new AtomicReference<>();
                AtomicReference<String> chunkValue = new AtomicReference<>();
                Disposable subscription =
                        transport.stream(request)
                                .subscribe(
                                        chunk -> {
                                            chunkValue.set(chunk);
                                            chunkThread.set(Thread.currentThread().getName());
                                            gotChunk.countDown();
                                        });

                assertTrue(gotChunk.await(5, TimeUnit.SECONDS), "first chunk must arrive");
                assertEquals("first", chunkValue.get());
                assertNotNull(chunkThread.get());
                assertTrue(
                        chunkThread.get().startsWith("custom-stream-cancel"),
                        "Expected custom-stream-cancel thread, got: " + chunkThread.get());

                long startedAt = System.nanoTime();
                subscription.dispose();
                long disposeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                assertTrue(
                        disposeMs < 2_000,
                        "dispose must not block behind stalled SSE read, took " + disposeMs + "ms");
                assertTrue(
                        peerSawEof.await(5, TimeUnit.SECONDS),
                        "cancelling stream must close underlying connection");
            } finally {
                transport.close();
                Socket socket = accepted.get();
                if (socket != null) {
                    socket.close();
                }
            }
            assertFalse(
                    customScheduler.isDisposed(),
                    "Transport close() must not dispose caller-owned streamScheduler");
        } finally {
            customScheduler.dispose();
        }
    }

    @Test
    @Timeout(30)
    void okhttpCustomStreamSchedulerCancelsSlowSseAndReleasesWorker() throws Exception {
        Scheduler customScheduler = Schedulers.newSingle("custom-stream-cancel");
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: first\n\ndata: second\n\ndata: [DONE]\n\n")
                            .throttleBody(16, 100, TimeUnit.MILLISECONDS));

            OkHttpTransport transport =
                    OkHttpTransport.builder().streamScheduler(customScheduler).build();
            try {
                HttpRequest request =
                        HttpRequest.builder()
                                .url(server.url("/stream").toString())
                                .method("GET")
                                .build();
                AtomicReference<String> chunkThread = new AtomicReference<>();
                List<String> chunks =
                        transport.stream(request)
                                .doOnNext(
                                        chunk -> chunkThread.set(Thread.currentThread().getName()))
                                .take(1)
                                .collectList()
                                .block(Duration.ofSeconds(5));

                assertEquals(List.of("first"), chunks);
                assertNotNull(chunkThread.get());
                assertTrue(
                        chunkThread.get().startsWith("custom-stream-cancel"),
                        "Expected custom-stream-cancel thread, got: " + chunkThread.get());

                CountDownLatch workerFree = new CountDownLatch(1);
                customScheduler.schedule(workerFree::countDown);
                assertTrue(
                        workerFree.await(3, TimeUnit.SECONDS),
                        "custom streamScheduler worker must be released after cancellation");
            } finally {
                transport.close();
            }
            assertFalse(
                    customScheduler.isDisposed(),
                    "Transport close() must not dispose caller-owned streamScheduler");
        } finally {
            customScheduler.dispose();
        }
    }

    private static HttpTransport buildTransport(boolean jdk, Scheduler streamScheduler) {
        return jdk
                ? JdkHttpTransport.builder().streamScheduler(streamScheduler).build()
                : OkHttpTransport.builder().streamScheduler(streamScheduler).build();
    }

    private static void serveOneChunkThenStall(
            ServerSocket server, AtomicReference<Socket> accepted, CountDownLatch peerSawEof) {
        try (Socket socket = server.accept()) {
            accepted.set(socket);
            OutputStream out = socket.getOutputStream();
            out.write(
                    ("HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/event-stream\r\n"
                                    + "Transfer-Encoding: chunked\r\n"
                                    + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            byte[] payload = "data: first\n\n".getBytes(StandardCharsets.UTF_8);
            out.write(
                    (Integer.toHexString(payload.length) + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            try {
                while (in.read() != -1) {
                    // Drain request body and wait for client close.
                }
                peerSawEof.countDown();
            } catch (SocketTimeoutException ignored) {
                // Assertion checks peerSawEof.
            }
        } catch (IOException ignored) {
            // Closed during teardown.
        }
    }
}
