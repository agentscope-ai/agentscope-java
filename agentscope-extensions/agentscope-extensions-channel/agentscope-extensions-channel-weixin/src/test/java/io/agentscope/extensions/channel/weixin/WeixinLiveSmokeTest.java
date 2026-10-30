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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

/** Interactive real-iLink smoke test. Credentials live only in this test process. */
class WeixinLiveSmokeTest {
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "WEIXIN_LIVE_TEST", matches = "(?i)true")
    void scansQrReceivesAndRepliesThroughRealWeixin() throws Exception {
        WeixinLoginClient login = new WeixinLoginClient();
        WeixinLoginChallenge loginChallenge = login.start("3");
        WeixinLoginSession session = loginChallenge.session();
        System.out.println("WEIXIN_QR_URL=" + loginChallenge.imageContent());
        System.out.println("WEIXIN_LOGIN_STATUS=waiting_for_scan");

        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        WeixinLoginStep authenticated = waitForLogin(login, session, input);
        assertNotNull(authenticated.credentials(), "Confirmed login did not return bot_token");
        assertNotNull(authenticated.accountId(), "Confirmed login did not return ilink_bot_id");
        System.out.println("WEIXIN_API_BASE_URL=" + authenticated.baseUrl());

        String challenge =
                "AS-JAVA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String acknowledgement = "ACK-" + challenge.substring("AS-JAVA-".length());
        CountDownLatch verified = new CountDownLatch(1);
        Gateway gateway =
                new Gateway() {
                    @Override
                    public void bindMainAgent(HarnessAgent agent) {}

                    @Override
                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        String text = messages.get(0).getTextContent();
                        System.out.println("WEIXIN_INBOUND_TEXT=" + text);
                        if (challenge.equals(text)) {
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent(
                                                    "AgentScope Java 已收到。看到此回复后，请发送 "
                                                            + acknowledgement)
                                            .build());
                        }
                        if (acknowledgement.equals(text)) {
                            verified.countDown();
                            // Receiving this one-time ACK proves the user saw the previous outbound
                            // reply. Do not enqueue another reply that could race test shutdown.
                            return Mono.empty();
                        }
                        return Mono.empty();
                    }
                };

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("accountId", authenticated.accountId());
        properties.put("botToken", authenticated.credentials().botToken());
        properties.put(
                "baseUrl",
                authenticated.baseUrl() == null || authenticated.baseUrl().isBlank()
                        ? WeixinChannelProperties.DEFAULT_BASE_URL
                        : authenticated.baseUrl());
        if (authenticated.userId() != null) {
            properties.put("ilinkUserId", authenticated.userId());
        }

        WeixinChannel channel =
                WeixinChannel.fromProperties(
                        "wx-live-smoke", ChannelConfig.of("wx-live-smoke", "main"), properties);
        try {
            channel.init(gateway);
            channel.start();
            System.out.println("WEIXIN_CONNECTED_ACCOUNT=" + authenticated.accountId());
            System.out.println("WEIXIN_SEND_CHALLENGE=" + challenge);
            System.out.println("WEIXIN_EXPECT_ACK=" + acknowledgement);
            assertTrue(
                    verified.await(3, TimeUnit.MINUTES),
                    "Did not receive the acknowledgement after sending the challenge");
        } finally {
            channel.stop();
        }
    }

    private static WeixinLoginStep waitForLogin(
            WeixinLoginClient login, WeixinLoginSession session, BufferedReader input)
            throws Exception {
        String verifyCode = null;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        while (System.nanoTime() < deadline) {
            WeixinLoginStep status =
                    verifyCode == null ? login.poll(session) : login.verify(session, verifyCode);
            session = status.session();
            verifyCode = null;
            System.out.println("WEIXIN_LOGIN_STATUS=" + status.status());
            if (status.connected()) return status;
            if (status.alreadyConnected()) {
                throw new IllegalStateException(
                        "This account is already bound but no reusable token was supplied");
            }
            if ("need_verifycode".equalsIgnoreCase(status.status())) {
                System.out.println("WEIXIN_VERIFY_CODE_REQUIRED=enter_the_number_shown_in_weixin");
                verifyCode = input.readLine();
                if (verifyCode == null || verifyCode.isBlank()) {
                    throw new IllegalStateException("Verification code was not provided");
                }
                verifyCode = verifyCode.strip();
            } else if ("expired".equalsIgnoreCase(status.status())
                    || "verify_code_blocked".equalsIgnoreCase(status.status())) {
                throw new IllegalStateException("Weixin QR login ended with " + status.status());
            }
        }
        throw new IllegalStateException("Timed out waiting for Weixin QR login");
    }
}
