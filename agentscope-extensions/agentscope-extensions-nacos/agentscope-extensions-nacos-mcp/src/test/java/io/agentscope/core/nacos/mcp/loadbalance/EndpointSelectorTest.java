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
package io.agentscope.core.nacos.mcp.loadbalance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.nacos.mcp.discovery.NacosMcpEndpoint;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EndpointSelector} implementations.
 */
class EndpointSelectorTest {

    private static NacosMcpEndpoint endpoint(String address, int port) {
        return new NacosMcpEndpoint(address, port, "/mcp", "http");
    }

    @Nested
    @DisplayName("RoundRobinEndpointSelector")
    class RoundRobin {

        @Test
        @DisplayName("Should cycle through endpoints evenly")
        void shouldCycleThroughEndpoints() {
            NacosMcpEndpoint a = endpoint("10.0.0.1", 8080);
            NacosMcpEndpoint b = endpoint("10.0.0.2", 8080);
            NacosMcpEndpoint c = endpoint("10.0.0.3", 8080);
            List<NacosMcpEndpoint> endpoints = List.of(a, b, c);
            EndpointSelector selector = new RoundRobinEndpointSelector();

            assertSame(a, selector.select(endpoints));
            assertSame(b, selector.select(endpoints));
            assertSame(c, selector.select(endpoints));
            // wraps back to the first endpoint
            assertSame(a, selector.select(endpoints));
        }

        @Test
        @DisplayName("Should distribute calls evenly across many selections")
        void shouldDistributeEvenly() {
            List<NacosMcpEndpoint> endpoints =
                    List.of(endpoint("h1", 8080), endpoint("h2", 8080), endpoint("h3", 8080));
            EndpointSelector selector = new RoundRobinEndpointSelector();

            int[] counts = new int[3];
            for (int i = 0; i < 300; i++) {
                NacosMcpEndpoint selected = selector.select(endpoints);
                counts[endpoints.indexOf(selected)]++;
            }
            for (int count : counts) {
                assertEquals(100, count);
            }
        }

        @Test
        @DisplayName("Should always return the only endpoint when there is a single candidate")
        void shouldReturnSingleEndpoint() {
            NacosMcpEndpoint only = endpoint("10.0.0.1", 8080);
            List<NacosMcpEndpoint> endpoints = List.of(only);
            EndpointSelector selector = new RoundRobinEndpointSelector();

            assertSame(only, selector.select(endpoints));
            assertSame(only, selector.select(endpoints));
        }
    }

    @Nested
    @DisplayName("StickyEndpointSelector")
    class Sticky {

        @Test
        @DisplayName("Should keep selecting the same endpoint while it stays available")
        void shouldStickToFirstSelection() {
            NacosMcpEndpoint a = endpoint("10.0.0.1", 8080);
            NacosMcpEndpoint b = endpoint("10.0.0.2", 8080);
            List<NacosMcpEndpoint> endpoints = List.of(a, b);
            EndpointSelector selector = new StickyEndpointSelector();

            NacosMcpEndpoint first = selector.select(endpoints);
            for (int i = 0; i < 5; i++) {
                assertSame(first, selector.select(endpoints));
            }
        }

        @Test
        @DisplayName("Should fail over when the sticky endpoint disappears")
        void shouldFailOverWhenStickyEndpointGone() {
            NacosMcpEndpoint a = endpoint("10.0.0.1", 8080);
            NacosMcpEndpoint b = endpoint("10.0.0.2", 8080);
            EndpointSelector selector = new StickyEndpointSelector();

            // first selection sticks to a
            assertSame(a, selector.select(List.of(a, b)));

            // a scales out, only b remains -> fail over to b and stick to it
            List<NacosMcpEndpoint> remaining = List.of(b);
            assertSame(b, selector.select(remaining));
            assertSame(b, selector.select(remaining));
        }

        @Test
        @DisplayName("Should not distribute like round-robin (consecutive calls are identical)")
        void shouldNotRotate() {
            List<NacosMcpEndpoint> endpoints = List.of(endpoint("h1", 8080), endpoint("h2", 8080));
            EndpointSelector selector = new StickyEndpointSelector();

            NacosMcpEndpoint first = selector.select(endpoints);
            NacosMcpEndpoint second = selector.select(endpoints);
            assertEquals(first.key(), second.key());

            // contrast with round-robin, which rotates
            EndpointSelector roundRobin = new RoundRobinEndpointSelector();
            assertNotEquals(roundRobin.select(endpoints).key(), roundRobin.select(endpoints).key());
        }
    }
}
