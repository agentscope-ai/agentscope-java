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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.WebSocketTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("OkHttpWebSocketTransport Tests")
class OkHttpWebSocketTransportTest {

    @Nested
    @DisplayName("Basic Creation Tests")
    class BasicCreationTests {

        @Test
        @DisplayName("Should create client with default config")
        void shouldCreateClientWithDefaultConfig() {
            WebSocketTransport client = OkHttpWebSocketTransport.create();

            assertNotNull(client);
        }

        @Test
        @DisplayName("Should create client with custom OkHttpClient")
        void shouldCreateClientWithCustomOkHttpClient() {
            OkHttpClient okHttpClient =
                    new OkHttpClient.Builder()
                            .connectTimeout(60, TimeUnit.SECONDS)
                            .pingInterval(15, TimeUnit.SECONDS)
                            .build();

            WebSocketTransport client = OkHttpWebSocketTransport.create(okHttpClient);

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
            assertEquals("value", request.getHeaders().get("X-Custom"));
            assertEquals(Duration.ofSeconds(60), request.getConnectTimeout());
        }

        @Test
        @DisplayName("Should handle shutdown gracefully")
        void shouldHandleShutdownGracefully() {
            WebSocketTransport client = OkHttpWebSocketTransport.create();

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
                            .readTimeout(Duration.ofSeconds(30))
                            .writeTimeout(Duration.ofSeconds(45))
                            .pingInterval(Duration.ofSeconds(20))
                            .build();

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with ignoreSsl config")
        void shouldCreateClientWithIgnoreSslConfig() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().ignoreSsl(true).build();

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with default timeout values")
        void shouldCreateClientWithDefaultTimeoutValues() {
            WebSocketTransportConfig config = WebSocketTransportConfig.defaults();

            assertEquals(Duration.ofSeconds(30), config.getConnectTimeout());
            assertEquals(Duration.ZERO, config.getReadTimeout());
            assertEquals(Duration.ofSeconds(30), config.getWriteTimeout());
            assertEquals(Duration.ofSeconds(30), config.getPingInterval());
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

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with HTTP proxy with authentication")
        void shouldCreateClientWithHttpProxyWithAuthentication() {
            ProxyConfig proxyConfig =
                    ProxyConfig.http("proxy.example.com", 8080, "user", "password");
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with SOCKS5 proxy")
        void shouldCreateClientWithSocks5Proxy() {
            ProxyConfig proxyConfig = ProxyConfig.socks5("socks.example.com", 1080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

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

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

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
        }
    }

    @Nested
    @DisplayName("Connection Tests")
    class ConnectionTests {

        @Test
        @DisplayName("Should connect to WebSocket server successfully")
        void shouldConnectToWebSocketServerSuccessfully() throws IOException {
            MockWebServer server = new MockWebServer();
            server.enqueue(
                    new MockResponse()
                            .withWebSocketUpgrade(
                                    new WebSocketListener() {
                                        @Override
                                        public void onOpen(WebSocket webSocket, Response response) {
                                            // Connection opened
                                        }
                                    }));
            server.start();

            try {
                String wsUrl = server.url("/ws").toString().replace("http://", "ws://");
                WebSocketRequest request = WebSocketRequest.builder(wsUrl).build();
                OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create();

                StepVerifier.create(transport.connect(request, String.class))
                        .assertNext(
                                connection -> {
                                    assertNotNull(connection);
                                    assertTrue(connection.isOpen());
                                })
                        .verifyComplete();

                transport.shutdown();
            } finally {
                server.shutdown();
            }
        }

        @Test
        @DisplayName("Should include headers in request")
        void shouldIncludeHeadersInRequest() throws IOException, InterruptedException {
            MockWebServer server = new MockWebServer();
            server.enqueue(
                    new MockResponse()
                            .withWebSocketUpgrade(
                                    new WebSocketListener() {
                                        @Override
                                        public void onOpen(WebSocket webSocket, Response response) {
                                            // Connection opened
                                        }
                                    }));
            server.start();

            try {
                String wsUrl = server.url("/ws").toString().replace("http://", "ws://");
                WebSocketRequest request =
                        WebSocketRequest.builder(wsUrl)
                                .header("Authorization", "Bearer test-token")
                                .header("X-Custom-Header", "custom-value")
                                .build();
                OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create();

                StepVerifier.create(transport.connect(request, String.class))
                        .assertNext(
                                connection -> {
                                    assertNotNull(connection);
                                })
                        .verifyComplete();

                // Verify headers were sent
                var recordedRequest = server.takeRequest();
                assertEquals("Bearer test-token", recordedRequest.getHeader("Authorization"));
                assertEquals("custom-value", recordedRequest.getHeader("X-Custom-Header"));

                transport.shutdown();
            } finally {
                server.shutdown();
            }
        }

        @Test
        @DisplayName("Should handle connection failure")
        void shouldHandleConnectionFailure() throws IOException {
            MockWebServer server = new MockWebServer();
            server.enqueue(new MockResponse().setResponseCode(404));
            server.start();

            try {
                String wsUrl = server.url("/ws").toString().replace("http://", "ws://");
                WebSocketRequest request = WebSocketRequest.builder(wsUrl).build();
                OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create();

                StepVerifier.create(transport.connect(request, String.class))
                        .expectError(WebSocketTransportException.class)
                        .verify(Duration.ofSeconds(10));

                transport.shutdown();
            } finally {
                server.shutdown();
            }
        }

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
            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);

            StepVerifier.create(transport.connect(request, String.class))
                    .expectError(WebSocketTransportException.class)
                    .verify(Duration.ofSeconds(10));

            transport.shutdown();
        }
    }

    @Nested
    @DisplayName("Send and Receive Tests")
    class SendAndReceiveTests {

        @Test
        @DisplayName("Should send and receive text messages")
        void shouldSendAndReceiveTextMessages() throws IOException {
            MockWebServer server = new MockWebServer();
            server.enqueue(
                    new MockResponse()
                            .withWebSocketUpgrade(
                                    new WebSocketListener() {
                                        @Override
                                        public void onOpen(WebSocket webSocket, Response response) {
                                            // Echo server
                                        }

                                        @Override
                                        public void onMessage(WebSocket webSocket, String text) {
                                            webSocket.send("Echo: " + text);
                                        }
                                    }));
            server.start();

            try {
                String wsUrl = server.url("/ws").toString().replace("http://", "ws://");
                WebSocketRequest request = WebSocketRequest.builder(wsUrl).build();
                OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create();

                StepVerifier.create(
                                transport
                                        .connect(request, String.class)
                                        .flatMapMany(
                                                connection -> {
                                                    connection.send("Hello").subscribe();
                                                    return connection.receive().take(1);
                                                }))
                        .assertNext(
                                message -> {
                                    assertEquals("Echo: Hello", message);
                                })
                        .verifyComplete();

                transport.shutdown();
            } finally {
                server.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("Shutdown Tests")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown cleanly with no active connections")
        void shouldShutdownCleanlyWithNoActiveConnections() {
            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create();

            // Should not throw
            transport.shutdown();
        }

        @Test
        @DisplayName("Should shutdown after connection was established")
        void shouldShutdownAfterConnectionWasEstablished() throws IOException {
            MockWebServer server = new MockWebServer();
            server.enqueue(
                    new MockResponse()
                            .withWebSocketUpgrade(
                                    new WebSocketListener() {
                                        @Override
                                        public void onOpen(WebSocket webSocket, Response response) {
                                            // Connection opened
                                        }
                                    }));
            server.start();

            try {
                String wsUrl = server.url("/ws").toString().replace("http://", "ws://");
                WebSocketRequest request = WebSocketRequest.builder(wsUrl).build();
                OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create();

                WebSocketConnection<String> connection =
                        transport.connect(request, String.class).block(Duration.ofSeconds(5));
                assertNotNull(connection);

                // Should not throw
                transport.shutdown();
            } finally {
                server.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("SSL Configuration Tests")
    class SslConfigurationTests {

        @Test
        @DisplayName("Should create client with SSL verification enabled by default")
        void shouldCreateClientWithSslVerificationEnabledByDefault() {
            WebSocketTransportConfig config = WebSocketTransportConfig.defaults();

            assertEquals(false, config.isIgnoreSsl());

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);
            assertNotNull(transport);
        }

        @Test
        @DisplayName("Should create client with SSL verification disabled")
        void shouldCreateClientWithSslVerificationDisabled() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().ignoreSsl(true).build();

            assertEquals(true, config.isIgnoreSsl());

            OkHttpWebSocketTransport transport = OkHttpWebSocketTransport.create(config);
            assertNotNull(transport);
        }
    }

    @Nested
    @DisplayName("ProxyConfig Integration Tests")
    class ProxyConfigIntegrationTests {

        @Test
        @DisplayName("Should create Java Proxy from HTTP ProxyConfig")
        void shouldCreateJavaProxyFromHttpProxyConfig() {
            ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);

            java.net.Proxy javaProxy = proxyConfig.toJavaProxy();

            assertEquals(java.net.Proxy.Type.HTTP, javaProxy.type());
            InetSocketAddress address = (InetSocketAddress) javaProxy.address();
            assertEquals("proxy.example.com", address.getHostString());
            assertEquals(8080, address.getPort());
        }

        @Test
        @DisplayName("Should create Java Proxy from SOCKS5 ProxyConfig")
        void shouldCreateJavaProxyFromSocks5ProxyConfig() {
            ProxyConfig proxyConfig = ProxyConfig.socks5("socks.example.com", 1080);

            java.net.Proxy javaProxy = proxyConfig.toJavaProxy();

            assertEquals(java.net.Proxy.Type.SOCKS, javaProxy.type());
            InetSocketAddress address = (InetSocketAddress) javaProxy.address();
            assertEquals("socks.example.com", address.getHostString());
            assertEquals(1080, address.getPort());
        }

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

            assertTrue(withAuth.hasAuthentication());
            assertTrue(!withoutAuth.hasAuthentication());
            assertTrue(!emptyPassword.hasAuthentication());
        }
    }
}
