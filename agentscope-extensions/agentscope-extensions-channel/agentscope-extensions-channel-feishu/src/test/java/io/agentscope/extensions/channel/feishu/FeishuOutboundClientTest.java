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
package io.agentscope.extensions.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link FeishuOutboundClient} against a loopback Feishu API. */
class FeishuOutboundClientTest {

    private static final String TOKEN_RESPONSE =
            "{\"code\":0,\"tenant_access_token\":\"token-1\",\"expire\":7200}";

    private final AtomicReference<String> sendResponse = new AtomicReference<>("");
    private final List<URI> sendRequests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private FeishuOutboundClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.startsWith("/open-apis/im/v1/messages")) {
                        sendRequests.add(exchange.getRequestURI());
                    }
                    String body =
                            path.contains("tenant_access_token")
                                    ? TOKEN_RESPONSE
                                    : sendResponse.get();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client =
                new FeishuOutboundClient(
                        base,
                        new FeishuAccessTokenProvider(base, "app-id", "app-secret"),
                        Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendErrorsOnEmptyResponseBody() {
        sendResponse.set("");
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectErrorSatisfies(
                        e -> {
                            assertTrue(e instanceof IllegalStateException, e.toString());
                            assertTrue(e.getMessage().contains("code missing"), e.toString());
                        })
                .verify();
    }

    @Test
    void sendRoutesEachMessageToTheAddressedThread() {
        sendResponse.set("{\"code\":0,\"data\":{\"message_id\":\"om-1\"}}");
        OutboundAddress thread =
                new OutboundAddress("feishu", null, "feishu:GROUP:chat-1", "om-root");
        client.send(thread, List.of(text("one"), text("two"))).block(Duration.ofSeconds(10));

        URI reply = URI.create("/open-apis/im/v1/messages/om-root/reply");
        assertEquals(List.of(reply, reply), sendRequests);
    }

    @Test
    void sendWithoutAThreadUsesChatAddressing() {
        sendResponse.set("{\"code\":0,\"data\":{\"message_id\":\"om-1\"}}");
        client.send(direct("chat-1"), List.of(text("hello"))).block(Duration.ofSeconds(10));
        assertEquals(
                List.of(URI.create("/open-apis/im/v1/messages?receive_id_type=chat_id")),
                sendRequests);
    }

    @Test
    void sendWithReceiptSucceedsWhenFeishuAccepts() {
        sendResponse.set("{\"code\":0,\"data\":{\"message_id\":\"om-1\"}}");
        assertEquals(
                "om-1",
                client.sendWithReceipt(direct("user-1"), text("hello"), "delivery-1")
                        .block(Duration.ofSeconds(5)));
    }

    private static Msg text(String content) {
        return Msg.builder().role(MsgRole.USER).textContent(content).build();
    }

    private static OutboundAddress direct(String userId) {
        return OutboundAddress.direct("feishu", "feishu:DIRECT:" + userId);
    }
}
