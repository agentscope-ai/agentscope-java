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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;

/**
 * Tests token-response validation against a loopback server and cache behavior using an
 * unreachable endpoint. No provider credentials or external services are needed.
 */
class DingTalkAccessTokenProviderTest {

    private HttpServer server;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidResponses")
    void invalidResponsePreservesCacheAndAllowsRecovery(String body, String description)
            throws IOException {
        DingTalkAccessTokenProvider liveProvider = loopbackProvider();
        CachedAccessToken expired =
                new CachedAccessToken("old-token", System.currentTimeMillis() - 1_000);
        store.put(expired);
        response.set(body);
        assertThrows(
                IllegalStateException.class,
                () -> liveProvider.token().block(Duration.ofSeconds(5)),
                description);
        assertSame(expired, store.get());
        response.set("{\"accessToken\":\"recovered-token\",\"expireIn\":7200}");
        assertEquals("recovered-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals("recovered-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals(2, requestCount.get());
    }

    @Test
    void missingOptionalExpiryRetainsDefaultLifetime() throws IOException {
        DingTalkAccessTokenProvider liveProvider = loopbackProvider();
        response.set("{\"accessToken\":\"valid-token\"}");
        assertEquals("valid-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals("valid-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals(1, requestCount.get());
        assertTrue(store.get().refreshAtMs() > System.currentTimeMillis() + 5_000_000);
    }

    @Test
    void malformedResponseDoesNotExposeTokenInExceptionChain() throws IOException {
        DingTalkAccessTokenProvider liveProvider = loopbackProvider();
        response.set("{\"accessToken\":\"sensitive-token-marker\",broken");
        Throwable failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> liveProvider.token().block(Duration.ofSeconds(5)));
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            assertFalse(String.valueOf(cause.getMessage()).contains("sensitive-token-marker"));
    }

    @Test
    void emptyTokenResponseFailsBeforeSendingMessage() throws IOException {
        DingTalkAccessTokenProvider liveProvider = loopbackProvider();
        response.set("");
        DingTalkOutboundClient client =
                new DingTalkOutboundClient(
                        "http://127.0.0.1:" + server.getAddress().getPort(), liveProvider, "robot");
        StepVerifier.create(
                        client.send(
                                OutboundAddress.direct("dingtalk", "dingtalk:DIRECT:user"),
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .textContent("hello")
                                                .build())))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(10));
        assertEquals(1, requestCount.get());
    }

    private DingTalkAccessTokenProvider loopbackProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        requestCount.incrementAndGet();
                        exchange.getRequestBody().readAllBytes();
                        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
                        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                    }
                });
        server.start();
        return new DingTalkAccessTokenProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-id",
                "test-secret",
                store);
    }

    private static Stream<Arguments> invalidResponses() {
        return Stream.of(
                Arguments.of("", "empty body"),
                Arguments.of("{}", "missing token"),
                Arguments.of("{\"accessToken\":\"\"}", "invalid token "),
                Arguments.of("{\"accessToken\":\" \"}", "invalid token  "),
                Arguments.of("{\"accessToken\":null}", "invalid token null"),
                Arguments.of("{\"accessToken\":123}", "invalid token 123"),
                Arguments.of("{\"accessToken\":true}", "invalid token true"),
                Arguments.of("{\"accessToken\":\"new-token\",\"expireIn\":0}", "invalid expiry 0"),
                Arguments.of(
                        "{\"accessToken\":\"new-token\",\"expireIn\":-1}", "invalid expiry -1"),
                Arguments.of(
                        "{\"accessToken\":\"new-token\",\"expireIn\":\"invalid\"}",
                        "invalid expiry invalid"),
                Arguments.of(
                        "{\"accessToken\":\"new-token\",\"expireIn\":null}", "invalid expiry null"),
                Arguments.of(
                        "{\"accessToken\":\"new-token\",\"expireIn\":0.5}", "invalid expiry 0.5"),
                Arguments.of(
                        "{\"accessToken\":\"new-token\",\"expireIn\":2147483648}",
                        "invalid expiry 2147483648"),
                Arguments.of("[]", "array"),
                Arguments.of("null", "null"),
                Arguments.of(
                        "{\"accessToken\":\"sensitive-token-marker\",broken", "malformed JSON"));
    }

    private static final String UNREACHABLE_API_BASE = "http://localhost:1";

    private final InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
    private final DingTalkAccessTokenProvider provider =
            new DingTalkAccessTokenProvider(UNREACHABLE_API_BASE, "appKey", "appSecret", store);

    @Test
    void servesCachedTokenFromInjectedStore() {
        store.put(new CachedAccessToken("cached-token", System.currentTimeMillis() + 60_000));
        assertEquals("cached-token", provider.token().block(Duration.ofSeconds(2)));
    }

    @Test
    void invalidateDropsCachedToken() {
        store.put(new CachedAccessToken("cached-token", System.currentTimeMillis() + 60_000));
        provider.invalidate();
        assertThrows(RuntimeException.class, () -> provider.token().block(Duration.ofSeconds(2)));
    }

    @Test
    void expiredEntryTriggersRefreshNotStaleToken() {
        store.put(new CachedAccessToken("stale-token", System.currentTimeMillis() - 1_000));
        assertThrows(RuntimeException.class, () -> provider.token().block(Duration.ofSeconds(2)));
    }
}
