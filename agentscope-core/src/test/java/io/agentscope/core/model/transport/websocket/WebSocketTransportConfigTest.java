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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WebSocketTransportConfig Tests")
class WebSocketTransportConfigTest {

    @Nested
    @DisplayName("Default Configuration Tests")
    class DefaultConfigurationTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaultValues() {
            WebSocketTransportConfig config = WebSocketTransportConfig.defaults();

            assertEquals(Duration.ofSeconds(30), config.getConnectTimeout());
            assertEquals(Duration.ZERO, config.getReadTimeout());
            assertEquals(Duration.ofSeconds(30), config.getWriteTimeout());
            assertEquals(Duration.ofSeconds(30), config.getPingInterval());
            assertNull(config.getProxyConfig());
            assertFalse(config.isIgnoreSsl());
        }

        @Test
        @DisplayName("Should have correct default constant values")
        void shouldHaveCorrectDefaultConstantValues() {
            assertEquals(Duration.ofSeconds(30), WebSocketTransportConfig.DEFAULT_CONNECT_TIMEOUT);
            assertEquals(Duration.ZERO, WebSocketTransportConfig.DEFAULT_READ_TIMEOUT);
            assertEquals(Duration.ofSeconds(30), WebSocketTransportConfig.DEFAULT_WRITE_TIMEOUT);
            assertEquals(Duration.ofSeconds(30), WebSocketTransportConfig.DEFAULT_PING_INTERVAL);
        }

        @Test
        @DisplayName("Builder should use defaults when not specified")
        void builderShouldUseDefaultsWhenNotSpecified() {
            WebSocketTransportConfig config = WebSocketTransportConfig.builder().build();

            assertEquals(
                    WebSocketTransportConfig.DEFAULT_CONNECT_TIMEOUT, config.getConnectTimeout());
            assertEquals(WebSocketTransportConfig.DEFAULT_READ_TIMEOUT, config.getReadTimeout());
            assertEquals(WebSocketTransportConfig.DEFAULT_WRITE_TIMEOUT, config.getWriteTimeout());
            assertEquals(WebSocketTransportConfig.DEFAULT_PING_INTERVAL, config.getPingInterval());
            assertFalse(config.isIgnoreSsl());
            assertNull(config.getProxyConfig());
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build with custom values")
        void shouldBuildWithCustomValues() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofSeconds(60))
                            .readTimeout(Duration.ofSeconds(120))
                            .writeTimeout(Duration.ofSeconds(45))
                            .pingInterval(Duration.ofSeconds(15))
                            .ignoreSsl(true)
                            .build();

