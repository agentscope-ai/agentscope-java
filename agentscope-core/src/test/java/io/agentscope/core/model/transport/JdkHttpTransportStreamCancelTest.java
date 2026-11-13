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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Regression tests for stream cancellation against a body that stops sending data without closing
 * the connection.
 *
 * <p>Two properties matter and both are asserted here: the cancellation chain must return without
 * waiting for the parked {@code readLine()}, and the response body must actually be closed so the
 * connection and the reading thread are released. A test that only waits for the fallback would
 * pass even when the close is silently lost.
 */
class JdkHttpTransportStreamCancelTest {

    @Test
    @Timeout(30)
    void timeoutFallbackIsDeliveredWhenServerStallsMidStream() throws Exception {
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
                            .responseTimeout(Duration.ofSeconds(5))
                            .streamIdleTimeout(Duration.ofSeconds(30))
                            .build();

            JdkHttpTransport transport = new JdkHttpTransport(config);
            try {
                HttpRequest request =
                        HttpRequest.builder()
                                .url("http://127.0.0.1:" + server.getLocalPort() + "/stream")
                                .method("POST")
                                .header("Content-Type", "application/json")
                                .body("{}")
                                .build();

                AtomicReference<Throwable> error = new AtomicReference<>();
                CountDownLatch terminal = new CountDownLatch(1);

                transport.stream(request)
                        .timeout(
                                Duration.ofMillis(500),
                                Flux.error(new IllegalStateException("MOCK_TIMEOUT")))
                        .subscribe(
                                ignored -> {},
                                failure -> {
                                    error.set(failure);
                                    terminal.countDown();
                                },
                                terminal::countDown);

                assertTrue(
                        terminal.await(10, TimeUnit.SECONDS),
                        "cancellation must complete so the timeout fallback can be delivered");
                assertNotNull(error.get(), "the fallback error must reach the subscriber");
                assertEquals("MOCK_TIMEOUT", error.get().getMessage());
                assertTrue(
                        peerSawEof.await(5, TimeUnit.SECONDS),
                        "cancelling the stream must close the response body, not just return");
            } finally {
                transport.close();
                Socket socket = accepted.get();
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    @Test
    @Timeout(30)
    void cancelClosesTheStalledConnection() throws Exception {
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
                            .responseTimeout(Duration.ofSeconds(30))
                            .streamIdleTimeout(Duration.ofSeconds(30))
                            .build();

            JdkHttpTransport transport = new JdkHttpTransport(config);
            try {
                HttpRequest request =
                        HttpRequest.builder()
                                .url("http://127.0.0.1:" + server.getLocalPort() + "/stream")
                                .method("POST")
                                .header("Content-Type", "application/json")
                                .body("{}")
                                .build();

                CountDownLatch gotChunk = new CountDownLatch(1);
                Disposable subscription =
                        transport.stream(request).subscribe(ignored -> gotChunk.countDown());

                assertTrue(gotChunk.await(5, TimeUnit.SECONDS), "the first chunk must arrive");
                long startedAt = System.nanoTime();
                subscription.dispose();
                long disposeMs = (System.nanoTime() - startedAt) / 1_000_000;

                assertTrue(
                        disposeMs < 2_000,
                        "dispose must not block behind the parked read, took " + disposeMs + "ms");
                assertTrue(
                        peerSawEof.await(5, TimeUnit.SECONDS),
                        "dispose must close the response body so the peer sees EOF");
            } finally {
                transport.close();
                Socket socket = accepted.get();
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    /** Sends SSE headers and one data chunk, then keeps the connection open without more data. */
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

            // The POST body is already buffered, so this read drains it and then blocks until the
            // client closes the connection - the observable effect of a real cancellation.
            socket.setSoTimeout(10_000);
            InputStream in = socket.getInputStream();
            try {
                while (in.read() != -1) {
                    // drain the request body, then wait for the peer's FIN
                }
                peerSawEof.countDown();
            } catch (SocketTimeoutException ignored) {
                // The client never closed; the assertion in the test reports it.
            }
        } catch (IOException ignored) {
            // The test closes the socket during teardown.
        }
    }
}
