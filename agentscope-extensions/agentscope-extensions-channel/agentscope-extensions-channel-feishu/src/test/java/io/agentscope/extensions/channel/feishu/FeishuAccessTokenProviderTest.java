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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests token-response validation against a loopback server and cache behavior using an
 * unreachable endpoint. No provider credentials or external services are needed.
 */
class FeishuAccessTokenProviderTest {

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
        FeishuAccessTokenProvider liveProvider = loopbackProvider();
        CachedAccessToken expired =
                new CachedAccessToken("old-token", System.currentTimeMillis() - 1_000);
        store.put(expired);
        response.set(body);
        assertThrows(
                IllegalStateException.class,
                () -> liveProvider.token().block(Duration.ofSeconds(5)),
                description);
        assertSame(expired, store.get(), "An invalid response must not overwrite the cache");

        response.set("{\"code\":0,\"tenant_access_token\":\"recovered-token\",\"expire\":7200}");
        assertEquals("recovered-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals("recovered-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals(2, requestCount.get(), "The recovered token should be cached");
        assertTrue(store.get().refreshAtMs() > System.currentTimeMillis());
    }

    @Test
    void missingOptionalFieldsRetainDefaultLifetime() throws IOException {
        FeishuAccessTokenProvider liveProvider = loopbackProvider();
        response.set("{\"tenant_access_token\":\"valid-token\"}");
        assertEquals("valid-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals("valid-token", liveProvider.token().block(Duration.ofSeconds(5)));
        assertEquals(1, requestCount.get());
        assertTrue(store.get().refreshAtMs() > System.currentTimeMillis() + 5_000_000);
    }

    @Test
    void malformedResponseDoesNotExposeTokenInExceptionChain() throws IOException {
        FeishuAccessTokenProvider liveProvider = loopbackProvider();
        response.set("{\"tenant_access_token\":\"sensitive-token-marker\",broken");
        Throwable failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> liveProvider.token().block(Duration.ofSeconds(5)));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains("sensitive-token-marker"));
        }
    }

    private FeishuAccessTokenProvider loopbackProvider() throws IOException {
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
        return new FeishuAccessTokenProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-id",
                "test-secret",
                store);
    }

    private static Stream<Arguments> invalidResponses() {
        return Stream.of(
                Arguments.of("", "empty body"),
                Arguments.of("{}", "missing token"),
                Arguments.of("{\"tenant_access_token\":\"\"}", "invalid token "),
                Arguments.of("{\"tenant_access_token\":\" \"}", "invalid token  "),
                Arguments.of("{\"tenant_access_token\":null}", "invalid token null"),
                Arguments.of("{\"tenant_access_token\":123}", "invalid token 123"),
                Arguments.of("{\"tenant_access_token\":true}", "invalid token true"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"expire\":0}", "invalid expiry 0"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"expire\":-1}",
                        "invalid expiry -1"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"expire\":\"invalid\"}",
                        "invalid expiry invalid"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"expire\":null}",
                        "invalid expiry null"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"expire\":0.5}",
                        "invalid expiry 0.5"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"expire\":2147483648}",
                        "invalid expiry 2147483648"),
                Arguments.of("[]", "array"),
                Arguments.of("null", "null"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"code\":\"invalid\"}",
                        "invalid status invalid"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"code\":0.5}",
                        "invalid status 0.5"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"code\":null}",
                        "invalid status null"),
                Arguments.of(
                        "{\"tenant_access_token\":\"new-token\",\"code\":400}",
                        "invalid status 400"),
                Arguments.of(
                        "{\"tenant_access_token\":\"sensitive-token-marker\",broken",
                        "malformed JSON"));
    }

    private static final String UNREACHABLE_API_BASE = "http://localhost:1";

    private final InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
    private final FeishuAccessTokenProvider provider =
            new FeishuAccessTokenProvider(UNREACHABLE_API_BASE, "appId", "appSecret", store);

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
