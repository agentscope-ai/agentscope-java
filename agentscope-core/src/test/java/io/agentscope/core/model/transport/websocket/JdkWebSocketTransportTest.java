/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.WebSocketTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("JdkWebSocketTransport Tests")
class JdkWebSocketTransportTest {

    @Nested
    @DisplayName("Basic Creation Tests")
    class BasicCreationTests {

        @Test
        @DisplayName("Should create client with default config")
        void shouldCreateClientWithDefaultConfig() {
            WebSocketTransport client = JdkWebSocketTransport.create();

            assertNotNull(client);
        }

        @Test
        @DisplayName("Should create client with custom HttpClient")
        void shouldCreateClientWithCustomHttpClient() {
            HttpClient httpClient =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();

            WebSocketTransport client = JdkWebSocketTransport.create(httpClient);

            assertNotNull(client);
        }

        @Test
        @DisplayName("Should build request with headers")
        void shouldBuildRequestWithHeaders() {
            WebSocketRequest request =
                    WebSocketRequest.builder("wss://example.com")
                            .header("Authorization", "Bearer token")
                            .header("X-Custom", "value")
                            .connectTimeout(Duration.ofSeconds(60))
                            .build();

            assertEquals("wss://example.com", request.getUrl());
            assertEquals("Bearer token", request.getHeaders().get("Authorization"));
            assertEquals(Duration.ofSeconds(60), request.getConnectTimeout());
        }

        @Test
        @DisplayName("Should handle shutdown gracefully")
        void shouldHandleShutdownGracefully() {
            WebSocketTransport client = JdkWebSocketTransport.create();

            // Should not throw
            client.shutdown();
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should create client with basic config")
        void shouldCreateClientWithBasicConfig() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofSeconds(60))
                            .build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with ignoreSsl config")
        void shouldCreateClientWithIgnoreSslConfig() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().ignoreSsl(true).build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with default config values")
        void shouldCreateClientWithDefaultConfigValues() {
            WebSocketTransportConfig config = WebSocketTransportConfig.defaults();

            assertEquals(Duration.ofSeconds(30), config.getConnectTimeout());
            assertEquals(Duration.ZERO, config.getReadTimeout());
            assertEquals(Duration.ofSeconds(30), config.getWriteTimeout());
            assertEquals(Duration.ofSeconds(30), config.getPingInterval());
            assertFalse(config.isIgnoreSsl());
        }

