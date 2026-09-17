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
package io.agentscope.extensions.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WeixinLoginClientTest {
    @Test
    void sendsProtocolHeadersAndFollowsQrPollingRedirect() throws Exception {
        AtomicReference<String> postHeaders = new AtomicReference<>();
        AtomicReference<String> getHeaders = new AtomicReference<>();
        AtomicInteger firstPolls = new AtomicInteger();
        HttpServer redirected = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirected.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    getHeaders.set(protocolHeaders(exchange));
                    write(
                            exchange,
                            "{\"status\":\"confirmed\",\"bot_token\":\"secret\","
                                    + "\"ilink_bot_id\":\"account-1\","
                                    + "\"ilink_user_id\":\"user-1\"}");
                });
        redirected.start();

        HttpServer initial = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        initial.createContext(
                "/ilink/bot/get_bot_qrcode",
                exchange -> {
                    postHeaders.set(protocolHeaders(exchange));
                    write(exchange, "{\"qrcode\":\"qr-1\",\"qrcode_img_content\":\"https://qr\"}");
                });
        initial.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    firstPolls.incrementAndGet();
                    write(
                            exchange,
                            "{\"status\":\"scaned_but_redirect\",\"redirect_host\":"
                                    + "\"http://127.0.0.1:"
                                    + redirected.getAddress().getPort()
                                    + "\"}");
                });
        initial.start();

        try {
            WeixinLoginClient client =
                    new WeixinLoginClient(
                            "http://127.0.0.1:" + initial.getAddress().getPort(),
                            Duration.ofSeconds(3));
            WeixinLoginSession session = client.start("3").session();
            assertEquals("qr-1", session.qrcode());
            WeixinLoginStep redirect = client.poll(session);
            assertEquals("scaned_but_redirect", redirect.status());
            WeixinLoginStep confirmed = client.poll(redirect.session());
            assertTrue(confirmed.connected());
            assertEquals("account-1", confirmed.accountId());
            assertEquals(1, firstPolls.get());
            assertRequiredHeaders(postHeaders.get(), true);
            assertRequiredHeaders(getHeaders.get(), false);
        } finally {
            initial.stop(0);
            redirected.stop(0);
        }
    }

    @Test
    void explicitSessionsCanBePolledIndependently() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<String> observedQueries = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_bot_qrcode",
                exchange -> {
                    int number = starts.incrementAndGet();
                    write(
                            exchange,
                            "{\"qrcode\":\"qr-"
                                    + number
                                    + "\",\"qrcode_img_content\":\"https://qr/"
                                    + number
                                    + "\"}");
                });
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    observedQueries.updateAndGet(
                            previous -> previous + "|" + exchange.getRequestURI().getQuery());
                    write(exchange, "{\"status\":\"wait\"}");
                });
        server.start();
        try {
            WeixinLoginClient client = new WeixinLoginClient(urlOf(server), Duration.ofSeconds(3));
            WeixinLoginSession first = client.start("3").session();
            WeixinLoginSession second = client.start("3").session();

            client.poll(second);
            client.poll(first);

            assertTrue(observedQueries.get().contains("qrcode=qr-2"));
            assertTrue(observedQueries.get().contains("qrcode=qr-1"));
            assertEquals(first.pollingBaseUrl(), second.pollingBaseUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBlankVerificationCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            WeixinLoginClient client = new WeixinLoginClient(urlOf(server), Duration.ofSeconds(1));
            WeixinLoginSession session = new WeixinLoginSession("qr-1", urlOf(server));

            assertThrows(IllegalArgumentException.class, () -> client.verify(session, "  "));
            assertThrows(IllegalArgumentException.class, () -> client.verify(session, null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verificationCodeTravelsWithTheStatusQuery() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    query.set(exchange.getRequestURI().getQuery());
                    write(
                            exchange,
                            "{\"status\":\"confirmed\",\"bot_token\":\"secret\","
                                + "\"ilink_bot_id\":\"account-1\",\"ilink_user_id\":\"user-1\"}");
                });
        server.start();
        try {
            WeixinLoginClient client = new WeixinLoginClient(urlOf(server), Duration.ofSeconds(3));
            WeixinLoginSession session = new WeixinLoginSession("qr 1", urlOf(server));

            WeixinLoginStep step = client.verify(session, " 123456 ");

            assertTrue(query.get().contains("qrcode=qr+1"), query.get());
            assertTrue(query.get().contains("verify_code=123456"), query.get());
            assertEquals("confirmed", step.status());
            assertEquals("account-1", step.accountId());
            assertEquals("user-1", step.userId());
            assertTrue(step.connected());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void surfacesTheVerificationChallenge() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> write(exchange, "{\"status\":\"need_verifycode\"}"));
        server.start();
        try {
            WeixinLoginClient client = new WeixinLoginClient(urlOf(server), Duration.ofSeconds(3));
            WeixinLoginStep step = client.poll(new WeixinLoginSession("qr-1", urlOf(server)));

            assertTrue(step.requiresVerification());
            assertFalse(step.connected());
            assertFalse(step.alreadyConnected());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnUntrustedPollingRedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange ->
                        write(
                                exchange,
                                "{\"status\":\"scaned_but_redirect\",\"redirect_host\":"
                                        + "\"https://evil.example.com\"}"));
        server.start();
        try {
            WeixinLoginClient client = new WeixinLoginClient(urlOf(server), Duration.ofSeconds(3));
            WeixinLoginSession session = new WeixinLoginSession("qr-1", urlOf(server));

            assertThrows(IllegalArgumentException.class, () -> client.poll(session));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsProviderHttpFailuresAndMissingFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_bot_qrcode",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    byte[] bytes = "boom".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            String baseUrl = urlOf(server);
            WeixinLoginClient client = new WeixinLoginClient(baseUrl, Duration.ofSeconds(3));

            assertThrows(IllegalStateException.class, () -> client.start("3"));
            assertThrows(
                    IllegalStateException.class,
                    () -> client.poll(new WeixinLoginSession("qr-1", baseUrl)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpFailuresDoNotEchoTheLoginUriOrResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    byte[] bytes =
                            "{\"bot_token\":\"super-secret-token\""
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            String baseUrl = urlOf(server);
            WeixinLoginClient client = new WeixinLoginClient(baseUrl, Duration.ofSeconds(3));

            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    client.verify(
                                            new WeixinLoginSession("qr-secret", baseUrl),
                                            "654321"));

            assertTrue(error.getMessage().contains("500"), error.getMessage());
            assertFalse(error.getMessage().contains("654321"), error.getMessage());
            assertFalse(error.getMessage().contains("qr-secret"), error.getMessage());
            assertFalse(error.getMessage().contains("super-secret-token"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unparseableLoginBodiesDoNotEchoTheirContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> write(exchange, "{\"bot_token\":\"super-secret-token\""));
        server.start();
        try {
            String baseUrl = urlOf(server);
            WeixinLoginClient client = new WeixinLoginClient(baseUrl, Duration.ofSeconds(3));

            IllegalStateException error =
                    assertThrows(
                            IllegalStateException.class,
                            () -> client.poll(new WeixinLoginSession("qr-secret", baseUrl)));

            assertTrue(error.getMessage().contains("unparseable"), error.getMessage());
            assertFalse(error.getMessage().contains("super-secret-token"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailuresDoNotEchoTheLoginUri() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String baseUrl = "http://127.0.0.1:" + closedPort;
        WeixinLoginClient client = new WeixinLoginClient(baseUrl, Duration.ofSeconds(3));

        Exception error =
                assertThrows(
                        Exception.class,
                        () ->
                                client.verify(
                                        new WeixinLoginSession("qr-secret", baseUrl), "654321"));

        assertFalse(
                String.valueOf(error.getMessage()).contains("654321"),
                String.valueOf(error.getMessage()));
        assertFalse(
                String.valueOf(error.getMessage()).contains("qr-secret"),
                String.valueOf(error.getMessage()));
    }

    /** Real server URL: these tests must fail on the behaviour under test, not on endpoint policy. */
    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String protocolHeaders(HttpExchange exchange) {
        return String.join(
                "|",
                exchange.getRequestHeaders().getFirst("iLink-App-Id"),
                exchange.getRequestHeaders().getFirst("iLink-App-ClientVersion"),
                String.valueOf(exchange.getRequestHeaders().getFirst("AuthorizationType")),
                String.valueOf(exchange.getRequestHeaders().getFirst("X-WECHAT-UIN")));
    }

    private static void assertRequiredHeaders(String headers, boolean post) {
        assertNotNull(headers);
        String[] values = headers.split("\\|", -1);
        assertEquals("bot", values[0]);
        assertEquals("132105", values[1]);
        if (post) {
            assertEquals("ilink_bot_token", values[2]);
            assertTrue(!values[3].equals("null") && !values[3].isBlank());
        } else {
            assertEquals("null", values[2]);
            assertEquals("null", values[3]);
        }
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
