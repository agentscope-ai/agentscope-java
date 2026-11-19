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
package io.agentscope.extensions.channel.dingtalk;

import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.AES_KEY;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.SECRET;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.encrypt;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.sign;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.timestamp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/** Tests for {@link DingTalkCallbackController} against real channels in http mode. */
class DingTalkCallbackControllerTest {

    private DingTalkChannelRegistry registry;
    private DingTalkCallbackController controller;
    private DingTalkChannel channel;
    private DingTalkChannel encryptedChannel;
    private Gateway gateway;
    private Gateway encryptedGateway;

    @BeforeEach
    void setUp() {
        registry = DingTalkChannelRegistry.instance();
        gateway = mock(Gateway.class);
        when(gateway.run(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        channel =
                DingTalkChannel.fromProperties(
                        "dt-test",
                        ChannelConfig.of("dt-test", "main"),
                        Map.of(
                                "appKey", "app-key",
                                "appSecret", SECRET,
                                "robotCode", "robot",
                                "mode", "http"));
        channel.init(gateway);
        channel.start();
        encryptedGateway = mock(Gateway.class);
        when(encryptedGateway.run(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        encryptedChannel =
                DingTalkChannel.fromProperties(
                        "dt-enc",
                        ChannelConfig.of("dt-enc", "main"),
                        Map.of(
                                "appKey", "app-key",
                                "appSecret", SECRET,
                                "robotCode", "robot",
                                "mode", "http",
                                "aesKey", AES_KEY));
        encryptedChannel.init(encryptedGateway);
        encryptedChannel.start();
        controller = new DingTalkCallbackController(registry);
    }

    @AfterEach
    void tearDown() {
        channel.stop();
        encryptedChannel.stop();
    }

    @Test
    void returns401ForUnknownChannel() throws Exception {
        // Indistinguishable from a bad signature so the endpoint is not a channelId oracle.
        ResponseEntity<String> response =
                controller
                        .dispatch("unknown", timestamp(), sign(timestamp()), botMessage("m-401x"))
                        .block();
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void returns401WhenHeadersMissing() {
        ResponseEntity<String> response =
                controller.dispatch("dt-test", null, null, botMessage("m-401")).block();
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void returns401ForBadSignature() throws Exception {
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller
                        .dispatch(
                                "dt-test",
                                timestamp,
                                sign("wrong-secret", timestamp),
                                botMessage("m-401b"))
                        .block();
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void returns401ForStaleTimestamp() throws Exception {
        String stale = Long.toString(System.currentTimeMillis() - 2 * 3_600_000L);
        ResponseEntity<String> response =
                controller.dispatch("dt-test", stale, sign(stale), botMessage("m-401c")).block();
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void dispatchesPlainMessage() throws Exception {
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller
                        .dispatch("dt-test", timestamp, sign(timestamp), botMessage("m-1"))
                        .block();
        assertEquals(200, response.getStatusCode().value());
        verify(gateway, times(1)).run(any(), any(), any(), any(), any());
    }

    @Test
    void dropsDuplicateMsgId() throws Exception {
        String timestamp = timestamp();
        String sign = sign(timestamp);
        ResponseEntity<String> first =
                controller.dispatch("dt-test", timestamp, sign, botMessage("m-dup")).block();
        ResponseEntity<String> second =
                controller.dispatch("dt-test", timestamp, sign, botMessage("m-dup")).block();
        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());
        verify(gateway, times(1)).run(any(), any(), any(), any(), any());
    }

    @Test
    void dispatchesEncryptedMessage() throws Exception {
        String body = encryptedBody(botMessage("m-enc"));
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller.dispatch("dt-enc", timestamp, sign(timestamp), body).block();
        assertEquals(200, response.getStatusCode().value());
        verify(encryptedGateway, times(1)).run(any(), any(), any(), any(), any());
    }

    @Test
    void returns400WhenEncryptedBodyArrivesWithoutAesKey() throws Exception {
        String body = encryptedBody(botMessage("m-nokey"));
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller.dispatch("dt-test", timestamp, sign(timestamp), body).block();
        assertEquals(400, response.getStatusCode().value());
        verify(gateway, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void returns400ForPlainBodyOnEncryptedChannel() throws Exception {
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller
                        .dispatch("dt-enc", timestamp, sign(timestamp), botMessage("m-plain-enc"))
                        .block();
        assertEquals(400, response.getStatusCode().value());
        verify(encryptedGateway, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void returns400ForOversizedBody() throws Exception {
        String timestamp = timestamp();
        String large =
                "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + "x".repeat(70_000) + "\"}}";
        ResponseEntity<String> response =
                controller.dispatch("dt-test", timestamp, sign(timestamp), large).block();
        assertEquals(400, response.getStatusCode().value());
        verify(gateway, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void returns400ForMalformedJson() throws Exception {
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller.dispatch("dt-test", timestamp, sign(timestamp), "not-json").block();
        assertEquals(400, response.getStatusCode().value());
        verify(gateway, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void acksNonTextMessageWithoutDispatch() throws Exception {
        String timestamp = timestamp();
        ResponseEntity<String> response =
                controller
                        .dispatch(
                                "dt-test",
                                timestamp,
                                sign(timestamp),
                                "{\"msgtype\":\"picture\",\"msgId\":\"m-pic\",\"conversationType\":\"1\",\"senderStaffId\":\"s1\"}")
                        .block();
        assertEquals(200, response.getStatusCode().value());
        verify(gateway, never()).run(any(), any(), any(), any(), any());
    }

    private static String botMessage(String msgId) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"},\"conversationId\":\"cid-1\","
                   + "\"conversationType\":\"1\",\"senderStaffId\":\"staff-1\",\"msgId\":\""
                + msgId
                + "\"}";
    }

    private static String encryptedBody(String json) throws Exception {
        return "{\"encrypt\":\"" + encrypt(json, null) + "\"}";
    }
}
