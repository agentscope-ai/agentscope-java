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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WeixinChannelTest {
    @Test
    void propertiesContainOnlyProviderConfiguration() {
        WeixinChannelProperties p =
                WeixinChannelProperties.from("binding-1", Map.of("botToken", "ignored-here"));
        assertEquals("binding-1", p.accountId());
        assertEquals(90000, p.leaseMs());
    }

    @Test
    void standaloneFactoryRejectsMissingCredential() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WeixinChannel.fromProperties(
                                "binding-1", ChannelConfig.of("binding-1", "main"), Map.of()));
    }

    @Test
    void sensitiveValuesAreRedactedFromDiagnosticStrings() {
        WeixinCredentials credentials = new WeixinCredentials("secret-token");
        WeixinLoginSession session = new WeixinLoginSession("secret-qr", "https://weixin.qq.com");
        WeixinLoginChallenge challenge = new WeixinLoginChallenge(session, "secret-image");

        assertFalse(credentials.toString().contains("secret-token"));
        assertFalse(session.toString().contains("secret-qr"));
        assertFalse(challenge.toString().contains("secret-image"));
    }

    @Test
    void mapperAcceptsDirectTextAndPreservesContext() throws Exception {
        var mapper = new WeixinInboundMapper("wx", "account", "self");
        var json =
                new ObjectMapper()
                        .readTree(
                                "{\"message_type\":1,\"message_id\":\"m1\",\"from_user_id\":\"u1\",\"context_token\":\"ctx\",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\""
                                    + " hi \"}}]}");
        var in = mapper.map(json).orElseThrow();
        assertEquals("hi", in.messages().get(0).getTextContent());
        assertEquals("ctx", in.messages().get(0).getMetadata().get("weixinContextToken"));
    }

    @Test
    void notifyStartCredentialRejectionIsReportedAsNeutralRuntimeEvent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/msg/notifystart",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = "{\"ret\":-14}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>();
        WeixinChannel channel =
                WeixinChannel.create(
                        "wx",
                        ChannelConfig.of("wx", "main"),
                        WeixinChannelProperties.from(
                                "wx",
                                Map.of(
                                        "baseUrl",
                                        "http://127.0.0.1:" + server.getAddress().getPort())),
                        WeixinCredentialProvider.fixed("token"),
                        WeixinStateStore.inMemory(),
                        new WeixinRuntimeListener() {
                            @Override
                            public void onCredentialRejected(String accountId, String value) {
                                reason.set(value);
                                rejected.countDown();
                            }
                        });
        try {
            channel.start();
            assertTrue(rejected.await(3, TimeUnit.SECONDS));
            assertTrue(reason.get().contains("-14"));
        } finally {
            channel.stop();
            server.stop(0);
        }
    }
}