            assertEquals(Duration.ofSeconds(60), config.getConnectTimeout());
            assertEquals(Duration.ofSeconds(120), config.getReadTimeout());
            assertEquals(Duration.ofSeconds(45), config.getWriteTimeout());
            assertEquals(Duration.ofSeconds(15), config.getPingInterval());
            assertTrue(config.isIgnoreSsl());
        }

        @Test
        @DisplayName("Should support builder chaining")
        void shouldSupportBuilderChaining() {
            WebSocketTransportConfig.Builder builder = WebSocketTransportConfig.builder();

            // Verify each method returns the same builder instance
            assertSame(builder, builder.connectTimeout(Duration.ofSeconds(10)));
            assertSame(builder, builder.readTimeout(Duration.ofSeconds(20)));
            assertSame(builder, builder.writeTimeout(Duration.ofSeconds(30)));
            assertSame(builder, builder.pingInterval(Duration.ofSeconds(40)));
            assertSame(builder, builder.ignoreSsl(true));
            assertSame(builder, builder.proxy(null));
        }

        @Test
        @DisplayName("Should build with proxy config")
        void shouldBuildWithProxyConfig() {
            ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);

            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            assertNotNull(config.getProxyConfig());
            assertEquals("proxy.example.com", config.getProxyConfig().getHost());
            assertEquals(8080, config.getProxyConfig().getPort());
        }

        @Test
        @DisplayName("Should build with proxy and authentication")
        void shouldBuildWithProxyAndAuthentication() {
            ProxyConfig proxyConfig = ProxyConfig.socks5("socks.example.com", 1080, "user", "pass");

            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            assertNotNull(config.getProxyConfig());
            assertEquals("socks.example.com", config.getProxyConfig().getHost());
            assertEquals(1080, config.getProxyConfig().getPort());
            assertTrue(config.getProxyConfig().hasAuthentication());
        }

        @Test
        @DisplayName("Should build with null proxy")
        void shouldBuildWithNullProxy() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(null).build();

            assertNull(config.getProxyConfig());
        }

        @Test
        @DisplayName("Should build with ignoreSsl false by default")
        void shouldBuildWithIgnoreSslFalseByDefault() {
            WebSocketTransportConfig config = WebSocketTransportConfig.builder().build();

            assertFalse(config.isIgnoreSsl());
        }

        @Test
        @DisplayName("Should override ignoreSsl when set to true")
        void shouldOverrideIgnoreSslWhenSetToTrue() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().ignoreSsl(true).build();

            assertTrue(config.isIgnoreSsl());
        }

        @Test
        @DisplayName("Should allow setting ignoreSsl back to false")
        void shouldAllowSettingIgnoreSslBackToFalse() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().ignoreSsl(true).ignoreSsl(false).build();

            assertFalse(config.isIgnoreSsl());
        }
    }

    @Nested
    @DisplayName("Proxy Configuration Tests")
    class ProxyConfigurationTests {

        @Test
        @DisplayName("Should support HTTP proxy")
        void shouldSupportHttpProxy() {
            ProxyConfig proxyConfig = ProxyConfig.http("proxy.example.com", 8080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            assertNotNull(config.getProxyConfig());
            assertEquals(ProxyType.HTTP, config.getProxyConfig().getType());
        }

        @Test
        @DisplayName("Should support SOCKS4 proxy")
        void shouldSupportSocks4Proxy() {
            ProxyConfig proxyConfig = ProxyConfig.socks4("socks.example.com", 1080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            assertNotNull(config.getProxyConfig());
            assertEquals(ProxyType.SOCKS4, config.getProxyConfig().getType());
        }

        @Test
        @DisplayName("Should support SOCKS5 proxy")
        void shouldSupportSocks5Proxy() {
            ProxyConfig proxyConfig = ProxyConfig.socks5("socks.example.com", 1080);
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            assertNotNull(config.getProxyConfig());
            assertEquals(ProxyType.SOCKS5, config.getProxyConfig().getType());
        }

        @Test
        @DisplayName("Should support proxy with nonProxyHosts")
        void shouldSupportProxyWithNonProxyHosts() {
            ProxyConfig proxyConfig =
                    ProxyConfig.builder()
                            .type(ProxyType.HTTP)
                            .host("proxy.example.com")
                            .port(8080)
                            .nonProxyHosts(Set.of("localhost", "*.internal.com"))
                            .build();
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder().proxy(proxyConfig).build();

            assertNotNull(config.getProxyConfig());
            assertNotNull(config.getProxyConfig().getNonProxyHosts());
            assertTrue(config.getProxyConfig().getNonProxyHosts().contains("localhost"));
            assertTrue(config.getProxyConfig().getNonProxyHosts().contains("*.internal.com"));
        }
    }

    @Nested
    @DisplayName("Timeout Configuration Tests")
    class TimeoutConfigurationTests {

        @Test
        @DisplayName("Should support zero timeout")
        void shouldSupportZeroTimeout() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ZERO)
                            .readTimeout(Duration.ZERO)
                            .writeTimeout(Duration.ZERO)
                            .pingInterval(Duration.ZERO)
                            .build();

            assertEquals(Duration.ZERO, config.getConnectTimeout());
            assertEquals(Duration.ZERO, config.getReadTimeout());
            assertEquals(Duration.ZERO, config.getWriteTimeout());
            assertEquals(Duration.ZERO, config.getPingInterval());
        }

        @Test
        @DisplayName("Should support large timeout values")
        void shouldSupportLargeTimeoutValues() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofMinutes(10))
                            .readTimeout(Duration.ofHours(1))
                            .writeTimeout(Duration.ofMinutes(5))
                            .pingInterval(Duration.ofMinutes(2))
                            .build();

            assertEquals(Duration.ofMinutes(10), config.getConnectTimeout());
            assertEquals(Duration.ofHours(1), config.getReadTimeout());
            assertEquals(Duration.ofMinutes(5), config.getWriteTimeout());
            assertEquals(Duration.ofMinutes(2), config.getPingInterval());
        }

        @Test
        @DisplayName("Should support millisecond precision")
        void shouldSupportMillisecondPrecision() {
            WebSocketTransportConfig config =
                    WebSocketTransportConfig.builder()
                            .connectTimeout(Duration.ofMillis(1500))
                            .readTimeout(Duration.ofMillis(2500))
                            .writeTimeout(Duration.ofMillis(3500))
                            .pingInterval(Duration.ofMillis(4500))
                            .build();

            assertEquals(Duration.ofMillis(1500), config.getConnectTimeout());
            assertEquals(Duration.ofMillis(2500), config.getReadTimeout());
            assertEquals(Duration.ofMillis(3500), config.getWriteTimeout());
            assertEquals(Duration.ofMillis(4500), config.getPingInterval());
        }
    }
}
