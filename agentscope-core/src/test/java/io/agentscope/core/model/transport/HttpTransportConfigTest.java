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
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests for {@link HttpTransportConfig} and its builder. */
@Tag("unit")
class HttpTransportConfigTest {

    @Test
    void defaultsExposeDocumentedValues() {
        HttpTransportConfig config = HttpTransportConfig.defaults();

        assertEquals(HttpTransportConfig.DEFAULT_CONNECT_TIMEOUT, config.getConnectTimeout());
        assertEquals(HttpTransportConfig.DEFAULT_RESPONSE_TIMEOUT, config.getResponseTimeout());
        assertEquals(
                HttpTransportConfig.DEFAULT_STREAM_IDLE_TIMEOUT, config.getStreamIdleTimeout());
        assertEquals(HttpTransportConfig.DEFAULT_READ_TIMEOUT, config.getReadTimeout());
        assertEquals(HttpTransportConfig.DEFAULT_WRITE_TIMEOUT, config.getWriteTimeout());
        assertEquals(5, config.getMaxIdleConnections());
        assertEquals(Duration.ofMinutes(5), config.getKeepAliveDuration());
        assertFalse(config.isIgnoreSsl());
        assertNull(config.getProxyConfig());
        assertNull(config.getHttpVersion());
        assertTrue(config.isLogRequests());
        assertTrue(config.isLogFailures());
        assertFalse(config.isLogBodies());
        assertEquals(HttpTransportConfig.DEFAULT_LOG_BODY_MAX_LENGTH, config.getLogBodyMaxLength());
        assertEquals(List.of(), config.getListeners());
    }

    @Test
    void builderRoundTripSetsAllOptions() {
        ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);

        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .responseTimeout(Duration.ofSeconds(2))
                        .streamIdleTimeout(Duration.ofSeconds(3))
                        .readTimeout(Duration.ofSeconds(4))
                        .writeTimeout(Duration.ofSeconds(5))
                        .maxIdleConnections(7)
                        .keepAliveDuration(Duration.ofSeconds(8))
                        .ignoreSsl(true)
                        .proxy(proxy)
                        .httpVersion(HttpVersion.HTTP_2)
                        .logRequests(false)
                        .logFailures(false)
                        .logBodies(true)
                        .logBodyMaxLength(128)
                        .build();

        assertEquals(Duration.ofSeconds(1), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(2), config.getResponseTimeout());
        assertEquals(Duration.ofSeconds(3), config.getStreamIdleTimeout());
        assertEquals(Duration.ofSeconds(4), config.getReadTimeout());
        assertEquals(Duration.ofSeconds(5), config.getWriteTimeout());
        assertEquals(7, config.getMaxIdleConnections());
        assertEquals(Duration.ofSeconds(8), config.getKeepAliveDuration());
        assertTrue(config.isIgnoreSsl());
        assertEquals(proxy, config.getProxyConfig());
        assertEquals(HttpVersion.HTTP_2, config.getHttpVersion());
        assertFalse(config.isLogRequests());
        assertFalse(config.isLogFailures());
        assertTrue(config.isLogBodies());
        assertEquals(128, config.getLogBodyMaxLength());
    }

    @Test
    void listenersAppendAcrossCallsAndTolerateVarargsEdgeCases() {
        HttpTransportListener first = new HttpTransportListener() {};
        HttpTransportListener second = new HttpTransportListener() {};
        HttpTransportListener third = new HttpTransportListener() {};

        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .listeners(first)
                        .listeners(second, (HttpTransportListener[]) null)
                        .listeners(third, second)
                        .build();

        assertEquals(List.of(first, second, third, second), config.getListeners());
    }

    @Test
    void builderRejectsNullListener() {
        assertThrows(
                NullPointerException.class,
                () -> HttpTransportConfig.builder().listeners((HttpTransportListener) null));
    }

    @Test
    void builderRejectsNullElementInVarargs() {
        HttpTransportListener first = new HttpTransportListener() {};
        assertThrows(
                NullPointerException.class,
                () -> HttpTransportConfig.builder().listeners(first, (HttpTransportListener) null));
    }
}
