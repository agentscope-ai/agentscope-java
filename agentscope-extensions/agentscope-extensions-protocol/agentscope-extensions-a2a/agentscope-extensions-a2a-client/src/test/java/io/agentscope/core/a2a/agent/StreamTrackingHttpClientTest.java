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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import io.a2a.client.http.JdkA2AHttpClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StreamTrackingHttpClientTest {

    @Test
    void tracksGetAndPostAndCancelsLateStreams() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.GetBuilder get = mock(A2AHttpClient.GetBuilder.class);
        A2AHttpClient.PostBuilder post = mock(A2AHttpClient.PostBuilder.class);
        when(delegate.createGet()).thenReturn(get);
        when(delegate.createPost()).thenReturn(post);
        CompletableFuture<Void> getStream = new CompletableFuture<>();
        CompletableFuture<Void> postStream = new CompletableFuture<>();
        when(get.getAsyncSSE(any(), any(), any())).thenReturn(getStream);
        when(post.postAsyncSSE(any(), any(), any())).thenReturn(postStream);
        StreamTrackingHttpClient client = new StreamTrackingHttpClient(delegate);
        Consumer<String> message = ignored -> {};
        Consumer<Throwable> error = ignored -> {};
        Runnable complete = () -> {};
        assertSame(
                getStream,
                client.createGet()
                        .url("local")
                        .addHeader("one", "1")
                        .addHeaders(Map.of("two", "2"))
                        .getAsyncSSE(message, error, complete));
        assertSame(
                postStream,
                client.createPost()
                        .url("local")
                        .body("{}")
                        .addHeader("one", "1")
                        .addHeaders(Map.of("two", "2"))
                        .postAsyncSSE(message, error, complete));
        verify(get).url("local");
        verify(get).addHeader("one", "1");
        verify(get).addHeaders(Map.of("two", "2"));
        verify(get).getAsyncSSE(message, error, complete);
        verify(post).body("{}");
        verify(post).postAsyncSSE(message, error, complete);
        getStream.complete(null);
        client.cancelStreams();
        client.cancelStreams();
        assertFalse(getStream.isCancelled());
        assertTrue(postStream.isCancelled());
        CompletableFuture<Void> late = new CompletableFuture<>();
        when(post.postAsyncSSE(any(), any(), any())).thenReturn(late);
        client.createPost().postAsyncSSE(message, error, complete);
        assertTrue(late.isCancelled());
    }

    @Test
    void ordinaryRequestsAreDelegated() throws Exception {
        A2AHttpClient delegate = mock(A2AHttpClient.class);
        A2AHttpClient.GetBuilder get = mock(A2AHttpClient.GetBuilder.class);
        A2AHttpClient.PostBuilder post = mock(A2AHttpClient.PostBuilder.class);
        A2AHttpClient.DeleteBuilder delete = mock(A2AHttpClient.DeleteBuilder.class);
        A2AHttpResponse response = mock(A2AHttpResponse.class);
        when(delegate.createGet()).thenReturn(get);
        when(delegate.createPost()).thenReturn(post);
        when(delegate.createDelete()).thenReturn(delete);
        when(get.get()).thenReturn(response);
        when(post.post()).thenReturn(response);
        StreamTrackingHttpClient client = new StreamTrackingHttpClient(delegate);
        assertSame(response, client.createGet().get());
        assertSame(response, client.createPost().post());
        assertSame(delete, client.createDelete());
    }

    @Test
    void defaultTransportCancelsRemoteTaskAndClosesClient() throws Exception {
        CountDownLatch taskReceived = new CountDownLatch(1);
        CountDownLatch remoteCancelled = new CountDownLatch(1);
        CountDownLatch finishStream = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<io.a2a.client.Client> builtClient =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> cancelledId =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/",
                exchange -> {
                    var mapper = io.a2a.util.Utils.OBJECT_MAPPER;
                    var request = mapper.readTree(exchange.getRequestBody());
                    if ("tasks/cancel".equals(request.path("method").asText())) {
                        cancelledId.set(request.path("params").path("id").asText());
                        var response = mapper.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", request.get("id"));
                        response.set(
                                "result",
                                mapper.valueToTree(
                                        new io.a2a.spec.Task.Builder()
                                                .id("local-task")
                                                .contextId("local-context")
                                                .status(
                                                        new io.a2a.spec.TaskStatus(
                                                                io.a2a.spec.TaskState.CANCELED))
                                                .build()));
                        byte[] body = mapper.writeValueAsBytes(response);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        } finally {
                            exchange.close();
                            remoteCancelled.countDown();
                        }
                    } else {
                        var response = mapper.createObjectNode();
                        response.put("jsonrpc", "2.0");
                        response.set("id", request.get("id"));
                        response.set(
                                "result",
                                mapper.valueToTree(
                                        new io.a2a.spec.Task.Builder()
                                                .id("local-task")
                                                .contextId("local-context")
                                                .status(
                                                        new io.a2a.spec.TaskStatus(
                                                                io.a2a.spec.TaskState.WORKING))
                                                .build()));
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        try (var output = exchange.getResponseBody()) {
                            output.write(
                                    ("data: " + mapper.writeValueAsString(response) + "\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            finishStream.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            exchange.close();
                        }
                    }
                });
        server.start();
        reactor.core.Disposable subscription = null;
        try {
            io.a2a.spec.AgentCard card = mock(io.a2a.spec.AgentCard.class);
            when(card.url()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            when(card.preferredTransport()).thenReturn("JSONRPC");
            when(card.capabilities())
                    .thenReturn(
                            new io.a2a.spec.AgentCapabilities.Builder().streaming(true).build());
            A2aAgent agent =
                    A2aAgent.builder()
                            .name("local-agent")
                            .agentCard(card)
                            .clientReplacer(
                                    client -> {
                                        var spy = org.mockito.Mockito.spy(client);
                                        builtClient.set(spy);
                                        return spy;
                                    })
                            .hook(
                                    new io.agentscope.core.hook.Hook() {
                                        @Override
                                        public <T extends io.agentscope.core.hook.HookEvent>
                                                reactor.core.publisher.Mono<T> onEvent(T event) {
                                            if (event
                                                    instanceof
                                                    io.agentscope.core.hook.PreReasoningEvent) {
                                                taskReceived.countDown();
                                            }
                                            return reactor.core.publisher.Mono.just(event);
                                        }
                                    })
                            .build();
            subscription =
                    agent.call(
                                    io.agentscope.core.message.Msg.builder()
                                            .textContent("local input")
                                            .build())
                            .subscribe(ignored -> {}, error::set);
            assertTrue(
                    taskReceived.await(5, TimeUnit.SECONDS),
                    "local task was not received: " + error.get());
            subscription.dispose();
            assertTrue(remoteCancelled.await(5, TimeUnit.SECONDS));
            assertEquals("local-task", cancelledId.get());
            verify(builtClient.get(), org.mockito.Mockito.timeout(5000)).close();
        } finally {
            if (subscription != null) {
                subscription.dispose();
            }
            finishStream.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualSdkSseStopsWhenTrackedFutureIsCancelled() throws Exception {
        for (boolean post : new boolean[] {false, true}) {
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch cancelled = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicInteger events = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/sse",
                    exchange -> {
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        try (var output = exchange.getResponseBody()) {
                            output.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            finish.await(5, TimeUnit.SECONDS);
                            output.write(
                                    "data: after cancellation\n\n"
                                            .getBytes(StandardCharsets.UTF_8));
                            output.flush();
                        } catch (Exception ignored) {
                            // Closing the tracked request may close the peer connection.
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
            StreamTrackingHttpClient client = new StreamTrackingHttpClient(new JdkA2AHttpClient());
            try {
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
                Consumer<String> message =
                        value -> {
                            events.incrementAndGet();
                            first.countDown();
                        };
                if (post) {
                    client.createPost()
                            .url(url)
                            .body("{}")
                            .postAsyncSSE(message, error -> cancelled.countDown(), () -> {});
                } else {
                    client.createGet()
                            .url(url)
                            .getAsyncSSE(message, error -> cancelled.countDown(), () -> {});
                }
                assertTrue(first.await(5, TimeUnit.SECONDS));
                client.cancelStreams();
                assertTrue(
                        cancelled.await(5, TimeUnit.SECONDS),
                        "SDK body subscription must be cancelled");
                finish.countDown();
                assertEquals(1, events.get());
            } finally {
                client.cancelStreams();
                finish.countDown();
                server.stop(0);
            }
        }
    }
}
