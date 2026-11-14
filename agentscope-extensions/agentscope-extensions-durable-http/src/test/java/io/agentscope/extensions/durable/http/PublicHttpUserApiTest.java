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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.SessionBackend;
import io.agentscope.extensions.durable.jdbc.DurableUserApiTest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;

class PublicHttpUserApiTest extends DurableUserApiTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<DurableRuntime> hosts = new ArrayList<>();
    private final ExecutorService requests = Executors.newCachedThreadPool();

    @Override
    protected DurableRuntime connect(SessionBackend backend) {
        try {
            var host = super.connect(backend);
            hosts.add(host);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api", new DurableRuntimeHandler(host, (e, o, n, s) -> {}));
            server.setExecutor(requests);
            server.start();
            servers.add(server);
            return DurableRuntime.builder()
                    .transport(
                            new DurableHttp(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/api")))
                    .pollInterval(Duration.ofMillis(10))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stopServers() throws InterruptedException {
        if (api != null) api.close();
        hosts.forEach(DurableRuntime::close);
        servers.forEach(s -> s.stop(0));
        requests.shutdownNow();
        assertTrue(
                requests.awaitTermination(5, TimeUnit.SECONDS),
                "HTTP test executor did not terminate");
    }
}
