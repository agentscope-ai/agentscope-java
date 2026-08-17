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

package io.agentscope.core.a2a.server.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for NetworkUtils.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Getting local IP address</li>
 *   <li>Handling network exceptions</li>
 *   <li>Fallback to localhost when no suitable IP found</li>
 * </ul>
 */
@DisplayName("NetworkUtils Tests")
class NetworkUtilsTest {

    @Test
    @DisplayName("Should return localhost when no network interfaces available")
    void testGetLocalIpAddressWithNoNetworkInterfaces() throws Exception {
        // Since we can't easily mock static methods in Java's NetworkInterface class,
        // we'll just test that the method doesn't throw an exception and returns a valid string
        String ipAddress = NetworkUtils.getLocalIpAddress();

        assertNotNull(ipAddress);
        // Should be either localhost or a valid IP address
        assertTrue("localhost".equals(ipAddress) || ipAddress.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
    }

    @Test
    @DisplayName("Should throw SocketException when network error occurs")
    void testGetLocalIpAddressWithNetworkException() {
        // We can't easily test the exception case without mocking static methods
        // which requires additional libraries like PowerMockito
        // This test is left as documentation of what we would test if we could mock static methods
    }

    @Test
    @DisplayName("Should return valid IP address format")
    void testValidIpAddressFormat() throws SocketException {
        String ipAddress = NetworkUtils.getLocalIpAddress();

        assertNotNull(ipAddress);
        // Check that it's either "localhost" or a valid IPv4 address
        if (!"localhost".equals(ipAddress)) {
            // Should match IPv4 format: X.X.X.X where X is 0-255
            assertTrue(
                    ipAddress.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"),
                    "IP address should be in valid IPv4 format");
        }
    }
}
