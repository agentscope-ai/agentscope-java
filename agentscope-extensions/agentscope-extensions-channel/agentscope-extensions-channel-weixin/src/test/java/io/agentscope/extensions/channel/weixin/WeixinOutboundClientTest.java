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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeixinOutboundClientTest {
    @org.junit.jupiter.api.Test
    void updateResponseIgnoresAdditionalProtocolFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/getupdates",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            ("{\"msgs\":[],\"get_updates_buf\":\"next\","
                                            + "\"longpolling_timeout_ms\":35000,"
                                            + "\"server_extension\":{\"enabled\":true}}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from(
                                    "test",
                                    Map.of(
                                            "baseUrl",
                                            "http://127.0.0.1:" + server.getAddress().getPort())),
                            WeixinCredentialProvider.fixed("test-token"));
            WeixinOutboundClient.JsonNodeResponse response = client.updates("");
            assertEquals(0, response.ret());
            assertEquals("next", response.get_updates_buf());
            assertEquals(35000, response.longpolling_timeout_ms());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ret\":-14}", "{\"ret\":0,\"errcode\":-14}"})
    void businessFailureMustNotBeReportedAsSuccessfulDelivery(String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/sendmessage",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinChannelProperties properties =
                    WeixinChannelProperties.from(
                            "test",
                            Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()));
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            properties, WeixinCredentialProvider.fixed("test-token"));
            WeixinCredentialRejectedException error =
                    assertThrows(
                            WeixinCredentialRejectedException.class,
                            () ->
                                    client.sendWithContext(
                                                    OutboundAddress.direct(
                                                            "test", "test:DIRECT:user-1"),
                                                    List.of(
                                                            Msg.builder()
                                                                    .role(MsgRole.ASSISTANT)
                                                                    .textContent("收到")
                                                                    .build()),
                                                    "ctx-1")
                                            .block());
            assertTrue(error.getMessage().contains("-14"));
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void longPollSettingKeepsASlowGetUpdatesAlive() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/getupdates",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    try {
                        Thread.sleep(900);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] bytes =
                            "{\"msgs\":[],\"sync_buf\":\"s\",\"get_updates_buf\":\"next\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from(
                                    "test",
                                    Map.of(
                                            "baseUrl", urlOf(server),
                                            "requestTimeoutMs", 300,
                                            "longPollTimeoutMs", 5000)),
                            WeixinCredentialProvider.fixed("test-token"));

            assertEquals("next", client.updates("").get_updates_buf());
        } finally {
            server.stop(0);
        }
    }

    /**
     * The live provider answers an idle long poll with its batch fields only — no {@code ret} and no
     * {@code errcode}. Reading that as "no provider outcome" put every channel into a permanent
     * transient-failure backoff, so the poll path has to accept the shape while still rejecting a
     * body that says nothing at all.
     */
    @org.junit.jupiter.api.Test
    void idlePollWithoutAProviderOutcomeIsAccepted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/getupdates",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            ("{\"msgs\":[],\"sync_buf\":\"CAEYw9XN6os0\","
                                 + "\"get_updates_buf\":\"CgkIARjD1c3qizQSOmM3YTdiNTUzNGRmZkBpbS5ib3Q6MDYw\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from("test", Map.of("baseUrl", urlOf(server))),
                            WeixinCredentialProvider.fixed("test-token"));

            WeixinOutboundClient.JsonNodeResponse response = client.updates("");
            assertEquals(0, response.ret());
            assertNull(response.errcode());
            assertTrue(response.msgs().isEmpty());
            assertEquals(
                    "CgkIARjD1c3qizQSOmM3YTdiNTUzNGRmZkBpbS5ib3Q6MDYw", response.get_updates_buf());
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void responsesWithoutAProviderOutcomeAreRejected() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from("test", Map.of("baseUrl", urlOf(server))),
                            WeixinCredentialProvider.fixed("test-token"));

            IllegalStateException poll =
                    assertThrows(IllegalStateException.class, () -> client.updates(""));
            assertTrue(poll.getMessage().contains("no ret/errcode"), poll.getMessage());
            IllegalStateException send =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    client.sendWithContext(
                                                    OutboundAddress.direct(
                                                            "test", "test:DIRECT:user-1"),
                                                    List.of(
                                                            Msg.builder()
                                                                    .role(MsgRole.ASSISTANT)
                                                                    .textContent("hi")
                                                                    .build()),
                                                    "ctx-1")
                                            .block());
            assertTrue(send.getMessage().contains("no ret/errcode"), send.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /** Real server URL so a test never depends on the loopback endpoint allowance. */
    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @org.junit.jupiter.api.Test
    void unparseableProviderBodiesDoNotEchoTheirContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/getupdates",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            "{\"get_updates_buf\":\"cursor\",\"msgs\":[{\"text\":\"secret-payload\""
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from(
                                    "test",
                                    Map.of(
                                            "baseUrl",
                                            "http://127.0.0.1:" + server.getAddress().getPort())),
                            WeixinCredentialProvider.fixed("test-token"));

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> client.updates(""));

            assertTrue(error.getMessage().contains("unparseable"), error.getMessage());
            assertFalse(error.getMessage().contains("secret-payload"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    /**
     * The live provider reports an accepted send by returning the message id alone: there is no
     * {@code ret}/{@code errcode} in a success body. Reading that as "no provider outcome" made
     * every delivered reply look failed, so the message was retried and the user received it again
     * on each attempt.
     */
    @org.junit.jupiter.api.Test
    void sendAcceptsAProviderReceiptWithoutAResultCode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/sendmessage",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            "{\"message_id\":7507333310956748680}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from("test", Map.of("baseUrl", urlOf(server))),
                            WeixinCredentialProvider.fixed("test-token"));

            client.sendWithContext(
                            OutboundAddress.direct("test", "test:DIRECT:user-1"),
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("hi")
                                            .build()),
                            "ctx-1")
                    .block();
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void notificationEndpointsAreCalled() throws Exception {
        List<String> calls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    calls.add(exchange.getRequestURI().getPath());
                    byte[] bytes = "{\"ret\":0}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            WeixinOutboundClient client =
                    new WeixinOutboundClient(
                            WeixinChannelProperties.from(
                                    "test",
                                    Map.of(
                                            "baseUrl",
                                            "http://127.0.0.1:" + server.getAddress().getPort())),
                            WeixinCredentialProvider.fixed("test-token"));

            client.notifyStart();
            client.notifyStop();

            assertEquals(List.of("/ilink/bot/msg/notifystart", "/ilink/bot/msg/notifystop"), calls);
        } finally {
            server.stop(0);
        }
    }
}
