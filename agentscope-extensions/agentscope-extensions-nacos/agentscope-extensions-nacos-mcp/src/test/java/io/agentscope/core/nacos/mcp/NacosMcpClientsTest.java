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
package io.agentscope.core.nacos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link NacosMcpClients} holder.
 */
class NacosMcpClientsTest {

    private static NacosLoadBalancedMcpClientWrapper client(String name) {
        NacosLoadBalancedMcpClientWrapper client = mock(NacosLoadBalancedMcpClientWrapper.class);
        when(client.getName()).thenReturn(name);
        return client;
    }

    @Test
    @DisplayName("Should treat null list as empty")
    void shouldTreatNullAsEmpty() {
        NacosMcpClients clients = new NacosMcpClients(null);
        assertTrue(clients.isEmpty());
        assertTrue(clients.list().isEmpty());
    }

    @Test
    @DisplayName("Should report empty when no client is configured")
    void shouldBeEmptyWhenNoClient() {
        NacosMcpClients clients = new NacosMcpClients(List.of());
        assertTrue(clients.isEmpty());
        assertNull(clients.get("weather"));
    }

    @Test
    @DisplayName("Should look up a client by name and return null for unknown name")
    void shouldGetClientByName() {
        NacosLoadBalancedMcpClientWrapper weather = client("weather");
        NacosLoadBalancedMcpClientWrapper amap = client("amap");
        NacosMcpClients clients = new NacosMcpClients(List.of(weather, amap));

        assertFalse(clients.isEmpty());
        assertSame(weather, clients.get("weather"));
        assertSame(amap, clients.get("amap"));
        assertNull(clients.get("unknown"));
    }

    @Test
    @DisplayName("Should expose an unmodifiable snapshot of the client list")
    void shouldExposeUnmodifiableList() {
        NacosMcpClients clients = new NacosMcpClients(List.of(client("weather")));
        assertEquals(1, clients.list().size());
        assertThrows(
                UnsupportedOperationException.class, () -> clients.list().add(client("another")));
    }

    @Test
    @DisplayName("Should be iterable over all clients")
    void shouldBeIterable() {
        NacosLoadBalancedMcpClientWrapper weather = client("weather");
        NacosLoadBalancedMcpClientWrapper amap = client("amap");
        NacosMcpClients clients = new NacosMcpClients(List.of(weather, amap));

        int count = 0;
        for (NacosLoadBalancedMcpClientWrapper client : clients) {
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    @DisplayName("Should close every contained client")
    void shouldCloseAllClients() {
        NacosLoadBalancedMcpClientWrapper weather = client("weather");
        NacosLoadBalancedMcpClientWrapper amap = client("amap");
        NacosMcpClients clients = new NacosMcpClients(List.of(weather, amap));

        clients.close();

        verify(weather).close();
        verify(amap).close();
    }

    @Test
    @DisplayName("Should keep closing remaining clients when one close throws")
    void shouldContinueClosingWhenOneThrows() {
        NacosLoadBalancedMcpClientWrapper broken = client("broken");
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(broken).close();
        NacosLoadBalancedMcpClientWrapper ok = client("ok");
        NacosMcpClients clients = new NacosMcpClients(List.of(broken, ok));

        clients.close();

        verify(broken).close();
        verify(ok).close();
    }

    @Test
    @DisplayName("Should reject a null toolkit on registerTo")
    void shouldRejectNullToolkit() {
        NacosMcpClients clients = new NacosMcpClients(List.of(client("weather")));
        assertThrows(IllegalArgumentException.class, () -> clients.registerTo(null));
    }
}
