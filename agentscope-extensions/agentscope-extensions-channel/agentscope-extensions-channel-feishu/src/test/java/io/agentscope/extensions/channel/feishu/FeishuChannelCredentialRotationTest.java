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
package io.agentscope.extensions.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Rotation semantics of {@link FeishuChannel}: which collaborators a credential change rebuilds,
 * the access-token store each credential generation mints into, and the credential generation a
 * reply goes out with.
 */
class FeishuChannelCredentialRotationTest {

    private static final String CHANNEL_ID = "rotating";
    private static final String APP_ID = "cli_rot";
    private static final String ROTATED_APP_ID = "cli_rot2";

    private MockWebServer server;

    /** The stores handed to each credential generation, in creation order. */
    private List<AccessTokenStore> tokenStores;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new FeishuTestSupport.ApiDispatcher());
        server.start();
        tokenStores = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void aCallbackOnlyRotationKeepsMintingIntoTheSameTokenStore() {
        FeishuChannelProperties initial = properties("secret-1");
        FeishuChannel channel = channel(initial);
        send(channel);
        takeRequest(); // tenant_access_token
        takeRequest(); // the send itself
        assertEquals(1, tokenStores.size());
        assertEquals("tok-" + APP_ID, tokenStores.get(0).get().value());

        channel.refreshCredentials(
                rotate(
                        initial,
                        initial.appId(),
                        FeishuTestSupport.newEncryptKey(),
                        initial.verificationToken(),
                        initial.appSecret()));
        send(channel);
        RecordedRequest send = takeRequest();

        // The app credentials did not change, so the outbound runtime — and its store — is the one
        // built before the rotation: the cached token still serves, with no second token request.
        assertEquals(1, tokenStores.size());
        assertEquals("Bearer tok-" + APP_ID, send.getHeader("Authorization"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void aRotatedAppCredentialNeverReadsThePreviousGenerationsTokenStore() {
        FeishuChannelProperties initial = properties("secret-1");
        FeishuChannel channel = channel(initial);
        send(channel);
        takeRequest(); // tenant_access_token
        takeRequest(); // the send itself
        AccessTokenStore firstGeneration = tokenStores.get(0);
        assertEquals("tok-" + APP_ID, firstGeneration.get().value());

        // Rotate the app credentials while a request on the previous generation is still in flight;
        // that request finishes and writes the token it minted from the rotated-out secret into the
        // store it holds.
        channel.refreshCredentials(
                rotate(
                        initial,
                        initial.appId(),
                        initial.encryptKey(),
                        initial.verificationToken(),
                        "secret-2"));
        firstGeneration.put(
                new CachedAccessToken("token-of-the-rotated-out-secret", Long.MAX_VALUE));

        assertEquals(2, tokenStores.size());
        assertNull(tokenStores.get(1).get());
        send(channel);
        takeRequest(); // tenant_access_token: the new generation's store starts empty
        RecordedRequest send = takeRequest();

        // The new generation minted its own token and sent with it; the late write above lives in a
        // store nothing reads any more.
        assertEquals("tok-" + APP_ID, tokenStores.get(1).get().value());
        assertEquals("Bearer tok-" + APP_ID, send.getHeader("Authorization"));
    }

    @Test
    void aReplyKeepsTheCredentialGenerationItsRequestEnteredWith() {
        FeishuChannelProperties initial = properties("secret-1");
        FeishuChannel channel = channel(initial);
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
        takeRequest(); // tenant_access_token
        takeRequest(); // the send itself

        FeishuChannel.Credentials entered = channel.credentials();
        // Rotate the app id as well as the secret, so the two generations mint recognizably
        // different tokens and the reply's Authorization header names the generation it went out
        // with rather than merely implying it.
        channel.refreshCredentials(
                rotate(
                        initial,
                        ROTATED_APP_ID,
                        initial.encryptKey(),
                        initial.verificationToken(),
                        "secret-2"));

        StepVerifier.create(channel.dispatch(inbound(), entered))
                .expectNextCount(1)
                .verifyComplete();
        RecordedRequest reply = takeRequest();

        // The reply went out through the snapshot the caller entered with, not the generation the
        // rotation installed: it authenticated as the pre-rotation app and its token was already
        // cached, so no token request was needed (three requests in total).
        assertEquals("Bearer tok-" + APP_ID, reply.getHeader("Authorization"));
        assertEquals(3, server.getRequestCount());
    }

    private FeishuChannel channel(FeishuChannelProperties properties) {
        return FeishuChannel.fromProperties(
                CHANNEL_ID,
                ChannelConfig.of(CHANNEL_ID, "main"),
                properties,
                new IdempotencyStore(),
                () -> {
                    InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
                    tokenStores.add(store);
                    return store;
                });
    }

    private void send(FeishuChannel channel) {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("hello").build();
        StepVerifier.create(
                        channel.credentials()
                                .outboundClient()
                                .send(
                                        OutboundAddress.direct("feishu", "feishu:DIRECT:oc_rot"),
                                        List.of(msg)))
                .verifyComplete();
    }

    private static InboundMessage inbound() {
        Msg msg = Msg.builder().role(MsgRole.USER).name("ou_alice").textContent("hello").build();
        return InboundMessage.builder(CHANNEL_ID, new Peer(PeerKind.DIRECT, "oc_rot"), List.of(msg))
                .accountId("tenant-rot")
                .senderId("ou_alice")
                .build();
    }

    private RecordedRequest takeRequest() {
        try {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            if (request == null) {
                throw new IllegalStateException("expected a request to the Feishu API");
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

    private FeishuChannelProperties properties(String appSecret) {
        return new FeishuChannelProperties(
                APP_ID, appSecret, FeishuTestSupport.newEncryptKey(), "vtok-rot", null, apiBase());
    }

    /** The same tenant with the app id, callback credentials and/or the app secret replaced. */
    private static FeishuChannelProperties rotate(
            FeishuChannelProperties base,
            String appId,
            String encryptKey,
            String verificationToken,
            String appSecret) {
        return new FeishuChannelProperties(
                appId,
                appSecret,
                encryptKey,
                verificationToken,
                base.callbackPath(),
                base.apiBase());
    }
}
