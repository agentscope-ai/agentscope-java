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

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration for HTTP and SOCKS proxy.
 *
 * <p>This class provides a unified configuration for both HTTP and SOCKS proxies,
 * supporting optional authentication and bypass rules.
 *
 * <p>Usage examples:
 *
 * <pre>{@code
 * // Simple HTTP proxy
 * ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);
 *
 * // HTTP proxy with authentication
 * ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080, "user", "pass");
 *
 * // SOCKS5 proxy with authentication
 * ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080, "user", "pass");
 *
 * // Using builder for advanced configuration
 * ProxyConfig proxy = ProxyConfig.builder()
 *     .type(ProxyType.HTTP)
 *     .host("proxy.example.com")
 *     .port(8080)
 *     .username("user")
 *     .password("pass")
 *     .nonProxyHosts(Set.of("localhost", "*.internal.com"))
 *     .build();
 * }</pre>
 */
public class ProxyConfig {

    private final ProxyType type;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final Set<String> nonProxyHosts;

    private ProxyConfig(Builder builder) {
        this.type = builder.type;
        this.host = builder.host;
        this.port = builder.port;
        this.username = builder.username;
        this.password = builder.password;
        this.nonProxyHosts =
                builder.nonProxyHosts != null
                        ? Collections.unmodifiableSet(new HashSet<>(builder.nonProxyHosts))
                        : null;
    }

    /**
     * Create a simple HTTP proxy configuration without authentication.
     *
     * @param host proxy server hostname or IP address
     * @param port proxy server port
     * @return a new ProxyConfig instance
     */
    public static ProxyConfig http(String host, int port) {
        return builder().type(ProxyType.HTTP).host(host).port(port).build();
    }

