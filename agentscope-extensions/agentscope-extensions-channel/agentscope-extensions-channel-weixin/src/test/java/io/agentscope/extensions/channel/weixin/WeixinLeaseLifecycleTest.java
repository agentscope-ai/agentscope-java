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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Timeout(20)
class WeixinLeaseLifecycleTest {
    private HttpServer server;
    private WeixinChannel channel;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch sent = new CountDownLatch(1);
    private final AtomicInteger sends = new AtomicInteger();
    private final WeixinStateStore store = spy(WeixinStateStore.inMemory());

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    var request = new ObjectMapper().readTree(exchange.getRequestBody());
                    String body = "{\"ret\":0}";
                    if (exchange.getRequestURI().getPath().endsWith("getupdates")) {
                        body =
                                request.path("get_updates_buf").asText().isEmpty()
                                        ? "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[{\"message_type\":1,"
                                              + "\"message_id\":\"m1\",\"from_user_id\":\"peer\",\"context_token\":\"ctx\","
                                              + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"hello\"}}]}]}"
                                        : "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[]}";
                    } else if (exchange.getRequestURI().getPath().endsWith("sendmessage")) {
                        sends.incrementAndGet();
                        sent.countDown();
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        if (channel != null) channel.stop();
        server.stop(0);
    }

    private void start(Mono<Msg> reply) {
        channel =
                WeixinChannel.create(
                        "channel",
                        ChannelConfig.of("channel", "main"),
                        WeixinChannelProperties.from(
                                "channel",
                                Map.of(
                                        "accountId",
                                        "account",
                                        "baseUrl",
                                        "http://127.0.0.1:" + server.getAddress().getPort(),
                                        "leaseMs",
                                        1500,
                                        "dispatchTimeoutMs",
                                        5000)),
                        WeixinCredentialProvider.fixed("test-token"),
                        store,
                        WeixinRuntimeListener.noOp());
        channel.init(
                new Gateway() {
                    @Override
                    public void bindMainAgent(HarnessAgent agent) {}

                    @Override
                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        entered.countDown();
                        return reply;
                    }
                });
        channel.start();
    }

    private Msg reply() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("reply").build();
    }

    @Test
    void standbyKeepsTryingAndTakesOverWithoutConfigurationChange() throws Exception {
        var owner = store.acquireLease("account", "first", 60_000).orElseThrow();
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            var result = invocation.callRealMethod();
                            attempted.countDown();
                            return result;
                        })
                .when(store)
                .acquireLease(anyString(), anyString(), anyLong());
        start(Mono.just(reply()));
        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        assertEquals(1, entered.getCount());
        store.releaseLease("account", owner);
        assertTrue(sent.await(3, TimeUnit.SECONDS));
        assertEquals(1, sends.get());
    }

    @Test
    void renewsIndependentlyWhileAgentIsStillRunning() throws Exception {
        CountDownLatch renewed = new CountDownLatch(4);
        doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (entered.getCount() == 0 && Boolean.TRUE.equals(result))
                                renewed.countDown();
                            return result;
                        })
                .when(store)
                .renewLease(anyString(), any(WeixinLease.class), anyLong());
        Sinks.One<Msg> reply = Sinks.one();
        start(reply.asMono());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(renewed.await(4, TimeUnit.SECONDS), "Lease must renew during slow dispatch");
        assertTrue(store.acquireLease("account", "competitor", 60_000).isEmpty());
        reply.tryEmitValue(reply());
        assertTrue(sent.await(2, TimeUnit.SECONDS));
    }

    @Test
    void leaseLossCancelsDispatchAndSuppressesLateReply() throws Exception {
        AtomicBoolean replaced = new AtomicBoolean();
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            if (entered.getCount() == 0 && replaced.compareAndSet(false, true)) {
                                WeixinLease old = invocation.getArgument(1);
                                store.releaseLease("account", old);
                                store.acquireLease("account", "successor", 60_000).orElseThrow();
                                return false;
                            }
                            return invocation.callRealMethod();
                        })
                .when(store)
                .renewLease(anyString(), any(WeixinLease.class), anyLong());
        Sinks.One<Msg> reply = Sinks.one();
        start(reply.asMono().doOnCancel(cancelled::countDown));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        reply.tryEmitValue(reply());
        assertFalse(sent.await(300, TimeUnit.MILLISECONDS));
        assertEquals(0, sends.get());
    }
}
