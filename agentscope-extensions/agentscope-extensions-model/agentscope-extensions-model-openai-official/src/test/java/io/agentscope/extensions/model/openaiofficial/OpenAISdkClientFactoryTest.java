/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.ProxyAuthenticator;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import java.net.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenAISdkClientFactory}.
 *
 * <p>Covers the three key branches: apiKey fail-fast, happy path, and RuntimeException
 * wrapping. The factory is also exercised indirectly by {@link OpenAIOfficialModelProviderTest}.
 */
class OpenAISdkClientFactoryTest {

    @Test
    void blankApiKeyThrowsWithDescriptiveMessage() {
        OpenAIOfficialModelException ex =
                assertThrows(
                        OpenAIOfficialModelException.class,
                        () -> OpenAISdkClientFactory.createClient(null, null, null, null, null));
        assertTrue(ex.getMessage().contains("apiKey is required"));
    }

    @Test
    void validParamsReturnsClient() {
        OpenAIClient client =
                OpenAISdkClientFactory.createClient(
                        "sk-test",
                        "https://custom.example.com",
                        Map.of("X-Request-Id", "abc"),
                        Duration.ofSeconds(30),
                        null);
        assertTrue(client != null);
    }

    @Test
    void sdkRuntimeExceptionWrappedInModelException() {
        // null header value triggers Kotlin null-check in putHeader(name, value)
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Null", null);

        OpenAIOfficialModelException ex =
                assertThrows(
                        OpenAIOfficialModelException.class,
                        () ->
                                OpenAISdkClientFactory.createClient(
                                        "sk-test", null, headers, null, null));
        assertTrue(ex.getMessage().contains("Failed to construct OpenAI client"));
        assertTrue(ex.getCause() != null);
    }

    @Test
    void httpProxyWithAuthenticationIsApplied() {
        OpenAIOkHttpClient.Builder builder = mockBuilder();
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080, "user", "password");

        OpenAISdkClientFactory.applyProxy(builder, proxy, "https://api.openai.com/v1");

        verify(builder).proxy(proxy.toJavaProxy());
        verify(builder).proxyAuthenticator(any(ProxyAuthenticator.class));
    }

    @Test
    void unauthenticatedSocksProxyIsApplied() {
        OpenAIOkHttpClient.Builder builder = mockBuilder();
        ProxyConfig proxy = ProxyConfig.socks5("proxy.example.com", 1080);

        OpenAISdkClientFactory.applyProxy(builder, proxy, "https://api.openai.com/v1");

        verify(builder).proxy(proxy.toJavaProxy());
        verify(builder, never()).proxyAuthenticator(any(ProxyAuthenticator.class));
    }

    @Test
    void nonProxyHostMatchingBaseUrlSkipsProxy() {
        OpenAIOkHttpClient.Builder builder = mockBuilder();
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .nonProxyHosts(Set.of("api.openai.com"))
                        .build();

        OpenAISdkClientFactory.applyProxy(builder, proxy, "https://api.openai.com/v1");

        verify(builder).proxy(Proxy.NO_PROXY);
    }

    @Test
    void nonProxyHostNotMatchingBaseUrlAppliesProxy() {
        OpenAIOkHttpClient.Builder builder = mockBuilder();
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .nonProxyHosts(Set.of("*.internal"))
                        .build();

        OpenAISdkClientFactory.applyProxy(builder, proxy, "https://api.openai.com/v1");

        verify(builder).proxy(proxy.toJavaProxy());
    }

    @Test
    void authenticatedSocksProxyFailsFast() {
        OpenAIOkHttpClient.Builder builder = mockBuilder();
        ProxyConfig proxy = ProxyConfig.socks5("proxy.example.com", 1080, "user", "password");

        OpenAIOfficialModelException ex =
                assertThrows(
                        OpenAIOfficialModelException.class,
                        () ->
                                OpenAISdkClientFactory.applyProxy(
                                        builder, proxy, "https://api.openai.com/v1"));
        assertTrue(ex.getMessage().contains("Authenticated SOCKS"));
    }

    @Test
    void partialProxyCredentialsAreIgnored() {
        OpenAIOkHttpClient.Builder builder = mockBuilder();
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .username("user")
                        .build();

        OpenAISdkClientFactory.applyProxy(builder, proxy, "https://api.openai.com/v1");

        verify(builder).proxy(proxy.toJavaProxy());
        verify(builder, never()).proxyAuthenticator(any(ProxyAuthenticator.class));
    }

    private static OpenAIOkHttpClient.Builder mockBuilder() {
        OpenAIOkHttpClient.Builder builder = mock(OpenAIOkHttpClient.Builder.class);
        when(builder.proxy(any(Proxy.class))).thenReturn(builder);
        when(builder.proxyAuthenticator(any(ProxyAuthenticator.class))).thenReturn(builder);
        return builder;
    }
}
