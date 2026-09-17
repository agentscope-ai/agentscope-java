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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            WeixinLoginClient client =
                    new WeixinLoginClient(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            Duration.ofSeconds(3));
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
