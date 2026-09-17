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
package io.agentscope.extensions.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
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
import reactor.test.StepVerifier;

/**
 * Rotation semantics of {@link WeComChannel}: which collaborators a credential change rebuilds, and
 * the access-token store each credential generation mints into.
 */
class WeComChannelCredentialRotationTest {

    private static final String CHANNEL_ID = "rotating";

    private MockWebServer server;

    /** The stores handed to each credential generation, in creation order. */
    private List<AccessTokenStore> tokenStores;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new WeComTestSupport.ApiDispatcher());
        server.start();
        tokenStores = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void aCallbackOnlyRotationKeepsMintingIntoTheSameTokenStore() {
        WeComChannelProperties initial = properties("secret-1");
        WeComChannel channel = channel(initial);
        send(channel);
        takeRequest(); // gettoken
        takeRequest(); // the send itself
        assertEquals(1, tokenStores.size());
        assertEquals("tok-corp-rot", tokenStores.get(0).get().value());

        channel.refreshCredentials(rotate(initial, "token-rotated", initial.secret()));
        send(channel);
        RecordedRequest send = takeRequest();

        // The app credentials did not change, so the outbound runtime — and its store — is the one
        // built before the rotation: the cached token still serves, with no second gettoken.
        assertEquals(1, tokenStores.size());
        assertEquals("tok-corp-rot", WeComTestSupport.query(send, "access_token"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void aRotatedAppCredentialNeverReadsThePreviousGenerationsTokenStore() {
        WeComChannelProperties initial = properties("secret-1");
        WeComChannel channel = channel(initial);
        send(channel);
        takeRequest(); // gettoken
        takeRequest(); // the send itself
        AccessTokenStore firstGeneration = tokenStores.get(0);
        assertEquals("tok-corp-rot", firstGeneration.get().value());

        // Rotate the app credentials while a request on the previous generation is still in flight;
        // that request finishes and writes the token it minted from the rotated-out secret into the
        // store it holds.
        channel.refreshCredentials(rotate(initial, initial.token(), "secret-2"));
        firstGeneration.put(
                new CachedAccessToken("token-of-the-rotated-out-secret", Long.MAX_VALUE));

        assertEquals(2, tokenStores.size());
        assertNull(tokenStores.get(1).get());
        send(channel);
        takeRequest(); // gettoken: the new generation's store starts empty
        RecordedRequest send = takeRequest();

        // The new generation minted its own token and sent with it; the late write above lives in a
        // store nothing reads any more.
        assertEquals("tok-corp-rot", tokenStores.get(1).get().value());
        assertEquals("tok-corp-rot", WeComTestSupport.query(send, "access_token"));
    }

    private WeComChannel channel(WeComChannelProperties properties) {
        return WeComChannel.fromProperties(
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

    private void send(WeComChannel channel) {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("hello").build();
        StepVerifier.create(
                        channel.credentials()
                                .outboundClient()
                                .send(
                                        OutboundAddress.direct("wecom", "wecom:DIRECT:alice"),
                                        List.of(msg)))
                .verifyComplete();
    }

    private RecordedRequest takeRequest() {
        try {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            if (request == null) {
                throw new IllegalStateException("expected a request to the WeCom API");
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

    private WeComChannelProperties properties(String secret) {
        return new WeComChannelProperties(
                "corp-rot",
                1000002,
                secret,
                "token-rot",
                WeComTestSupport.newAesKey(),
                null,
                apiBase());
    }

    /** The same tenant with the callback token and/or the secret replaced. */
    private static WeComChannelProperties rotate(
            WeComChannelProperties base, String token, String secret) {
        return new WeComChannelProperties(
                base.corpId(),
                base.agentId(),
                secret,
                token,
                base.encodingAesKey(),
                base.callbackPath(),
                base.apiBase());
    }
}
