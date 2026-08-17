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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Network utility class for getting current server IP address and port configuration
 */
public class NetworkUtils {

    /**
     * Get local IP address Prefer non-loopback addresses
     *
     * @return local IP address
     * @throws SocketException network exception
     */
    public static String getLocalIpAddress() throws SocketException {
        Stream<NetworkInterface> networkInterfaces = NetworkInterface.networkInterfaces();

        List<NetworkInterface> nis =
                networkInterfaces
                        .filter(NetworkUtils::isValidIpAddress)
                        .sorted(Comparator.comparing(NetworkInterface::getIndex))
                        .toList();

        for (NetworkInterface networkInterface : nis) {
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();

                // Skip loopback addresses and IPv6 addresses
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                    continue;
                }

                // Prefer IPv4 addresses
                if (address.getAddress().length == 4) {
                    return address.getHostAddress();
                }
            }
        }

        // If no suitable IP is found, return localhost
        return "localhost";
    }

    private static boolean isValidIpAddress(NetworkInterface n) {
        try {
            return n != null && !n.isLoopback() && n.isUp();
        } catch (SocketException e) {
            return false;
        }
    }
}
