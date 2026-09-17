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
                            ("{\"ret\":0,\"msgs\":[],\"get_updates_buf\":\"next\","
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
