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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link FeishuCallbackController}: multi-tenant credential resolution end to end, plus
 * a static-mode regression check.
 */
class FeishuCallbackControllerTest {

    private static final String TIMESTAMP = "1700000000";
    private static final String NONCE = "nonce-1";

    private MockWebServer server;
    private Map<String, FeishuChannelProperties> tenants;
    private FeishuTenantChannelManager manager;
    private FeishuCallbackController controller;
    private Gateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new FeishuTestSupport.ApiDispatcher());
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
                new FeishuTenantChannelManager(
                        key -> Optional.ofNullable(tenants.get(key)),
                        ChannelConfig.of("feishu", "main"),
                        gateway);
        controller = new FeishuCallbackController(manager);
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
        FeishuChannelProperties props = tenant("acme", "cli_acme");

        ResponseEntity<String> response = post(controller, "acme", props, "9001");

        assertEquals(200, response.getStatusCode().value());
        RecordedRequest token = takeRequest();
        assertTrue(token.getPath().startsWith("/open-apis/auth/v3/tenant_access_token/internal"));
        assertEquals("cli_acme", FeishuTestSupport.appIdOf(token));
        RecordedRequest send = takeRequest();
        assertTrue(send.getPath().startsWith("/open-apis/im/v1/messages"));
        assertEquals("Bearer tok-cli_acme", send.getHeader("Authorization"));
        assertTrue(send.getBody().readUtf8().contains("oc_acme"));
        // The tenant key namespaces the session; the platform account is the event's tenant key.
        ArgumentCaptor<MsgContext> context = ArgumentCaptor.forClass(MsgContext.class);
        ArgumentCaptor<InboundMessage> inbound = ArgumentCaptor.forClass(InboundMessage.class);
        verify(gateway).run(context.capture(), any(), any(), any(), inbound.capture());
        assertEquals("acme", context.getValue().channel());
        assertEquals("acme", inbound.getValue().accountId());
    }

    @Test
    void dispatchesPlaintextCallbackForUnencryptedTenant() {
        FeishuChannelProperties props =
                new FeishuChannelProperties(
                        "cli_plain", "secret-plain", null, "vtok-plain", null, apiBase());
        tenants.put("plain", props);

        ResponseEntity<String> response = post(controller, "plain", props, "9002");

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<MsgContext> context = ArgumentCaptor.forClass(MsgContext.class);
        verify(gateway).run(context.capture(), any(), any(), any(), any());
        assertEquals("plain", context.getValue().channel());
        // Drain the async reply chain before counting: the outbound send races the response.
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void rejectsSignatureFromAnotherTenant() {
        tenant("acme", "cli_acme");
        FeishuChannelProperties other = tenant("globex", "cli_globex");

        ResponseEntity<String> response = post(controller, "acme", other, "9003");

        assertEquals(401, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void unknownTenantIsRejected() {
        ResponseEntity<String> response =
                post(controller, "ghost", properties("cli_ghost"), "9004");

        assertEquals(404, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void rotationTakesEffectOnNextCallbackWithoutLosingTheTokenCache() {
        String key = "rotating";
        FeishuChannelProperties before = tenant(key, "cli_rot");
        assertEquals(200, post(controller, key, before, "9101").getStatusCode().value());
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());

        FeishuChannelProperties rotated =
                new FeishuChannelProperties(
                        before.appId(),
                        before.appSecret(),
                        FeishuTestSupport.newEncryptKey(),
                        before.verificationToken(),
                        before.callbackPath(),
                        apiBase());
        tenants.put(key, rotated);

        assertEquals(401, post(controller, key, before, "9102").getStatusCode().value());
        assertEquals(200, post(controller, key, rotated, "9103").getStatusCode().value());
        takeRequest();

        // The app secret did not change, so the cached access token survived the rotation: the last
        // callback sent its reply without minting a new token (three requests, not four).
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void secretRotationDiscardsTheCachedAccessToken() {
        String key = "secret-rotating";
        FeishuChannelProperties before = tenant(key, "cli_srot");
        assertEquals(200, post(controller, key, before, "9401").getStatusCode().value());
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());

        FeishuChannelProperties rotated =
                new FeishuChannelProperties(
                        before.appId(),
                        "secret-rotated",
                        before.encryptKey(),
                        before.verificationToken(),
                        before.callbackPath(),
                        apiBase());
        tenants.put(key, rotated);

        assertEquals(200, post(controller, key, rotated, "9402").getStatusCode().value());
        takeRequest();
        takeRequest();

        // A rotated app secret invalidates the access token minted from it: fresh token + send.
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void verifyHandshakeResolvesTenantCredentials() {
        FeishuChannelProperties props = tenant("handshake", "cli_hs");
        String plain =
                FeishuTestSupport.urlVerification(props.verificationToken(), "challenge-123");
        String body =
                FeishuTestSupport.envelope(FeishuTestSupport.encrypt(props.encryptKey(), plain));
        String signature = FeishuTestSupport.sign(props.encryptKey(), TIMESTAMP, NONCE, body);

        ResponseEntity<String> response =
                controller.callback("handshake", signature, TIMESTAMP, NONCE, body).block();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"challenge\":\"challenge-123\"}", response.getBody());
    }

    @Test
    void staticModeStillDispatchesRegisteredChannel() {
        String channelId = "static-feishu";
        FeishuChannelProperties props = properties(channelId);
        FeishuChannel channel =
                FeishuChannel.fromProperties(
                        channelId,
                        ChannelConfig.of(channelId, "main"),
                        props,
                        new IdempotencyStore(),
                        InMemoryAccessTokenStore::new);
        channel.init(gateway);
        channel.start();
        try {
            FeishuCallbackController staticController = new FeishuCallbackController();

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
    private FeishuChannelProperties properties(String appId) {
        return new FeishuChannelProperties(
                appId,
                "secret-" + appId,
                FeishuTestSupport.newEncryptKey(),
                "vtok-" + appId,
                null,
                apiBase());
    }

    /** Builds properties and registers the tenant under {@code key}. */
    private FeishuChannelProperties tenant(String key, String appId) {
        FeishuChannelProperties props = properties(appId);
        tenants.put(key, props);
        return props;
    }

    private String apiBase() {
        return server.url("/").toString();
    }

    /**
     * Posts a well-formed event callback for {@code props}' tenant: encrypted and signed when the
     * tenant configures an encrypt key, plain otherwise.
     */
    private ResponseEntity<String> post(
            FeishuCallbackController target,
            String tenantKey,
            FeishuChannelProperties props,
            String eventId) {
        String rawBody =
                FeishuTestSupport.textEvent(
                        props.verificationToken(),
                        tenantKey,
                        "oc_" + tenantKey,
                        "ou_alice",
                        "hello",
                        eventId);
        String signature = null;
        if (props.isEncrypted()) {
            rawBody =
                    FeishuTestSupport.envelope(
                            FeishuTestSupport.encrypt(props.encryptKey(), rawBody));
            signature = FeishuTestSupport.sign(props.encryptKey(), TIMESTAMP, NONCE, rawBody);
        }
        return target.callback(tenantKey, signature, TIMESTAMP, NONCE, rawBody).block();
    }

    private RecordedRequest takeRequest() {
        try {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request, "expected a request to the Feishu API");
            return request;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
