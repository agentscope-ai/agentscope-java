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

/* Copyright 2024-2026 the original author or authors. Licensed under Apache-2.0. */
package io.agentscope.builder.web.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.feishu.FeishuAccessTokenProvider;
import io.agentscope.extensions.channel.feishu.FeishuInboundMapper;
import io.agentscope.extensions.channel.feishu.FeishuOutboundClient;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ChannelWorkBridgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT =
            """
            {"header":{"tenant_key":"org","token":"verify"},"event":{
            "sender":{"sender_id":{"open_id":"human"},"sender_type":"user"},
            "message":{"message_id":"om-inbound","parent_id":"om-reply","root_id":"om-root",
            "chat_id":"room","chat_type":"group","message_type":"text","content":"{\\"text\\":\\"hello\\"}"}}}
            """;

    @Test
    void durableIntakeKeepsProviderIdentityAndDoesNotPollSession() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> received = new AtomicReference<>();
        server.createContext(
                "/",
                exchange -> {
                    received.set(JSON.readTree(exchange.getRequestBody()));
                    byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var chat = mock(ManagedSessionChannelBridge.class);
            var bridge =
                    new ChannelWorkBridge(
                            WebClient.create(url(server)), chat, new ChannelManager());
            var in = new FeishuInboundMapper("channel").map(JSON.readTree(EVENT)).orElseThrow();
            assertNull(bridge.receive(in).block(Duration.ofSeconds(5)));
            assertEquals("human", received.get().path("senderId").asText());
            assertEquals("org", received.get().path("accountId").asText());
            assertEquals("om-inbound", received.get().path("messageId").asText());
            assertEquals("om-reply", received.get().path("replyToId").asText());
            assertEquals("om-root", received.get().path("threadId").asText());
            assertFalse(received.get().has("ownerId"));
            verifyNoInteractions(chat);
        } finally {
            server.stop(0);
        }
    }

    /**
     * The Agent's reply is persisted for the host's delivery queue instead of being returned to the
     * channel: sending inline would make a provider outage fail the inbound message and replay the
     * Agent that produced the reply.
     */
    @Test
    void agentReplyIsPersistedForDeliveryInsteadOfReturnedInline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> replies = Collections.synchronizedList(new ArrayList<>());
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    JsonNode body = JSON.readTree(exchange.getRequestBody());
                    if (path.endsWith("/api/internal/channels/replies")) {
                        replies.add(body);
                        exchange.sendResponseHeaders(204, -1);
                    } else {
                        byte[] payload =
                                ("{\"chat\":true,\"ownerId\":\"owner-1\",\"agentId\":\"agent-1\","
                                                + "\"externalKey\":\"key-1\"}")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, payload.length);
                        exchange.getResponseBody().write(payload);
                    }
                    exchange.close();
                });
        server.start();
        try {
            var chat = mock(ManagedSessionChannelBridge.class);
            when(chat.dispatchAndAwaitReply(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.just("结果"));
            var bridge =
                    new ChannelWorkBridge(
                            WebClient.create(url(server)), chat, new ChannelManager());
            var in = new FeishuInboundMapper("channel").map(JSON.readTree(EVENT)).orElseThrow();

            assertNull(
                    bridge.receive(in).block(Duration.ofSeconds(5)),
                    "the reply must not travel back to the channel for an inline send");
            assertEquals(1, replies.size());
            JsonNode queued = replies.get(0);
            assertEquals("结果", queued.path("text").asText());
            assertEquals("channel", queued.path("channelId").asText());
            assertEquals("om-inbound", queued.path("messageId").asText());
            assertEquals("human", queued.path("senderId").asText());
            String key = queued.path("eventKey").asText();

            // A replayed inbound message queues the same reply under the same key.
            assertNull(bridge.receive(in).block(Duration.ofSeconds(5)));
            assertEquals(2, replies.size());
            assertEquals(key, replies.get(1).path("eventKey").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void feishuReceiptsRejectProviderErrorsAndPreserveRetryKeyAndThread() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> providerResponse =
                new AtomicReference<>("{\"code\":0,\"data\":{\"message_id\":\"om-out\"}}");
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext(
                "/",
                exchange -> {
                    String response;
                    if (exchange.getRequestURI().getPath().contains("tenant_access_token")) {
                        response = "{\"code\":0,\"tenant_access_token\":\"test\",\"expire\":7200}";
                    } else {
                        sent.set(JSON.readTree(exchange.getRequestBody()));
                        path.set(exchange.getRequestURI().getPath());
                        response = providerResponse.get();
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    new FeishuOutboundClient(
                            url(server),
                            new FeishuAccessTokenProvider(url(server), "app", "secret"));
            var address = new OutboundAddress("channel", "org", "channel:GROUP:room", "om-root");
            var msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("result").build();
            assertEquals(
                    "om-out",
                    client.sendWithReceipt(address, msg, "stable-id").block(Duration.ofSeconds(5)));
            assertEquals("stable-id", sent.get().path("uuid").asText());
            assertTrue(sent.get().path("reply_in_thread").asBoolean());
            assertEquals("/open-apis/im/v1/messages/om-root/reply", path.get());
            providerResponse.set("{\"code\":230001,\"msg\":\"permission denied\"}");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            client.sendWithReceipt(address, msg, "stable-id")
                                    .block(Duration.ofSeconds(5)));
            assertEquals("stable-id", sent.get().path("uuid").asText());
            providerResponse.set("{\"code\":0}");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            client.sendWithReceipt(address, msg, "stable-id")
                                    .block(Duration.ofSeconds(5)));
            providerResponse.set("{}");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            client.sendWithReceipt(address, msg, "stable-id")
                                    .block(Duration.ofSeconds(5)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rawOutboundCannotBypassWorkPublicationAndDoesNotClaimDelivery() {
        var service = mock(io.agentscope.builder.runtime.outbound.OutboundService.class);
        var guard = mock(io.agentscope.builder.web.share.AgentAccessGuard.class);
        var controller =
                new io.agentscope.builder.runtime.outbound.OutboundController(service, guard);
        var request =
                new io.agentscope.builder.runtime.outbound.OutboundRequest(
                        "channel", "GROUP", "room", "org", null, "text", null);
        var console =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "user",
                        null,
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_ADMIN")));
        assertEquals(403, controller.send(request, console).block().getStatusCode().value());
        verifyNoInteractions(service);
        var internal =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "service",
                        null,
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_INTERNAL")));
        var result = controller.send(request, internal).block();
        assertEquals(202, result.getStatusCode().value());
        assertEquals("submitted", result.getBody().get("status"));
    }

    /** A courtesy reply answers an unbound peer, which the delivery queue refuses to claim. */
    @Test
    void courtesyReplyStaysInlineBecauseTheQueueWouldRejectIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = Collections.synchronizedList(new ArrayList<>());
        server.createContext(
                "/",
                exchange -> {
                    paths.add(exchange.getRequestURI().getPath());
                    JSON.readTree(exchange.getRequestBody());
                    byte[] payload =
                            ("{\"chat\":false,\"reply\":\"请先在控制台 Channel 页面生成绑定码。\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                });
        server.start();
        try {
            var chat = mock(ManagedSessionChannelBridge.class);
            var bridge =
                    new ChannelWorkBridge(
                            WebClient.create(url(server)), chat, new ChannelManager());
            var in = new FeishuInboundMapper("channel").map(JSON.readTree(EVENT)).orElseThrow();

            Msg reply = bridge.receive(in).block(Duration.ofSeconds(5));
            assertEquals("请先在控制台 Channel 页面生成绑定码。", reply.getTextContent());
            assertEquals(List.of("/api/internal/channels/inbound"), paths);
            verifyNoInteractions(chat);
        } finally {
            server.stop(0);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
