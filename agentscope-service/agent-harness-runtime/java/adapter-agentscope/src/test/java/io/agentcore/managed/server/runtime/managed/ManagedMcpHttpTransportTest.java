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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManagedMcpHttpTransportTest {
    @Test
    void independentClientsShareWorkersAndPreserveRequestHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    var value = exchange.getRequestHeaders().getFirst("Authorization").getBytes();
                    exchange.sendResponseHeaders(200, value.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(value);
                    }
                });
        server.start();
        try {
            var responses = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            ThreadPoolExecutor shared = null;
            for (int i = 0; i < 80; i++) {
                var builder = HttpClient.newBuilder();
                ManagedMcpHttpTransport.customize(builder);
                var client = builder.build();
                var workers = (ThreadPoolExecutor) client.executor().orElseThrow();
                if (shared == null) shared = workers;
                else assertSame(shared, workers);
                var request =
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + server.getAddress().getPort()))
                                .header("Authorization", "tenant-" + i)
                                .build();
                responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new))
                    .get(20, TimeUnit.SECONDS);
            for (int i = 0; i < responses.size(); i++) {
                assertEquals(200, responses.get(i).join().statusCode());
                assertEquals("tenant-" + i, responses.get(i).join().body());
            }
            assertTrue(shared.getCompletedTaskCount() > 0);
            assertTrue(shared.getLargestPoolSize() <= 64);
        } finally {
            server.stop(0);
        }
    }
}
