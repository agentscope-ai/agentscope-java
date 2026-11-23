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

import com.sun.net.httpserver.HttpServer;
import io.agentscope.durable.SessionBackend;
import io.agentscope.extensions.durable.jdbc.DurableBackendTest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;

/** Every storage recovery test also traverses the real versioned HTTP protocol. */
class HttpBackendContractTest extends DurableBackendTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private final ExecutorService requests = Executors.newCachedThreadPool();

    @Override
    protected SessionBackend wrap(SessionBackend backend) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/backend", new SessionBackendHandler(backend, (e, o, s) -> {}));
            server.setExecutor(requests);
            server.start();
            servers.add(server);
            return new HttpSessionBackend(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/backend"));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        requests.shutdownNow();
    }
}
