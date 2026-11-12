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
package io.agentscope.extensions.durable.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicHttpBoundaryTest {
    @TempDir Path directory;

    @Test
    void authorizesNamespaceListingsAndBothForkScopesBeforeAccess() throws Exception {
        try (var backend = LocalBackend.open(directory);
                var host =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .build()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var threads = Executors.newCachedThreadPool();
            List<String> checked = Collections.synchronizedList(new ArrayList<>());
            server.createContext(
                    "/api",
                    new DurableRuntimeHandler(
                            host,
                            (e, op, namespace, key) -> {
                                checked.add(
                                        op
                                                + ":"
                                                + namespace
                                                + ":"
                                                + (key == null ? "" : key.sessionId()));
                                if (namespace != null && !namespace.equals("allowed"))
                                    throw new SecurityException();
                                if ("forkTarget".equals(op)) throw new SecurityException();
                            }));
            server.setExecutor(threads);
            server.start();
            try (var access =
                    DurableRuntime.builder()
                            .transport(
                                    new DurableHttp(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/api")))
                            .build()) {
                var session = access.createSession(new SessionKey("allowed", "source"), "agent");
                assertEquals(1, access.listSessions("allowed", null, 10).items().size());
                assertThrows(DurableException.class, () -> access.listSessions("denied", null, 10));
                assertThrows(
                        DurableException.class,
                        () ->
                                session.mainThread()
                                        .fork(
                                                1,
                                                new ThreadKey(
                                                        new SessionKey("allowed", "target"),
                                                        "main"),
                                                "fork"));
                assertTrue(checked.contains("listSessions:denied:"));
                assertTrue(checked.contains("fork:allowed:source"));
                assertTrue(checked.contains("forkTarget:allowed:target"));
                assertEquals(
                        DurableException.Code.NOT_FOUND,
                        assertThrows(
                                        DurableException.class,
                                        () -> backend.session(new SessionKey("allowed", "target")))
                                .code());
            } finally {
                server.stop(0);
                threads.shutdownNow();
            }
        }
    }

    @Test
    void lostSubmitReplyCanBeRetriedWithoutDuplicateAdmissionOrHistory() throws Exception {
        try (var backend = LocalBackend.open(directory);
                var host =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .build()) {
            var key = new SessionKey("tenant", "lost-reply");
            host.createSession(key, "agent");
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var threads = Executors.newCachedThreadPool();
            var drop = new AtomicBoolean(true);
            server.createContext(
                    "/api",
                    exchange -> {
                        try (exchange) {
                            var request =
                                    DurableJson.convert(
                                            DurableHttp.request(
                                                    exchange, DurableProtocol.Endpoint.USER),
                                            DurableProtocol.Request.class);
                            var result = host.invoke(request);
                            if ("submit".equals(request.operation()) && drop.get()) return;
                            DurableHttp.result(exchange, result);
                        }
                    });
            server.setExecutor(threads);
            server.start();
            try (var access =
                    DurableRuntime.builder()
                            .transport(
                                    new DurableHttp(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/api")))
                            .build()) {
                var session = access.getSession(key);
                assertEquals(
                        DurableException.Code.UNAVAILABLE,
                        assertThrows(
                                        DurableException.class,
                                        () -> session.submit("request", "hello"))
                                .code());
                String original = backend.admission(key, "request").orElseThrow().turnId();
                drop.set(false);
                assertEquals(original, session.submit("request", "hello").turnId());
                assertEquals(1, session.listTurns(null, 10).items().size());
                assertEquals(1, session.history(null, 10).items().size());
            } finally {
                server.stop(0);
                threads.shutdownNow();
            }
        }
    }
}