    /**
     * Create an HTTP proxy configuration with authentication.
     *
     * @param host proxy server hostname or IP address
     * @param port proxy server port
     * @param username authentication username
     * @param password authentication password
     * @return a new ProxyConfig instance
     */
    public static ProxyConfig http(String host, int port, String username, String password) {
        return builder()
                .type(ProxyType.HTTP)
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * Create a simple SOCKS4 proxy configuration.
     *
     * <p>Note: SOCKS4 does not support authentication.
     *
     * @param host proxy server hostname or IP address
     * @param port proxy server port
     * @return a new ProxyConfig instance
     */
    public static ProxyConfig socks4(String host, int port) {
        return builder().type(ProxyType.SOCKS4).host(host).port(port).build();
    }

    /**
     * Create a simple SOCKS5 proxy configuration without authentication.
     *
     * @param host proxy server hostname or IP address
     * @param port proxy server port
     * @return a new ProxyConfig instance
     */
    public static ProxyConfig socks5(String host, int port) {
        return builder().type(ProxyType.SOCKS5).host(host).port(port).build();
    }

    /**
     * Create a SOCKS5 proxy configuration with authentication.
     *
     * <p><b>Note:</b> SOCKS5 authentication is only supported when using OkHttp-based
     * transport implementations. JDK HttpClient does not support SOCKS5 authentication.
     *
     * @param host proxy server hostname or IP address
     * @param port proxy server port
     * @param username authentication username
     * @param password authentication password
     * @return a new ProxyConfig instance
     */
    public static ProxyConfig socks5(String host, int port, String username, String password) {
        return builder()
                .type(ProxyType.SOCKS5)
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * Create a new builder for ProxyConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the proxy type.
     *
     * @return the proxy type
     */
    public ProxyType getType() {
        return type;
    }

    /**
     * Get the proxy server hostname or IP address.
     *
     * @return the proxy host
     */
    public String getHost() {
        return host;
    }

    /**
     * Get the proxy server port.
     *
     * @return the proxy port
     */
    public int getPort() {
        return port;
    }

    /**
     * Get the authentication username.
     *
     * @return the username, or null if no authentication is configured
     */
    public String getUsername() {
        return username;
    }

    /**
     * Get the authentication password.
     *
     * @return the password, or null if no authentication is configured
     */
    public String getPassword() {
        return password;
    }

    /**
     * Get the set of hosts that should bypass the proxy.
     *
     * <p>The set may contain exact hostnames (e.g., "localhost") or wildcard patterns
     * (e.g., "*.internal.com", "192.168.*").
     *
     * @return the set of non-proxy hosts, or null if not configured
     */
    public Set<String> getNonProxyHosts() {
        return nonProxyHosts;
    }

    /**
     * Check if authentication credentials are configured.
     *
     * @return true if both username and password are set
     */
    public boolean hasAuthentication() {
        return username != null && !username.isEmpty() && password != null && !password.isEmpty();
    }

    /**
     * Check if the given hostname should bypass the proxy.
     *
     * @param hostname the hostname to check
     * @return true if the hostname should bypass the proxy
     */
    public boolean shouldBypass(String hostname) {
        if (nonProxyHosts == null || nonProxyHosts.isEmpty() || hostname == null) {
            return false;
        }

        for (String pattern : nonProxyHosts) {
            if (matchesPattern(hostname, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert this configuration to a {@link java.net.Proxy} instance.
     *
     * @return the Java Proxy instance
     */
    public Proxy toJavaProxy() {
        Proxy.Type proxyType;
        switch (type) {
            case HTTP:
                proxyType = Proxy.Type.HTTP;
                break;
            case SOCKS4:
            case SOCKS5:
                proxyType = Proxy.Type.SOCKS;
                break;
            default:
                throw new IllegalStateException("Unknown proxy type: " + type);
        }
        return new Proxy(proxyType, new InetSocketAddress(host, port));
    }

    /**
     * Get the socket address for this proxy.
     *
     * @return the proxy socket address
     */
    public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    private boolean matchesPattern(String hostname, String pattern) {
        if (pattern.equals(hostname)) {
            return true;
        }

        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            return hostname.endsWith(suffix);
        }

        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return hostname.startsWith(prefix);
        }

        if (pattern.contains("*")) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return Pattern.matches(regex, hostname);
        }

        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ProxyConfig{type=").append(type);
        sb.append(", host='").append(host).append('\'');
        sb.append(", port=").append(port);
        if (hasAuthentication()) {
            sb.append(", username='").append(username).append('\'');
            sb.append(", password='****'");
        }
        if (nonProxyHosts != null) {
            sb.append(", nonProxyHosts=").append(nonProxyHosts);
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProxyConfig that = (ProxyConfig) o;
        return port == that.port
                && type == that.type
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(nonProxyHosts, that.nonProxyHosts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, host, port, username, password, nonProxyHosts);
    }

    /** Builder for ProxyConfig. */
    public static class Builder {
        private ProxyType type;
        private String host;
        private int port;
        private String username;
        private String password;
        private Set<String> nonProxyHosts;

        private Builder() {}

        /**
         * Set the proxy type.
         *
         * @param type the proxy type
         * @return this builder
         */
        public Builder type(ProxyType type) {
            this.type = type;
            return this;
        }

        /**
         * Set the proxy server hostname or IP address.
         *
         * @param host the proxy host
         * @return this builder
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Set the proxy server port.
         *
         * @param port the proxy port
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Set the authentication username.
         *
         * @param username the username
         * @return this builder
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * Set the authentication password.
         *
         * @param password the password
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Set the hosts that should bypass the proxy.
         *
         * <p>Supports exact hostnames and wildcard patterns:
         *
         * <ul>
         *   <li>{@code localhost} - exact match
         *   <li>{@code *.internal.com} - suffix match
         *   <li>{@code 192.168.*} - prefix match
         * </ul>
         *
         * @param nonProxyHosts the set of non-proxy hosts
         * @return this builder
         */
        public Builder nonProxyHosts(Set<String> nonProxyHosts) {
            this.nonProxyHosts = nonProxyHosts;
            return this;
        }

        /**
         * Build the ProxyConfig.
         *
         * @return a new ProxyConfig instance
         * @throws NullPointerException if type or host is null
         * @throws IllegalArgumentException if port is out of range
         */
        public ProxyConfig build() {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(host, "host is required");
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            return new ProxyConfig(this);
        }
    }
}
