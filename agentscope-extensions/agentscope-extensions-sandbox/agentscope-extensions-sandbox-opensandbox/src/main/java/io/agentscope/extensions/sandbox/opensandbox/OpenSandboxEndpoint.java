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
package io.agentscope.extensions.sandbox.opensandbox;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Parsed OpenSandbox control-plane endpoint. */
record OpenSandboxEndpoint(String protocol, String domain) {

    static OpenSandboxEndpoint parse(String value) {
        String raw = Objects.requireNonNull(value, "endpoint must not be null").trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
        String path = uri.getPath();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || (path != null && !path.matches("/?"))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid OpenSandbox endpoint: " + value);
        }
        String domain = uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        return new OpenSandboxEndpoint(uri.getScheme().toLowerCase(Locale.ROOT), domain);
    }
}
