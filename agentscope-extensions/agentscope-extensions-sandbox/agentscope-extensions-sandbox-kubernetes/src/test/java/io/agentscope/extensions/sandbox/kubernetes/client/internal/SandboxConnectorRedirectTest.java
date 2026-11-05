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
package io.agentscope.extensions.sandbox.kubernetes.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.extensions.sandbox.kubernetes.client.config.DirectConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy.ConnectionStrategy;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the connector follows redirects on file API downloads. Gateways in front of the
 * sandbox runtime may normalize encoded multi-segment paths ({@code %2F} -> {@code /}) with a
 * 307; without redirect following, workspace persistence fails silently.
 */
class SandboxConnectorRedirectTest {

    private static final String ENCODED_PATH = "/download/.agentscope-tmp%2Fws-persist.tar";
    private static final String DECODED_PATH = "/download/.agentscope-tmp/ws-persist.tar";
    private static final String TAR_BYTES = "tar-bytes";
    private static final String STALL_PATH = "/stall";
    private static final int STALL_HOLD_SECONDS = 30;

    private HttpServer server;
    private SandboxConnector connector;
    private volatile CountDownLatch stallLatch;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        // First hit on the encoded path answers 307 (gateway normalization); the decoded
        // target serves the payload, proving the client followed the redirect.
        server.createContext(
                "/",
                exchange -> {
                    byte[] body;
                    if (ENCODED_PATH.equals(exchange.getRequestURI().getRawPath())) {
                        exchange.getResponseHeaders().add("Location", DECODED_PATH);
                        exchange.sendResponseHeaders(307, -1);
                        return;
                    }
                    if (STALL_PATH.equals(exchange.getRequestURI().getRawPath())) {
                        // Headers go out immediately (so the per-attempt
                        // header timeout never fires), then the body never arrives.
                        exchange.sendResponseHeaders(200, 0);
                        try {
                            if (stallLatch != null) {
                                stallLatch.await(STALL_HOLD_SECONDS, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        try (OutputStream out = exchange.getResponseBody()) {}
                        return;
                    }
                    body = TAR_BYTES.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (connector != null) {
            connector.close();
        }
        server.stop(0);
    }

    @Test
    void downloadFollowsGatewayRedirect() throws Exception {
        ConnectionStrategy strategy = mock(ConnectionStrategy.class);
        when(strategy.connect()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(strategy.shouldInjectRouterHeaders()).thenReturn(false);
        connector = new SandboxConnector(strategy, "sbx-test", "default", 8888);
        connector.connect();

        HttpResponse<byte[]> resp = download();
        assertEquals(200, resp.statusCode());
        assertEquals(TAR_BYTES, new String(resp.body(), StandardCharsets.UTF_8));
    }

    @Test
    void downloadFollowsGatewayRedirect_directConfig() throws Exception {
        connector =
                new SandboxConnector(
                        "sbx-test",
                        "default",
                        new DirectConnectionConfig(
                                "http://127.0.0.1:" + server.getAddress().getPort()),
                        null,
                        null,
                        null,
                        null);
        connector.connect();

        HttpResponse<byte[]> resp = download();
        assertEquals(200, resp.statusCode());
        assertEquals(TAR_BYTES, new String(resp.body(), StandardCharsets.UTF_8));
    }

    /**
     * Regression for the unbounded blocking send: when the server sends response headers and
     * then stalls the body, the exchange deadline must fire even though the per-attempt
     * header timeout never does.
     */
    @Test
    void exchangeTimesOutWhenBodyStallsAfterHeaders() throws Exception {
        stallLatch = new CountDownLatch(1);
        ConnectionStrategy strategy = mock(ConnectionStrategy.class);
        when(strategy.connect()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(strategy.shouldInjectRouterHeaders()).thenReturn(false);
        connector = new SandboxConnector(strategy, "sbx-test", "default", 8888);
        connector.connect();

        try {
            HttpTimeoutException timeout =
                    assertThrows(
                            HttpTimeoutException.class,
                            () ->
                                    connector.sendRequest(
                                            "GET", "stall", null, null, 1, Duration.ofSeconds(2)));
            assertTrue(timeout.getMessage().contains("Sandbox exchange timed out"));
        } finally {
            stallLatch.countDown();
        }
    }

    private HttpResponse<byte[]> download() throws Exception {
        return connector.sendRequestForBytes(
                "GET", "download/.agentscope-tmp%2Fws-persist.tar", null, null);
    }
}
