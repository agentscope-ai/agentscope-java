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
package io.agentscope.extensions.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Pins the provider-endpoint allowlist that runs before iLink credentials are sent. */
class WeixinEndpointPolicyTest {

    @Test
    void appliesHttpsAndStripsTrailingSlashes() {
        assertEquals(
                "https://ilinkai.weixin.qq.com",
                WeixinEndpointPolicy.normalizeBaseUrl("ilinkai.weixin.qq.com"));
        assertEquals(
                "https://ilinkai.weixin.qq.com",
                WeixinEndpointPolicy.normalizeBaseUrl(" https://ilinkai.weixin.qq.com/// "));
    }

    @Test
    void rejectsBlankBaseUrl() {
        assertThrows(
                IllegalArgumentException.class, () -> WeixinEndpointPolicy.normalizeBaseUrl(null));
        assertThrows(
                IllegalArgumentException.class, () -> WeixinEndpointPolicy.normalizeBaseUrl("   "));
    }

    @Test
    void rejectsCredentialsQueryFragmentAndPath() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WeixinEndpointPolicy.normalizeBaseUrl(
                                "https://user:pass@ilinkai.weixin.qq.com"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WeixinEndpointPolicy.normalizeBaseUrl(
                                "https://ilinkai.weixin.qq.com?debug=1"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WeixinEndpointPolicy.normalizeBaseUrl(
                                "https://ilinkai.weixin.qq.com#fragment"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WeixinEndpointPolicy.normalizeBaseUrl("https://ilinkai.weixin.qq.com/ilink"));
    }

    @Test
    void rejectsHostsOutsideTheOfficialProviderAndLoopback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WeixinEndpointPolicy.normalizeBaseUrl("https://evil.example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WeixinEndpointPolicy.normalizeBaseUrl(
                                "https://ilinkai.weixin.qq.com.evil.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WeixinEndpointPolicy.normalizeBaseUrl("https://evilweixin.qq.com"));
    }

    @Test
    void rejectsPlaintextHttpOutsideLoopback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WeixinEndpointPolicy.normalizeBaseUrl("http://ilinkai.weixin.qq.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WeixinEndpointPolicy.normalizeBaseUrl("http://weixin.qq.com"));
    }

    @Test
    void rejectsOfficialHostsOnNonTlsPorts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WeixinEndpointPolicy.normalizeBaseUrl("https://ilinkai.weixin.qq.com:8443"));
    }

    @Test
    void acceptsOfficialSubdomainsAndLoopback() {
        assertEquals(
                "https://channel.weixin.qq.com",
                WeixinEndpointPolicy.normalizeBaseUrl("https://channel.weixin.qq.com"));
        assertEquals(
                "http://127.0.0.1:8089",
                WeixinEndpointPolicy.normalizeBaseUrl("http://127.0.0.1:8089/"));
        assertEquals(
                "https://localhost:8089", WeixinEndpointPolicy.normalizeBaseUrl("localhost:8089"));
    }

    @Test
    void allowsRedirectsWithinTheOfficialProvider() {
        assertEquals(
                "https://weixin.qq.com",
                WeixinEndpointPolicy.validateProviderEndpoint(
                        "https://ilinkai.weixin.qq.com", "https://weixin.qq.com"));
    }

    @Test
    void allowsRedirectsBetweenLoopbackEndpoints() {
        assertEquals(
                "http://127.0.0.1:9999",
                WeixinEndpointPolicy.validateProviderEndpoint(
                        "http://127.0.0.1:8089", "http://127.0.0.1:9999"));
    }

    @Test
    void rejectsRedirectFromLoopbackToTheOfficialProvider() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        WeixinEndpointPolicy.validateProviderEndpoint(
                                "http://127.0.0.1:8089", "https://ilinkai.weixin.qq.com"));
    }

    @Test
    void rejectsRedirectToAnUntrustedHost() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WeixinEndpointPolicy.validateProviderEndpoint(
                                "https://ilinkai.weixin.qq.com", "https://evil.example.com"));
    }
}
