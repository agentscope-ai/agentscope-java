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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Rotation semantics of {@link DingTalkChannel}: which collaborators a credential change rebuilds,
 * the access-token store each credential generation mints into, and the credential generation a
 * callback is mapped and answered under.
 */
class DingTalkChannelCredentialRotationTest {

    private static final String CHANNEL_ID = "rotating";
    private static final String APP_KEY = "app-rot";
    private static final String ROTATED_APP_KEY = "app-rot2";
    private static final String SENDER = "staff-1";

    private MockWebServer server;

    /** The stores handed to each credential generation, in creation order. */
    private List<AccessTokenStore> tokenStores;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new DingTalkCallbackTestSupport.ApiDispatcher());
        server.start();
        tokenStores = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void aCallbackOnlyRotationKeepsMintingIntoTheSameTokenStore() {
        DingTalkChannelProperties initial = properties(APP_KEY, "secret-1", AES_KEY);
        DingTalkChannel channel = channel(initial);
        send(channel);
        takeRequest(); // oauth2/accessToken
        takeRequest(); // the send itself
        assertEquals(1, tokenStores.size());
        assertEquals("tok-" + APP_KEY, tokenStores.get(0).get().value());

        channel.refreshCredentials(
                rotate(
                        initial,
                        initial.appKey(),
                        initial.appSecret(),
                        DingTalkCallbackTestSupport.newAesKey()));
        send(channel);
        RecordedRequest send = takeRequest();

        // The app credentials did not change, so the outbound runtime — and its store — is the one
        // built before the rotation: the cached token still serves, with no second token request.
        assertEquals(1, tokenStores.size());
        assertEquals("tok-" + APP_KEY, send.getHeader("x-acs-dingtalk-access-token"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void aRotatedAppCredentialNeverReadsThePreviousGenerationsTokenStore() {
        DingTalkChannelProperties initial = properties(APP_KEY, "secret-1", AES_KEY);
        DingTalkChannel channel = channel(initial);
        send(channel);
        takeRequest(); // oauth2/accessToken
        takeRequest(); // the send itself
        AccessTokenStore firstGeneration = tokenStores.get(0);
        assertEquals("tok-" + APP_KEY, firstGeneration.get().value());

        // Rotate the app credentials while a request on the previous generation is still in flight;
        // that request finishes and writes the token it minted from the rotated-out secret into the
        // store it holds.
        channel.refreshCredentials(rotate(initial, ROTATED_APP_KEY, "secret-2", initial.aesKey()));
        firstGeneration.put(
                new CachedAccessToken("token-of-the-rotated-out-secret", Long.MAX_VALUE));

        assertEquals(2, tokenStores.size());
        assertNull(tokenStores.get(1).get());
        send(channel);
        takeRequest(); // oauth2/accessToken: the new generation's store starts empty
        RecordedRequest send = takeRequest();

        // The new generation minted its own token and sent with it; the late write above lives in a
        // store nothing reads any more.
        assertEquals("tok-" + ROTATED_APP_KEY, tokenStores.get(1).get().value());
        assertEquals("tok-" + ROTATED_APP_KEY, send.getHeader("x-acs-dingtalk-access-token"));
    }

    @Test
    void aCallbackKeepsTheCredentialGenerationItsRequestEnteredWith() throws Exception {
        DingTalkChannelProperties initial = properties(APP_KEY, "secret-1", AES_KEY);
        DingTalkChannel channel = channel(initial);
        Gateway gateway = mock(Gateway.class);
        when(gateway.run(any(), any(), any(), any(), any()))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("reply")
                                        .build()));
        channel.init(gateway);
        send(channel);
        takeRequest(); // oauth2/accessToken
        takeRequest(); // the send itself

        DingTalkChannel.Credentials entered = channel.credentials();
        // Rotate the app key as well as the secret, so the two generations mint recognizably
        // different tokens: both the account the callback is attributed to and the token its reply
        // authenticates with then name the generation they came from.
        channel.refreshCredentials(rotate(initial, ROTATED_APP_KEY, "secret-2", initial.aesKey()));

        channel.onInboundPayload(new ObjectMapper().readTree(botMessage("m-1")), entered);
        RecordedRequest reply = takeRequest();

        // The intake ran on the snapshot the caller entered with, not on the generation the
        // rotation installed: the callback is attributed to the pre-rotation app key and its reply
        // authenticated with that generation's cached token, so no token request was needed.
        ArgumentCaptor<InboundMessage> inbound = ArgumentCaptor.forClass(InboundMessage.class);
        verify(gateway).run(any(), any(), any(), any(), inbound.capture());
        assertEquals(APP_KEY, inbound.getValue().accountId());
        assertEquals("tok-" + APP_KEY, reply.getHeader("x-acs-dingtalk-access-token"));
        assertEquals(3, server.getRequestCount());
    }

    private DingTalkChannel channel(DingTalkChannelProperties properties) {
        return DingTalkChannel.fromProperties(
                CHANNEL_ID,
                ChannelConfig.of(CHANNEL_ID, "main"),
                properties,
                new IdempotencyStore(),
                generation -> {
                    InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
                    tokenStores.add(store);
                    return store;
                });
    }

    private void send(DingTalkChannel channel) {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("hello").build();
        StepVerifier.create(
                        channel.credentials()
                                .outboundClient()
                                .send(
                                        OutboundAddress.direct(
                                                "dingtalk", "dingtalk:DIRECT:" + SENDER),
                                        List.of(msg)))
                .verifyComplete();
    }

    private RecordedRequest takeRequest() {
        try {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            if (request == null) {
                throw new IllegalStateException("expected a request to the DingTalk API");
            }
            return request;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String apiBase() {
        return server.url("/").toString();
    }

    private DingTalkChannelProperties properties(String appKey, String appSecret, String aesKey) {
        return new DingTalkChannelProperties(
                appKey,
                appSecret,
                "robot-" + appKey,
                DingTalkChannelProperties.MODE_HTTP,
                aesKey,
                apiBase(),
                null,
                null);
    }

    /** The same tenant with the app key, the app secret and/or the callback AES key replaced. */
    private static DingTalkChannelProperties rotate(
            DingTalkChannelProperties base, String appKey, String appSecret, String aesKey) {
        return new DingTalkChannelProperties(
                appKey,
                appSecret,
                base.robotCode(),
                base.mode(),
                aesKey,
                base.apiBase(),
                base.oapiBase(),
                base.streamRegisterUrl());
    }

    private static String botMessage(String msgId) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"},\"conversationId\":\"cid-1\","
                   + "\"conversationType\":\"1\",\"senderStaffId\":\""
                + SENDER
                + "\",\"msgId\":\""
                + msgId
                + "\"}";
    }
}
