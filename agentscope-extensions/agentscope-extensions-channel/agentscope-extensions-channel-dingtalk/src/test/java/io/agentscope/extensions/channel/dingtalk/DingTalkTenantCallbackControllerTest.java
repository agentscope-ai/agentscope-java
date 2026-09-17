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

import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.appKeyOf;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.encrypt;
import static io.agentscope.extensions.channel.dingtalk.DingTalkCallbackTestSupport.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
 * Tests for {@link DingTalkCallbackController} in multi-tenant wiring: credentials resolved per
 * callback through {@link DingTalkTenantChannelManager}. The static-registry wiring, including
 * its regression coverage, lives in {@link DingTalkCallbackControllerTest}.
 */
class DingTalkTenantCallbackControllerTest {

    private MockWebServer server;
    private Map<String, DingTalkChannelProperties> tenants;
    private DingTalkTenantChannelManager manager;
    private DingTalkCallbackController controller;
    private Gateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new DingTalkCallbackTestSupport.ApiDispatcher());
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
                new DingTalkTenantChannelManager(
                        key -> Optional.ofNullable(tenants.get(key)),
                        ChannelConfig.of("dingtalk", "main"),
                        gateway);
        controller = new DingTalkCallbackController(manager);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (String key : tenants.keySet()) {
            manager.evict(key);
        }
        server.shutdown();
    }

    @Test
    void dispatchesWithResolvedTenantCredentials() throws Exception {
        DingTalkChannelProperties props = tenant("acme", "app-acme");

        ResponseEntity<String> response = post("acme", props, "9001");

        assertEquals(200, response.getStatusCode().value());
        RecordedRequest token = takeRequest();
        assertTrue(token.getPath().startsWith("/v1.0/oauth2/accessToken"));
        assertEquals("app-acme", appKeyOf(token));
        RecordedRequest send = takeRequest();
        assertTrue(send.getPath().startsWith("/v1.0/robot/oToMessages/batchSend"));
        assertEquals("tok-app-acme", send.getHeader("x-acs-dingtalk-access-token"));
        // The tenant key namespaces the session; the platform account is the tenant's app key.
        ArgumentCaptor<MsgContext> context = ArgumentCaptor.forClass(MsgContext.class);
        ArgumentCaptor<InboundMessage> inbound = ArgumentCaptor.forClass(InboundMessage.class);
        verify(gateway).run(context.capture(), any(), any(), any(), inbound.capture());
        assertEquals("acme", context.getValue().channel());
        assertEquals("app-acme", inbound.getValue().accountId());
    }

    @Test
    void dispatchesPlaintextCallbackForTenantWithoutAesKey() throws Exception {
        DingTalkChannelProperties props = tenant("plain", "app-plain", null);

        ResponseEntity<String> response = post("plain", props, "9002");

        assertEquals(200, response.getStatusCode().value());
        // Drain the async reply chain before counting: the outbound send races the response.
        assertTrue(takeRequest().getPath().startsWith("/v1.0/oauth2/accessToken"));
        assertTrue(takeRequest().getPath().startsWith("/v1.0/robot/"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void rejectsSignatureFromAnotherTenant() throws Exception {
        tenant("acme", "app-acme");
        DingTalkChannelProperties other = tenant("globex", "app-globex");

        ResponseEntity<String> response = post("acme", other, "9003");

        assertEquals(401, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void unknownTenantAnswersUnauthorized() throws Exception {
        ResponseEntity<String> response = post("ghost", properties("app-ghost", null), "9004");

        // Indistinguishable from a bad signature so the endpoint is not a tenant-key oracle.
        assertEquals(401, response.getStatusCode().value());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void aesKeyRotationTakesEffectOnNextCallbackWithoutLosingTheTokenCache() throws Exception {
        String key = "rotating";
        DingTalkChannelProperties before = tenant(key, "app-rot");
        assertEquals(200, post(key, before, "9101").getStatusCode().value());
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());

        DingTalkChannelProperties rotated =
                rotate(before, before.appSecret(), DingTalkCallbackTestSupport.newAesKey());
        tenants.put(key, rotated);

        // The HMAC secret is unchanged so the old envelope still verifies, but it no longer
        // decrypts under the rotated AES key.
        assertEquals(400, post(key, before, "9102").getStatusCode().value());
        assertEquals(200, post(key, rotated, "9103").getStatusCode().value());
        takeRequest();

        // The app secret did not change, so the cached access token survived the rotation: the last
        // callback sent its reply without minting a new token (three requests, not four).
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void secretRotationDiscardsTheCachedAccessToken() throws Exception {
        String key = "secret-rotating";
        DingTalkChannelProperties before = tenant(key, "app-srot");
        assertEquals(200, post(key, before, "9401").getStatusCode().value());
        takeRequest();
        takeRequest();
        assertEquals(2, server.getRequestCount());

        DingTalkChannelProperties rotated = rotate(before, "secret-rotated", before.aesKey());
        tenants.put(key, rotated);

        assertEquals(401, post(key, before, "9402").getStatusCode().value());
        assertEquals(200, post(key, rotated, "9403").getStatusCode().value());
        takeRequest();
        takeRequest();

        // A rotated app secret invalidates the access token minted from it: fresh token + send.
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void sameMsgIdFromDifferentTenantsIsNotDeduplicatedAway() throws Exception {
        DingTalkChannelProperties acme = tenant("acme-dedup", "app-acme-dedup");
        DingTalkChannelProperties globex = tenant("globex-dedup", "app-globex-dedup");

        assertEquals(200, post("acme-dedup", acme, "7777").getStatusCode().value());
        assertEquals(200, post("globex-dedup", globex, "7777").getStatusCode().value());

        // Both dispatched — two token fetches and two reply sends. A deduplication key shared
        // across tenants would have dropped the second callback.
        for (int i = 0; i < 4; i++) {
            takeRequest();
        }
        assertEquals(4, server.getRequestCount());
        verify(gateway, times(2)).run(any(), any(), any(), any(), any());
    }

    /** Builds properties for a tenant that is not registered with the resolver. */
    private DingTalkChannelProperties properties(String appKey, String aesKey) {
        return new DingTalkChannelProperties(
                appKey,
                "secret-" + appKey,
                "robot-" + appKey,
                DingTalkChannelProperties.MODE_HTTP,
                aesKey,
                apiBase(),
                null,
                null);
    }

    /** Builds properties and registers the tenant under {@code key}. */
    private DingTalkChannelProperties tenant(String key, String appKey) {
        return tenant(key, appKey, DingTalkCallbackTestSupport.newAesKey());
    }

    private DingTalkChannelProperties tenant(String key, String appKey, String aesKey) {
        DingTalkChannelProperties props = properties(appKey, aesKey);
        tenants.put(key, props);
        return props;
    }

    /** The same tenant with the app secret and/or the callback AES key replaced. */
    private static DingTalkChannelProperties rotate(
            DingTalkChannelProperties base, String appSecret, String aesKey) {
        return new DingTalkChannelProperties(
                base.appKey(),
                appSecret,
                base.robotCode(),
                base.mode(),
                aesKey,
                base.apiBase(),
                base.oapiBase(),
                base.streamRegisterUrl());
    }

    private String apiBase() {
        return server.url("/").toString();
    }

    /**
     * Posts a well-formed callback for {@code props}' tenant: encrypted when the tenant configures
     * an AES key, plain otherwise, always signed with the tenant's app secret.
     */
    private ResponseEntity<String> post(
            String tenantKey, DingTalkChannelProperties props, String msgId) throws Exception {
        // A live timestamp: the controller enforces a freshness window, so a fixed one is rejected.
        String timestamp = Long.toString(System.currentTimeMillis());
        String body = botMessage(msgId);
        if (props.aesKey() != null) {
            body = "{\"encrypt\":\"" + encrypt(props.aesKey(), body, null) + "\"}";
        }
        return controller
                .dispatch(tenantKey, timestamp, sign(props.appSecret(), timestamp), body)
                .block();
    }

    private RecordedRequest takeRequest() {
        try {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request, "expected a request to the DingTalk API");
            return request;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String appKeyOf(RecordedRequest request) {
        try {
            return new ObjectMapper()
                    .readTree(request.getBody().readUtf8())
                    .path("appKey")
                    .asText(null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String botMessage(String msgId) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"},\"conversationId\":\"cid-1\","
                   + "\"conversationType\":\"1\",\"senderStaffId\":\"staff-1\",\"msgId\":\""
                + msgId
                + "\"}";
    }
}
