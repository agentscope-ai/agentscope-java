/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.a2a.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StreamTrackingHttpClient Tests")
class StreamTrackingHttpClientTest {

    @Test
    void cancelsStreamRegisteredAfterCallCleanup() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.PostBuilder post = mock(A2AHttpClient.PostBuilder.class);
        when(delegate.createPost()).thenReturn(post);
        CompletableFuture<Void> stream = new CompletableFuture<>();
        StreamTrackingHttpClient client = new StreamTrackingHttpClient(delegate);
        when(post.postAsyncSSE(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            client.cancelStreams();
                            return stream;
                        });

        client.createPost().postAsyncSSE(event -> {}, error -> {}, () -> {});

        assertTrue(
                stream.isCancelled(), "call cleanup must also close a stream returned afterward");
    }

    @Test
    void cancelsGetStreamStartedAfterCleanupAndKeepsCompletedFuture() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.GetBuilder get = mock(A2AHttpClient.GetBuilder.class);
        when(delegate.createGet()).thenReturn(get);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        CompletableFuture<Void> completed = CompletableFuture.completedFuture(null);
        when(get.getAsyncSSE(any(), any(), any())).thenReturn(pending, completed);
        StreamTrackingHttpClient client = new StreamTrackingHttpClient(delegate);

        client.cancelStreams();
        client.cancelStreams();
        assertSame(pending, client.createGet().getAsyncSSE(event -> {}, error -> {}, () -> {}));
        assertTrue(pending.isCancelled());
        assertSame(completed, client.createGet().getAsyncSSE(event -> {}, error -> {}, () -> {}));
        assertFalse(completed.isCancelled());
    }

    @Test
    void cancelsFutureReturnedByConcurrentRegistration() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.PostBuilder post = mock(A2AHttpClient.PostBuilder.class);
        when(delegate.createPost()).thenReturn(post);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> stream = new CompletableFuture<>();
        when(post.postAsyncSSE(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return stream;
                        });
        StreamTrackingHttpClient client = new StreamTrackingHttpClient(delegate);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var opening =
                    executor.submit(
                            () ->
                                    client.createPost()
                                            .postAsyncSSE(event -> {}, error -> {}, () -> {}));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            client.cancelStreams();
            release.countDown();
            assertSame(stream, opening.get(5, TimeUnit.SECONDS));
            assertTrue(stream.isCancelled());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Should close an open event stream when its streams are cancelled")
    void testCancelStreamsClosesOpenStream() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        // Stream until the client goes away.
                        for (int i = 0; ; i++) {
                            out.write(("data: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            Thread.sleep(20);
                        }
                    } catch (Exception e) {
                        disconnected.countDown();
                    }
                });
        server.start();
        try {
            StreamTrackingHttpClient client = new StreamTrackingHttpClient(new JdkA2AHttpClient());
            AtomicInteger received = new AtomicInteger();
            CountDownLatch firstEvents = new CountDownLatch(3);
            CompletableFuture<Void> stream =
                    client.createPost()
                            .url("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                            .body("{}")
                            .postAsyncSSE(
                                    event -> {
                                        received.incrementAndGet();
                                        firstEvents.countDown();
                                    },
                                    error -> {},
                                    () -> {});
            assertTrue(firstEvents.await(5, TimeUnit.SECONDS), "the stream should deliver events");

            assertFalse(stream.isDone());
            client.cancelStreams();

            assertTrue(stream.isDone());
            assertTrue(
                    disconnected.await(5, TimeUnit.SECONDS),
                    "the server should see the connection closed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Should pass the requests to the delegate and forget ended streams")
    void testDelegatesRequests() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.PostBuilder post = mock(A2AHttpClient.PostBuilder.class);
        A2AHttpClient.GetBuilder get = mock(A2AHttpClient.GetBuilder.class);
        A2AHttpClient.DeleteBuilder delete = mock(A2AHttpClient.DeleteBuilder.class);
        A2AHttpResponse response = mock(A2AHttpResponse.class);
        CompletableFuture<Void> ended = CompletableFuture.completedFuture(null);
        when(delegate.createPost()).thenReturn(post);
        when(delegate.createGet()).thenReturn(get);
        when(delegate.createDelete()).thenReturn(delete);
        when(post.post()).thenReturn(response);
        when(get.get()).thenReturn(response);
        when(post.postAsyncSSE(any(), any(), any())).thenReturn(ended);
        when(get.getAsyncSSE(any(), any(), any())).thenReturn(ended);
        StreamTrackingHttpClient client = new StreamTrackingHttpClient(delegate);

        A2AHttpClient.PostBuilder postBuilder =
                client.createPost()
                        .url("http://post")
                        .addHeaders(Map.of("a", "1"))
                        .addHeader("b", "2")
                        .body("{}")
                        .followRedirects(true);
        assertSame(response, postBuilder.post());
        assertSame(ended, postBuilder.postAsyncSSE(event -> {}, error -> {}, () -> {}));
        A2AHttpClient.GetBuilder getBuilder =
                client.createGet()
                        .url("http://get")
                        .addHeaders(Map.of("c", "3"))
                        .addHeader("d", "4")
                        .followRedirects(true);
        assertSame(response, getBuilder.get());
        assertSame(ended, getBuilder.getAsyncSSE(event -> {}, error -> {}, () -> {}));
        assertSame(delete, client.createDelete());

        verify(post).url("http://post");
        verify(post).addHeaders(Map.of("a", "1"));
        verify(post).addHeader("b", "2");
        verify(post).body("{}");
        verify(post).followRedirects(true);
        verify(get).url("http://get");
        verify(get).addHeaders(Map.of("c", "3"));
        verify(get).addHeader("d", "4");
        verify(get).followRedirects(true);
        // The streams already ended, so cancelling has nothing to cancel.
        client.cancelStreams();
        assertFalse(ended.isCancelled());
    }
}