        @Test
        @DisplayName("Should create client with all config values set")
        void shouldCreateClientWithAllConfigValuesSet() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofSeconds(60))
                            .readTimeout(Duration.ofSeconds(30))
                            .writeTimeout(Duration.ofSeconds(45))
                            .pingInterval(Duration.ofSeconds(20))
                            .ignoreSsl(true)
                            .build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
            assertEquals(Duration.ofSeconds(60), config.getConnectTimeout());
            assertEquals(Duration.ofSeconds(30), config.getReadTimeout());
            assertEquals(Duration.ofSeconds(45), config.getWriteTimeout());
            assertEquals(Duration.ofSeconds(20), config.getPingInterval());
            assertTrue(config.isIgnoreSsl());
        }
    }

    @Nested
    @DisplayName("Proxy Configuration Tests")
    class ProxyConfigurationTests {

        @Test
        @DisplayName("Should create client with simple HTTP proxy")
        void shouldCreateClientWithSimpleHttpProxy() {
            ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with HTTP proxy with authentication")
        void shouldCreateClientWithHttpProxyWithAuthentication() {
            ProxyConfig proxyConfig =
                    ProxyConfig.http("proxy.example.com", 8080, "user", "password");
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with SOCKS5 proxy")
        void shouldCreateClientWithSocks5Proxy() {
            ProxyConfig proxyConfig = ProxyConfig.socks5("socks.example.com", 1080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with SOCKS4 proxy")
        void shouldCreateClientWithSocks4Proxy() {
            ProxyConfig proxyConfig = ProxyConfig.socks4("socks.example.com", 1080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with proxy and nonProxyHosts")
        void shouldCreateClientWithProxyAndNonProxyHosts() {
            ProxyConfig proxyConfig =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .nonProxyHosts(Set.of("localhost", "*.internal.com"))
                            .build();
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should bypass proxy for matching hosts")
        void shouldBypassProxyForMatchingHosts() {
            ProxyConfig proxyConfig =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .nonProxyHosts(Set.of("localhost", "*.internal.com", "192.168.*"))
                            .build();

            assertTrue(proxyConfig.shouldBypass("localhost"));
            assertTrue(proxyConfig.shouldBypass("api.internal.com"));
            assertTrue(proxyConfig.shouldBypass("192.168.1.1"));
            assertFalse(proxyConfig.shouldBypass("example.com"));
        }

        @Test
        @DisplayName("Should not bypass proxy for non-matching hosts")
        void shouldNotBypassProxyForNonMatchingHosts() {
            ProxyConfig proxyConfig =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .nonProxyHosts(Set.of("localhost"))
                            .build();

            assertFalse(proxyConfig.shouldBypass("example.com"));
            assertFalse(proxyConfig.shouldBypass("api.example.com"));
        }

        @Test
        @DisplayName("Should handle null nonProxyHosts")
        void shouldHandleNullNonProxyHosts() {
            ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);

            assertFalse(proxyConfig.shouldBypass("localhost"));
            assertFalse(proxyConfig.shouldBypass("example.com"));
        }
    }

    @Nested
    @DisplayName("Connection Tests")
    class ConnectionTests {

        @Test
        @DisplayName("Should handle connection to non-existent server")
        void shouldHandleConnectionToNonExistentServer() {
            WebSocketRequest request =
                    WebSocketRequest.builder("ws://localhost:59999/nonexistent")
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();

            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();
            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            StepVerifier.create(transport.connect(request, String.class))
                    .expectError(WebSocketTransportException.class)
                    .verify(Duration.ofSeconds(10));

            transport.shutdown();
        }

        @Test
        @DisplayName("Should handle connection with invalid URL")
        void shouldHandleConnectionWithInvalidUrl() {
            WebSocketRequest request =
                    WebSocketRequest.builder("ws://invalid-host-that-does-not-exist:9999/ws")
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();

            JdkWebSocketTransport transport = JdkWebSocketTransport.create();

            StepVerifier.create(transport.connect(request, String.class))
                    .expectError(WebSocketTransportException.class)
                    .verify(Duration.ofSeconds(30));

            transport.shutdown();
        }

        @Test
        @DisplayName("Should include connect timeout in request")
        void shouldIncludeConnectTimeoutInRequest() {
            Duration customTimeout = Duration.ofSeconds(45);
            WebSocketRequest request =
                    WebSocketRequest.builder("ws://localhost:9999/ws")
                            .connectTimeout(customTimeout)
                            .build();

            assertEquals(customTimeout, request.getConnectTimeout());
        }

        @Test
        @DisplayName("Should include headers in request")
        void shouldIncludeHeadersInRequest() {
            WebSocketRequest request =
                    WebSocketRequest.builder("ws://localhost:9999/ws")
                            .header("Authorization", "Bearer token")
                            .header("X-Custom", "value")
                            .build();

            assertEquals("Bearer token", request.getHeaders().get("Authorization"));
            assertEquals("value", request.getHeaders().get("X-Custom"));
        }
    }

    @Nested
    @DisplayName("Shutdown Tests")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown cleanly with no active connections")
        void shouldShutdownCleanlyWithNoActiveConnections() {
            JdkWebSocketTransport transport = JdkWebSocketTransport.create();

            // Should not throw
            transport.shutdown();
        }

        @Test
        @DisplayName("Should shutdown cleanly with config")
        void shouldShutdownCleanlyWithConfig() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .build();
            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);

            // Should not throw
            transport.shutdown();
        }
    }

    @Nested
    @DisplayName("SSL Configuration Tests")
    class SslConfigurationTests {

        @Test
        @DisplayName("Should create client with SSL verification enabled by default")
        void shouldCreateClientWithSslVerificationEnabledByDefault() {
            WebSocketTransportConfig config = WebSocketTransportConfig.defaults();

            assertFalse(config.isIgnoreSsl());

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);
            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with SSL verification disabled")
        void shouldCreateClientWithSslVerificationDisabled() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().ignoreSsl(true).build();

            assertTrue(config.isIgnoreSsl());

            JdkWebSocketTransport transport = JdkWebSocketTransport.create(config);
            assertNotNull(transport);
        }
    }

    @Nested
    @DisplayName("ProxyConfig Tests")
    class ProxyConfigTests {

        @Test
        @DisplayName("Should detect authentication configured")
        void shouldDetectAuthenticationConfigured() {
            ProxyConfig withAuth = ProxyConfig.http("proxy.example.com", 8080, "user", "password");
            ProxyConfig withoutAuth = ProxyConfig.http("proxy.example.com", 8080);
            ProxyConfig emptyPassword =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .username("user")
                            .password("")
                            .build();
            ProxyConfig nullPassword =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .username("user")
                            .build();

            assertTrue(withAuth.hasAuthentication());
            assertFalse(withoutAuth.hasAuthentication());
            assertFalse(emptyPassword.hasAuthentication());
            assertFalse(nullPassword.hasAuthentication());
        }

        @Test
        @DisplayName("Should get proxy configuration details")
        void shouldGetProxyConfigurationDetails() {
            ProxyConfig proxyConfig =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .username("user")
                            .password("password")
                            .nonProxyHosts(Set.of("localhost"))
                            .build();

            assertEquals(ProxyType.HTTP, proxyConfig.getType());
            assertEquals("proxy.example.com", proxyConfig.getHost());
            assertEquals(8080, proxyConfig.getPort());
            assertEquals("user", proxyConfig.getUsername());
            assertEquals("password", proxyConfig.getPassword());
            assertNotNull(proxyConfig.getNonProxyHosts());
            assertTrue(proxyConfig.getNonProxyHosts().contains("localhost"));
        }

        @Test
        @DisplayName("Should get socket address from proxy config")
        void shouldGetSocketAddressFromProxyConfig() {
            ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);

            java.net.InetSocketAddress socketAddress = proxyConfig.getSocketAddress();

            assertEquals("proxy.example.com", socketAddress.getHostString());
            assertEquals(8080, socketAddress.getPort());
        }

        @Test
        @DisplayName("Should convert to string correctly")
        void shouldConvertToStringCorrectly() {
            ProxyConfig proxyConfig =
                    ProxyConfig.http("proxy.example.com", 8080, "user", "password");

            String str = proxyConfig.toString();

            assertTrue(str.contains("proxy.example.com"));
            assertTrue(str.contains("8080"));
            assertTrue(str.contains("user"));
            assertTrue(str.contains("****")); // Password should be masked
        }

        @Test
        @DisplayName("Should check equality correctly")
        void shouldCheckEqualityCorrectly() {
            ProxyConfig config1 = ProxyConfig.http("proxy.example.com", 8080);
            ProxyConfig config2 = ProxyConfig.http("proxy.example.com", 8080);
            ProxyConfig config3 = ProxyConfig.http("proxy.example.com", 8081);

            assertEquals(config1, config2);
            assertEquals(config1.hashCode(), config2.hashCode());
            assertFalse(config1.equals(config3));
        }
    }
}
