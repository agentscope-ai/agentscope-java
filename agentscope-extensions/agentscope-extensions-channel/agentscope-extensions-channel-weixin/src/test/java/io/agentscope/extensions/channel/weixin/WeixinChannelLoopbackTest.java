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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

/** End-to-end channel test with a local fake iLink HTTP server. */
class WeixinChannelLoopbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private WeixinChannel channel;
    private String baseUrl;
    private final AtomicReference<String> agentInput = new AtomicReference<>();
    private final AtomicReference<JsonNode> sendRequest = new AtomicReference<>();
    private final AtomicReference<String> secondCursor = new AtomicReference<>();
    private final AtomicInteger sendCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/getupdates", this::getUpdates);
        server.createContext("/ilink/bot/sendmessage", this::sendMessage);
        server.createContext("/ilink/bot/msg/notifystart", WeixinChannelLoopbackTest::acknowledge);
        server.createContext("/ilink/bot/msg/notifystop", WeixinChannelLoopbackTest::acknowledge);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (channel != null) channel.stop();
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(30)
    void receivesMessageCallsAgentRepliesWithContextAndPersistsCursor() throws Exception {
        AtomicInteger agentCalls = new AtomicInteger();
        CountDownLatch handled = new CountDownLatch(1);
        Gateway gateway =
                new Gateway() {
                    @Override
                    public void bindMainAgent(HarnessAgent agent) {}

                    @Override
                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        agentInput.set(messages.get(0).getTextContent());
                        agentCalls.incrementAndGet();
                        handled.countDown();
                        return Mono.just(
                                Msg.builder().role(MsgRole.ASSISTANT).textContent("收到").build());
                    }
                };

        channel =
                WeixinChannel.fromProperties(
                        "wx-test",
                        ChannelConfig.of("wx-test", "main"),
                        Map.of(
                                "accountId",
                                "test-account-" + UUID.randomUUID(),
                                "botToken",
                                "test-token",
                                "baseUrl",
                                baseUrl,
                                "ilinkUserId",
                                "user-1"));
        channel.init(gateway);
        channel.start();

        assertTrue(
                handled.await(10, TimeUnit.SECONDS),
                "No message reached Gateway; check the getupdates stub and weixin-poll scenario");
        assertEquals("你好", agentInput.get(), "Text delivered to Gateway");
        assertTrue(
                waitFor(() -> sendRequest.get() != null && secondCursor.get() != null),
                () ->
                        "Timed out waiting for sendmessage and the next cursor: replies="
                                + sendCalls.get()
                                + ", nextCursor="
                                + secondCursor.get());
        assertEquals(1, agentCalls.get(), "Gateway calls");
        assertEquals(1, sendCalls.get(), "sendmessage requests from this run");
        assertEquals("ctx-1", sendRequest.get().path("msg").path("context_token").asText());
        assertEquals("user-1", sendRequest.get().path("msg").path("to_user_id").asText());
        assertEquals(
                "收到",
                sendRequest
                        .get()
                        .path("msg")
                        .path("item_list")
                        .path(0)
                        .path("text_item")
                        .path("text")
                        .asText());
        assertEquals("cursor-2", secondCursor.get());
    }

    @Test
    @Timeout(10)
    void doesNotLoseMessageWhenCursorPersistenceInterruptsThePoll() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        CountDownLatch handled = new CountDownLatch(1);
        WeixinStateStore interruptedStore = org.mockito.Mockito.spy(WeixinStateStore.inMemory());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (saves.incrementAndGet() == 1)
                                throw new IllegalStateException(
                                        "simulated interruption after commit");
                            return result;
                        })
                .when(interruptedStore)
                .acceptBatch(
                        org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        Gateway gateway =
                new Gateway() {
                    @Override
                    public void bindMainAgent(HarnessAgent agent) {}

                    @Override
                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        handled.countDown();
                        return Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("received")
                                        .build());
                    }
                };

        channel =
                WeixinChannel.create(
                        "wx-cursor-crash",
                        ChannelConfig.of("wx-cursor-crash", "main"),
                        WeixinChannelProperties.from(
                                "wx-cursor-crash",
                                Map.of(
                                        "accountId",
                                        "cursor-crash-account-" + UUID.randomUUID(),
                                        "baseUrl",
                                        baseUrl,
                                        "ilinkUserId",
                                        "user-1")),
                        WeixinCredentialProvider.fixed("test-token"),
                        interruptedStore,
                        WeixinRuntimeListener.noOp());
        channel.init(gateway);
        channel.start();

        assertTrue(
                handled.await(4, TimeUnit.SECONDS),
                "The message must remain recoverable when cursor persistence interrupts the poll");
    }

    private void getUpdates(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        String cursor = request.path("get_updates_buf").asText();
        if (cursor.isEmpty()) {
            write(
                    exchange,
                    "{\"ret\":0,\"msgs\":[{\"message_type\":1,\"message_id\":\"msg-1\","
                            + "\"from_user_id\":\"user-1\",\"context_token\":\"ctx-1\","
                            + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"你好\"}}]}],"
                            + "\"get_updates_buf\":\"cursor-2\"}");
        } else {
            secondCursor.set(cursor);
            write(exchange, "{\"ret\":0,\"msgs\":[],\"get_updates_buf\":\"cursor-2\"}");
        }
    }

    private void sendMessage(HttpExchange exchange) throws IOException {
        sendCalls.incrementAndGet();
        sendRequest.set(JSON.readTree(exchange.getRequestBody()));
        write(exchange, "{\"message_id\":\"receipt-1\"}");
    }

    /** The consumer refuses to poll until the provider session starts, so this must answer. */
    private static void acknowledge(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        write(exchange, "{\"ret\":0}");
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean waitFor(TrafficCheck condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.complete()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @FunctionalInterface
    private interface TrafficCheck {
        boolean complete() throws Exception;
    }
}
