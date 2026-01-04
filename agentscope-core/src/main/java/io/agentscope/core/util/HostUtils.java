package io.agentscope.core.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HostUtils {

    /**
     * Returns the hostname of the machine.
     * <p>
     * The method tries to get the hostname in the following order:
     * <ol>
     *   <li>Read from /etc/hostname file</li>
     *   <li>Execute the hostname command</li>
     *   <li>Use InetAddress.getLocalHost().getHostName()</li>
     * </ol>
     *
     * @return the hostname of the machine, or "localhost" if all methods fail
     */
    public static String getHostname() {
        // 1. Try to read from /etc/hostname
        String hostname = getHostnameFromFile();
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        }

        // 2. Try to execute hostname command
        hostname = getHostnameFromCommand();
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        }

        // 3. Fallback to generic Java method
        hostname = getHostnameFromInetAddress();
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        }

        // 4. read from property: host.name
        hostname = System.getProperty("host.name");
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        }

        // 5. return localhost
        return "localhost";
    }

    /**
     * Reads the hostname from /etc/hostname file.
     *
     * @return the hostname, or null if the file doesn't exist or cannot be read
     */
    private static String getHostnameFromFile() {
        try {
            Path hostnameFile = Paths.get("/etc/hostname");
            if (Files.exists(hostnameFile) && Files.isReadable(hostnameFile)) {
                String hostname = Files.readString(hostnameFile);
                if (hostname != null) {
                    return hostname.trim();
                }
            }
        } catch (Exception e) {
            // Ignore and try next method
        }
        return null;
    }

    /**
     * Gets the hostname by executing the hostname command.
     *
     * @return the hostname, or null if the command fails
     */
    private static String getHostnameFromCommand() {
        try {
            Process process = Runtime.getRuntime().exec("hostname");
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String hostname = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && hostname != null) {
                    return hostname.trim();
                }
            }
        } catch (Exception e) {
            // Ignore and try next method
        }
        return null;
    }

    /**
     * Gets the hostname using InetAddress.
     *
     * @return the hostname, or null if the method fails
     */
    private static String getHostnameFromInetAddress() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Returns the first non-loop local IP address of the machine.
     *
     * @return the local IP address or empty string if an error occurs
     */
    public static String getLocalIpAddressOrEmpty() {
        try {
            return getLocalIpAddress();
        } catch (SocketException e) {
            return "";
        }
    }

    /**
     * Returns the first non-loop local IP address of the machine.
     *
     * @return the local IP address
     * @throws SocketException if an error occurs while retrieving the network interfaces
     */
    public static String getLocalIpAddress() throws SocketException {
        Stream<NetworkInterface> networkInterfaces = NetworkInterface.networkInterfaces();

        List<NetworkInterface> nis =
                networkInterfaces
                        .filter(HostUtils::isValidIpAddress)
                        .sorted(Comparator.comparing((n) -> n.getIndex()))
                        .collect(Collectors.toList());

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
