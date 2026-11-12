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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class S3RuntimeObjectStoreTest {
    @Test
    void compatibleEndpointMustEnforceConditionalCreationAndFixedHash() throws Exception {
        try (var endpoint = new Endpoint(true);
                var store =
                        new S3RuntimeObjectStore(
                                "s3", endpoint.url(), "bucket", "us-east-1", "access", "secret")) {
            var ref = store.put("scope", "native", "key", new byte[] {1, 2, 3});
            assertArrayEquals(new byte[] {1, 2, 3}, store.read(ref));
            store.put("scope", "native", "key", new byte[] {1, 2, 3});
            assertThrows(
                    ManagedStateException.class,
                    () -> store.put("scope", "native", "key", new byte[] {4}));
            endpoint.objects.put("/bucket/scope/native/key", new byte[] {4});
            assertThrows(ManagedStateException.class, () -> store.read(ref));
        }
    }

    @Test
    void endpointIgnoringConditionalHeadersFailsBeforeServingRecovery() throws Exception {
        try (var endpoint = new Endpoint(false)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new S3RuntimeObjectStore(
                                    "s3",
                                    endpoint.url(),
                                    "bucket",
                                    "us-east-1",
                                    "access",
                                    "secret"));
        }
    }

    private static final class Endpoint implements AutoCloseable {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final HttpServer server;

        Endpoint(boolean conditional) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        try (exchange) {
                            String path = exchange.getRequestURI().getPath();
                            byte[] response;
                            int status;
                            if ("PUT".equals(exchange.getRequestMethod())) {
                                byte[] body = exchange.getRequestBody().readAllBytes();
                                boolean exists =
                                        conditional
                                                && "*"
                                                        .equals(
                                                                exchange.getRequestHeaders()
                                                                        .getFirst("If-None-Match"))
                                                && objects.putIfAbsent(path, body) != null;
                                if (exists) {
                                    status = 412;
                                    response =
                                            ("<Error><Code>PreconditionFailed</Code><Message>exists</Message><Resource>"
                                                            + path
                                                            + "</Resource><RequestId>test</RequestId><HostId>test</HostId></Error>")
                                                    .getBytes(StandardCharsets.UTF_8);
                                    exchange.getResponseHeaders()
                                            .set("Content-Type", "application/xml");
                                } else {
                                    objects.put(path, body);
                                    status = 200;
                                    response = new byte[0];
                                }
                            } else {
                                response = objects.get(path);
                                status = response == null ? 404 : 200;
                                if (response == null) response = new byte[0];
                            }
                            exchange.getResponseHeaders().set("ETag", "\"test\"");
                            exchange.getResponseHeaders().set("x-amz-request-id", "test");
                            exchange.sendResponseHeaders(
                                    status, response.length == 0 ? -1 : response.length);
                            exchange.getResponseBody().write(response);
                        }
                    });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public void close() {
            server.stop(0);
        }
    }
}
