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
package io.agentscope.builder.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.weixin.WeixinStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class SchedulerWeixinRuntimeTest {
    @Test
    @Timeout(45)
    void standbyDoesNotReportInactiveAndNewOwnerReportsItsFence() throws Exception {
        ObjectMapper json = new ObjectMapper();
        WeixinStateStore store = WeixinStateStore.inMemory();
        var owner = store.acquireLease("account", "other-replica", 60_000).orElseThrow();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String config =
                json.writeValueAsString(
                        Map.of(
                                "wx",
                                Map.of(
                                        "type",
                                        "weixin",
                                        "defaultAgentId",
                                        "main",
                                        "properties",
                                        Map.of(
                                                "accountId",
                                                "account",
                                                "credentialRevision",
                                                1,
                                                "baseUrl",
                                                baseUrl,
                                                "leaseMs",
                                                3000))));
        AtomicReference<JsonNode> initialReport = new AtomicReference<>();
        AtomicReference<JsonNode> activeReport = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String body;
                    if (path.equals("/api/internal/channels/config")) {
                        body = config;
                    } else if (path.endsWith("/credential")) {
                        exchange.getRequestBody().readAllBytes();
                        body = "{\"botToken\":\"test-token\",\"revision\":1}";
                    } else if (path.equals("/api/internal/channels/runtime")) {
                        JsonNode report = json.readTree(exchange.getRequestBody());
                        initialReport.compareAndSet(null, report);
                        for (JsonNode item : report.path("channels")) {
                            if (item.path("started").asBoolean()) {
                                activeReport.set(item);
                                reported.countDown();
                            }
                        }
                        body = "{}";
                    } else {
                        exchange.getRequestBody().readAllBytes();
                        body = "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[]}";
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        Gateway gateway =
                new Gateway() {
                    @Override
                    public void bindMainAgent(HarnessAgent agent) {}

                    @Override
                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        return Mono.empty();
                    }
                };
        var runtime =
                new SchedulerChannelRuntime(
                        new ChannelManager(),
                        gateway,
                        WebClient.create(baseUrl),
                        json,
                        new ChannelRuntimeCatalog(),
                        store,
                        1,
                        1,
                        0);
        try {
            runtime.start();
            assertEquals(0, initialReport.get().path("channels").size());
            store.releaseLease("account", owner);
            assertTrue(reported.await(5, TimeUnit.SECONDS));
            assertEquals("account", activeReport.get().path("accountId").asText());
            assertEquals(1, activeReport.get().path("credentialRevision").asLong());
            assertTrue(activeReport.get().path("leaseGeneration").asLong() > owner.generation());
            assertTrue(activeReport.get().path("sequence").asLong() > 0);
        } finally {
            runtime.stop();
            server.stop(0);
        }
    }
}
