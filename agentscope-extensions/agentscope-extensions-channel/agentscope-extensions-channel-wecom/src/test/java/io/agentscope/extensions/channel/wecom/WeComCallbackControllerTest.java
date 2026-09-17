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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link WeComCallbackController}: multi-tenant credential resolution end to end, plus
 * a static-mode regression check.
 */
class WeComCallbackControllerTest {

    private static final String TIMESTAMP = "1700000000";
    private static final String NONCE = "nonce-1";

    private MockWebServer server;
    private Map<String, WeComChannelProperties> tenants;
    private WeComTenantChannelManager manager;
    private WeComCallbackController controller;
    private Gateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new WeComTestSupport.ApiDispatcher());
        server.start();
        tenants = new HashMap<>();
        gateway = mock(Gateway.class);
        when(gateway.run(any(), any(), any(), any(), any()))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .textContent("reply")
                                        .build()));
        manager =
                new WeComTenantChannelManager(
                        key -> Optional.ofNullable(tenants.get(key)),
                        ChannelConfig.of("wecom", "main"),
                        gateway);
        controller = new WeComCallbackController(manager);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (String key : tenants.keySet()) {
            manager.evict(key);
        }
        server.shutdown();
    }

    @Test
    void dispatchesWithResolvedTenantCredentials() {
        WeComChannelProperties props = tenant("acme", "corp-acme");

        ResponseEntity<String> response = post(controller, "acme", props, "9001");

        assertEquals(200, response.getStatusCode().value());
        RecordedRequest gettoken = takeRequest();
        assertTrue(gettoken.getPath().startsWith("/cgi-bin/gettoken"));
        assertEquals("corp-acme", WeComTestSupport.query(gettoken, "corpid"));
        RecordedRequest send = takeRequest();
        assertTrue(send.getPath().startsWith("/cgi-bin/message/send"));
        assertEquals("tok-corp-acme", WeComTestSupport.query(send, "access_token"));
        assertTrue(send.getBody().readUtf8().contains("\"touser\":\"alice\""));
        // The tenant key namespaces the session, and the platform account is the tenant's corp.
        ArgumentCaptor<MsgContext> context = ArgumentCaptor.forClass(MsgContext.class);
        ArgumentCaptor<InboundMessage> inbound = ArgumentCaptor.forClass(InboundMessage.class);
        verify(gateway).run(context.capture(), any(), any(), any(), inbound.capture());
        assertEquals("acme", context.getValue().channel());
        assertEquals("corp-acme", inbound.getValue().accountId());
    }

    @Test
    void rejectsSignatureFromAnotherTenant() {
        tenant("acme", "corp-acme");
        WeComChannelProperties other = tenant("globex", "corp-globex");

        ResponseEntity<String> response = post(controller, "acme", other, "9002");

        assertEquals(401, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void unknownTenantIsRejected() {
        ResponseEntity<String> response =
                post(controller, "ghost", properties("corp-ghost"), "9003");

        assertEquals(404, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void rotationTakesEffectOnNextCallbackWithoutLosingTheTokenCache() {
        String key = "rotating";
        WeComChannelProperties before = tenant(key, "corp-rot");
        assertEquals(200, post(controller, key, before, "9101").getStatusCode().value());
        // Drain the async reply chain before counting: the outbound send races the response.
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());

        WeComChannelProperties rotated =
                new WeComChannelProperties(
                        "corp-rot",
                        1000002,
                        before.secret(),
                        "token-rotated",
                        before.encodingAesKey(),
                        null,
                        apiBase());
        tenants.put(key, rotated);

        assertEquals(401, post(controller, key, before, "9102").getStatusCode().value());
        assertEquals(200, post(controller, key, rotated, "9103").getStatusCode().value());
        takeRequest();

        // The secret did not change, so the cached access token survived the rotation: the last
        // callback sent its reply without minting a new token (three requests, not four).
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void secretRotationDiscardsTheCachedAccessToken() {
        String key = "secret-rotating";
        WeComChannelProperties before = tenant(key, "corp-srot");
        assertEquals(200, post(controller, key, before, "9401").getStatusCode().value());
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());

        WeComChannelProperties rotated =
                new WeComChannelProperties(
                        "corp-srot",
                        1000002,
                        "secret-rotated",
                        before.token(),
                        before.encodingAesKey(),
                        null,
                        apiBase());
        tenants.put(key, rotated);

        assertEquals(200, post(controller, key, rotated, "9402").getStatusCode().value());
        takeRequest();
        takeRequest();

        // A rotated secret invalidates the access token minted from it: fresh gettoken + send.
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void replyKeepsTheCredentialGenerationTheRequestEnteredWith() {
        String key = "snapshot";
        WeComChannelProperties before = properties("corp-snap");
        WeComChannelProperties rotated =
                new WeComChannelProperties(
                        before.corpId(),
                        2000003,
                        before.secret(),
                        before.token(),
                        before.encodingAesKey(),
                        before.callbackPath(),
                        apiBase());
        Map<String, WeComChannelProperties> store = new HashMap<>();
        store.put(key, before);
        // Rotate the tenant and let the next lookup apply the rotation exactly between the
        // controller capturing its credential snapshot and the reply being delivered: the
        // deduplicator runs in that window.
        AtomicReference<WeComTenantChannelManager> tenantManager = new AtomicReference<>();
        InboundEventDeduplicator rotating =
                eventKey -> {
                    store.put(key, rotated);
                    tenantManager.get().channelFor(key);
                    return true;
                };
        WeComTenantChannelManager snapshotManager =
                new WeComTenantChannelManager(
                        k -> Optional.ofNullable(store.get(k)),
                        ChannelConfig.of("wecom", "main"),
                        gateway,
                        rotating);
        tenantManager.set(snapshotManager);
        WeComCallbackController snapshotController = new WeComCallbackController(snapshotManager);

        assertEquals(200, post(snapshotController, key, before, "9301").getStatusCode().value());

        takeRequest(); // gettoken
        RecordedRequest send = takeRequest();
        // The reply was delivered with the generation the request verified and mapped with — agent
        // 1000002 — not the one the rotation installed mid-request (2000003).
        assertTrue(send.getBody().readUtf8().contains("\"agentid\":1000002"));
    }

    @Test
    void verifyHandshakeResolvesTenantCredentials() {
        WeComChannelProperties props = tenant("handshake", "corp-hs");
        String echostr =
                WeComTestSupport.encrypt(props.encodingAesKey(), props.corpId(), "challenge-123");
        String signature = WeComTestSupport.sign(props.token(), TIMESTAMP, NONCE, echostr);

        ResponseEntity<String> response =
                controller.verify("handshake", signature, TIMESTAMP, NONCE, echostr);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("challenge-123", response.getBody());
    }

    @Test
    void sameMsgIdFromDifferentTenantsIsNotDeduplicatedAway() {
        WeComChannelProperties acme = tenant("acme-dedup", "corp-acme-dedup");
        WeComChannelProperties globex = tenant("globex-dedup", "corp-globex-dedup");

        assertEquals(200, post(controller, "acme-dedup", acme, "7777").getStatusCode().value());
        assertEquals(200, post(controller, "globex-dedup", globex, "7777").getStatusCode().value());

        // Both dispatched — two token fetches and two reply sends. A deduplication key shared
        // across tenants would have dropped the second callback.
        for (int i = 0; i < 4; i++) {
            takeRequest();
        }
        assertEquals(4, server.getRequestCount());
        verify(gateway, times(2)).run(any(), any(), any(), any(), any());
    }

    @Test
    void staticModeStillDispatchesRegisteredChannel() {
        String channelId = "static-wecom";
        WeComChannelProperties props = properties("corp-static");
        WeComChannel channel =
                WeComChannel.fromProperties(
                        channelId,
                        ChannelConfig.of(channelId, "main"),
                        props,
                        new IdempotencyStore(),
                        InMemoryAccessTokenStore::new);
        channel.init(gateway);
        channel.start();
        try {
            WeComCallbackController staticController = new WeComCallbackController();

            ResponseEntity<String> response = post(staticController, channelId, props, "9201");

            assertEquals(200, response.getStatusCode().value());
            ArgumentCaptor<MsgContext> context = ArgumentCaptor.forClass(MsgContext.class);
            verify(gateway).run(context.capture(), any(), any(), any(), any());
            assertEquals(channelId, context.getValue().channel());
        } finally {
            channel.stop();
        }
    }

    /** Builds properties for a tenant that is not registered with the resolver. */
    private WeComChannelProperties properties(String corpId) {
        return new WeComChannelProperties(
                corpId,
                1000002,
                "secret-" + corpId,
                "token-" + corpId,
                WeComTestSupport.newAesKey(),
                null,
                apiBase());
    }

    /** Builds properties and registers the tenant under {@code key}. */
    private WeComChannelProperties tenant(String key, String corpId) {
        WeComChannelProperties props = properties(corpId);
        tenants.put(key, props);
        return props;
    }

    private String apiBase() {
        return server.url("/").toString();
    }

    /** Posts a well-formed callback signed and encrypted with {@code props}' credentials. */
    private ResponseEntity<String> post(
            WeComCallbackController target,
            String tenantKey,
            WeComChannelProperties props,
            String msgId) {
        String encrypt =
                WeComTestSupport.encrypt(
                        props.encodingAesKey(),
                        props.corpId(),
                        WeComTestSupport.textMessage("alice", "hello", msgId));
        String signature = WeComTestSupport.sign(props.token(), TIMESTAMP, NONCE, encrypt);
        return target.dispatch(
                        tenantKey, signature, TIMESTAMP, NONCE, WeComTestSupport.envelope(encrypt))
                .block();
    }

    private RecordedRequest takeRequest() {
        try {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request, "expected a request to the WeCom API");
            return request;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
