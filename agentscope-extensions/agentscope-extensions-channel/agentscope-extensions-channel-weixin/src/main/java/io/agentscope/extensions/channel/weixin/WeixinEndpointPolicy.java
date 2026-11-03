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

import java.net.URI;
import java.util.Locale;

/** Validates provider endpoints before protocol credentials are sent. */
final class WeixinEndpointPolicy {

    private WeixinEndpointPolicy() {}

    static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String value = raw.strip().replaceAll("/+$", "");
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        URI uri = URI.create(value);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("Invalid iLink base URL");
        }
        if (!isSecureOrLoopback(uri) || !isAllowedHost(uri.getHost(), uri.getPort())) {
            throw new IllegalArgumentException(
                    "iLink base URL is not an approved provider endpoint");
        }
        return value;
    }

    static String validateProviderEndpoint(String current, String candidate) {
        URI from = URI.create(normalizeBaseUrl(current));
        URI to = URI.create(normalizeBaseUrl(candidate));
        boolean loopback = isLoopback(from.getHost()) && isLoopback(to.getHost());
        boolean sameOfficialProvider =
                isOfficialHost(from.getHost()) && isOfficialHost(to.getHost());
        boolean sameHost =
                from.getHost().equalsIgnoreCase(to.getHost()) && from.getPort() == to.getPort();
        if (!isSecureOrLoopback(to)
                || !isAllowedHost(to.getHost(), to.getPort())
                || (!sameHost && !sameOfficialProvider && !loopback)) {
            throw new IllegalStateException("Untrusted iLink endpoint");
        }
        return to.toString();
    }

    private static boolean isSecureOrLoopback(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()));
    }

    private static boolean isAllowedHost(String host, int port) {
        return isLoopback(host) || (isOfficialHost(host) && (port == -1 || port == 443));
    }

    private static boolean isOfficialHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return "ilinkai.weixin.qq.com".equals(lower)
                || "weixin.qq.com".equals(lower)
                || lower.endsWith(".weixin.qq.com");
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
