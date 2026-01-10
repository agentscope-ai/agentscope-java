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
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Proxy;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {

    @Test
    void testHttpProxyWithoutAuth() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        assertEquals(ProxyType.HTTP, proxy.getType());
        assertEquals("proxy.example.com", proxy.getHost());
        assertEquals(8080, proxy.getPort());
        assertNull(proxy.getUsername());
        assertNull(proxy.getPassword());
        assertFalse(proxy.hasAuthentication());
    }

    @Test
    void testHttpProxyWithAuth() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080, "user", "pass");

        assertEquals(ProxyType.HTTP, proxy.getType());
        assertEquals("proxy.example.com", proxy.getHost());
        assertEquals(8080, proxy.getPort());
        assertEquals("user", proxy.getUsername());
        assertEquals("pass", proxy.getPassword());
        assertTrue(proxy.hasAuthentication());
    }

    @Test
    void testSocks4Proxy() {
        ProxyConfig proxy = ProxyConfig.socks4("socks.example.com", 1080);

        assertEquals(ProxyType.SOCKS4, proxy.getType());
        assertEquals("socks.example.com", proxy.getHost());
        assertEquals(1080, proxy.getPort());
        assertFalse(proxy.hasAuthentication());
    }

    @Test
    void testSocks5ProxyWithoutAuth() {
        ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080);

        assertEquals(ProxyType.SOCKS5, proxy.getType());
        assertEquals("socks.example.com", proxy.getHost());
        assertEquals(1080, proxy.getPort());
        assertFalse(proxy.hasAuthentication());
    }

    @Test
    void testSocks5ProxyWithAuth() {
        ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080, "user", "pass");

        assertEquals(ProxyType.SOCKS5, proxy.getType());
        assertEquals("socks.example.com", proxy.getHost());
        assertEquals(1080, proxy.getPort());
        assertEquals("user", proxy.getUsername());
        assertEquals("pass", proxy.getPassword());
        assertTrue(proxy.hasAuthentication());
    }

    @Test
    void testBuilderWithNonProxyHosts() {
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .nonProxyHosts(Set.of("localhost", "*.internal.com"))
                        .build();

        assertNotNull(proxy.getNonProxyHosts());
        assertEquals(2, proxy.getNonProxyHosts().size());
        assertTrue(proxy.getNonProxyHosts().contains("localhost"));
        assertTrue(proxy.getNonProxyHosts().contains("*.internal.com"));
    }

    @Test
    void testShouldBypassExactMatch() {
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .nonProxyHosts(Set.of("localhost", "127.0.0.1"))
                        .build();

        assertTrue(proxy.shouldBypass("localhost"));
        assertTrue(proxy.shouldBypass("127.0.0.1"));
        assertFalse(proxy.shouldBypass("example.com"));
    }

    @Test
    void testShouldBypassWildcardSuffix() {
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .nonProxyHosts(Set.of("*.internal.com"))
                        .build();

        assertTrue(proxy.shouldBypass("app.internal.com"));
        assertTrue(proxy.shouldBypass("api.internal.com"));
        assertFalse(proxy.shouldBypass("internal.com"));
        assertFalse(proxy.shouldBypass("example.com"));
    }

    @Test
    void testShouldBypassWildcardPrefix() {
        ProxyConfig proxy =
                ProxyConfig.builder()
                        .type(ProxyType.HTTP)
                        .host("proxy.example.com")
                        .port(8080)
                        .nonProxyHosts(Set.of("192.168.*"))
                        .build();

        assertTrue(proxy.shouldBypass("192.168.1.1"));
        assertTrue(proxy.shouldBypass("192.168.0.100"));
        assertFalse(proxy.shouldBypass("10.0.0.1"));
    }

    @Test
    void testShouldBypassWithNullNonProxyHosts() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        assertFalse(proxy.shouldBypass("localhost"));
        assertFalse(proxy.shouldBypass("example.com"));
    }

    @Test
    void testToJavaProxyHttp() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        Proxy javaProxy = proxy.toJavaProxy();

        assertEquals(Proxy.Type.HTTP, javaProxy.type());
        assertNotNull(javaProxy.address());
    }

    @Test
    void testToJavaProxySocks() {
        ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080);

        Proxy javaProxy = proxy.toJavaProxy();

        assertEquals(Proxy.Type.SOCKS, javaProxy.type());
        assertNotNull(javaProxy.address());
    }

    @Test
    void testBuilderRequiresType() {
        assertThrows(
                NullPointerException.class,
                () -> ProxyConfig.builder().host("proxy.example.com").port(8080).build());
    }

    @Test
    void testBuilderRequiresHost() {
        assertThrows(
                NullPointerException.class,
                () -> ProxyConfig.builder().type(ProxyType.HTTP).port(8080).build());
    }

    @Test
    void testBuilderValidatesPort() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProxyConfig.builder()
                                .type(ProxyType.HTTP)
                                .host("proxy.example.com")
                                .port(0)
                                .build());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProxyConfig.builder()
                                .type(ProxyType.HTTP)
                                .host("proxy.example.com")
                                .port(70000)
                                .build());
    }

    @Test
    void testEqualsAndHashCode() {
        ProxyConfig proxy1 = ProxyConfig.http("proxy.example.com", 8080);
        ProxyConfig proxy2 = ProxyConfig.http("proxy.example.com", 8080);
        ProxyConfig proxy3 = ProxyConfig.http("proxy.example.com", 8081);

        assertEquals(proxy1, proxy2);
        assertEquals(proxy1.hashCode(), proxy2.hashCode());
        assertFalse(proxy1.equals(proxy3));
    }

    @Test
    void testToString() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080, "user", "secretpass");

        String str = proxy.toString();

        assertTrue(str.contains("HTTP"));
        assertTrue(str.contains("proxy.example.com"));
        assertTrue(str.contains("8080"));
        assertTrue(str.contains("user"));
        assertTrue(str.contains("****")); // Password should be masked
        assertFalse(str.contains("secretpass")); // Password should not be visible
    }
}
