/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.transport.WebSocketTransport;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebSocketConnectCancellationTest {

    private static final WebSocketRequest REQUEST =
            WebSocketRequest.builder("ws://localhost/fixture").build();

    @Test
    void jdkCancellationCancelsPendingHandshake() {
        CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
        var subscription = jdk(handshake).connect(REQUEST, String.class).subscribe();
        subscription.dispose();
        assertTrue(handshake.isCancelled());
    }

    @Test
    void jdkCancellationAbortsHandshakeThatCompletesLate() {
        CompletableFuture<WebSocket> handshake =
                new CompletableFuture<>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        return false;
                    }
                };
        WebSocket socket = mock(WebSocket.class);
        var subscription = jdk(handshake).connect(REQUEST, String.class).subscribe();
        subscription.dispose();
        handshake.complete(socket);
        verify(socket).abort();
    }

    @Test
    void successfulJdkConnectionStaysOwnedByCaller() {
        WebSocket socket = mock(WebSocket.class);
        var subscription =
                jdk(CompletableFuture.completedFuture(socket))
                        .connect(REQUEST, String.class)
                        .subscribe(connection -> assertNotNull(connection));
        subscription.dispose();
        verify(socket, never()).abort();
    }

    @Test
    void jdkCancellationReleasesConnectionWaitingForDemand() {
        WebSocket socket = mock(WebSocket.class);
        reactor.test.StepVerifier.create(
                        jdk(CompletableFuture.completedFuture(socket))
                                .connect(REQUEST, String.class),
                        0)
                .thenCancel()
                .verify();
        verify(socket).abort();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationClosesStalledHandshakeSocket(boolean useJdk) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var worker = Executors.newSingleThreadExecutor();
            CountDownLatch requestReceived = new CountDownLatch(1);
            AtomicReference<Socket> accepted = new AtomicReference<>();
            OkHttpClient http = new OkHttpClient();
            WebSocketTransport transport =
                    useJdk ? JdkWebSocketTransport.create() : OkHttpWebSocketTransport.create(http);
            reactor.core.Disposable subscription = null;
            var disconnected =
                    worker.submit(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    accepted.set(socket);
                                    socket.setSoTimeout(5000);
                                    BufferedReader input =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            socket.getInputStream(),
                                                            StandardCharsets.US_ASCII));
                                    String line;
                                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                                    requestReceived.countDown();
                                    try {
                                        return input.read() == -1;
                                    } catch (SocketException reset) {
                                        // Windows may report a connection reset instead of EOF.
                                        return true;
                                    }
                                }
                            });
            try {
                subscription =
                        transport
                                .connect(
                                        WebSocketRequest.builder(
                                                        "ws://127.0.0.1:"
                                                                + server.getLocalPort()
                                                                + "/ws")
                                                .build(),
                                        String.class)
                                .subscribe(ignored -> {}, error -> {});
                assertTrue(requestReceived.await(5, TimeUnit.SECONDS));
                subscription.dispose();
                assertTrue(
                        disconnected.get(5, TimeUnit.SECONDS),
                        "cancellation must close the pending handshake connection");
            } finally {
                if (subscription != null) subscription.dispose();
                if (accepted.get() != null) accepted.get().close();
                server.close();
                transport.shutdown();
                http.dispatcher().executorService().shutdownNow();
                http.connectionPool().evictAll();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private JdkWebSocketTransport jdk(CompletableFuture<WebSocket> handshake) {
        HttpClient client = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(handshake);
        return JdkWebSocketTransport.create(client);
    }
}
