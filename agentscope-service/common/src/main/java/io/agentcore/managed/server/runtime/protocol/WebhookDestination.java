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
package io.agentcore.managed.server.runtime.protocol;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator allowlist shared by subscription admission and physical sending. */
public final class WebhookDestination {
    private final Set<String> hosts;
    private final boolean allowLoopbackHttp;

    public WebhookDestination(String allowedHosts, boolean allowLoopbackHttp) {
        hosts =
                Arrays.stream(allowedHosts.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    public URI validate(String value) {
        if (value == null || value.length() > 4096)
            throw new IllegalArgumentException("Invalid webhook URL");
        URI uri = URI.create(value);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback =
                allowLoopbackHttp
                        && "http".equalsIgnoreCase(uri.getScheme())
                        && Set.of("127.0.0.1", "localhost", "[::1]").contains(host);
        boolean https =
                "https".equalsIgnoreCase(uri.getScheme())
                        && (uri.getPort() == -1 || uri.getPort() == 443)
                        && hosts.contains(host);
        if ((!loopback && !https) || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException(
                    "Webhook requires HTTPS port 443 on an operator-allowed host");
        return uri;
    }
}
