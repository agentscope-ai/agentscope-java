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
package io.agentcore.managed.server.runtime.managed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** Explicit wire identity; all shared infrastructure keys include the complete scope. */
public record ManagedScope(String tenant, String namespace, String sessionId) {
    public ManagedScope {
        requireText(tenant, "tenant");
        requireText(namespace, "namespace");
        requireText(sessionId, "sessionId");
    }

    public String messageGroup() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest(tenant, namespace, sessionId));
    }

    public static String fingerprint(String... values) {
        return HexFormat.of().formatHex(digest(values));
    }

    private static byte[] digest(String... values) {
        var identity = new StringBuilder();
        for (String value : values) identity.append(value.length()).append(':').append(value);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    public String key(String suffix) {
        requireText(suffix, "key suffix");
        return "ma:{" + messageGroup() + "}:" + suffix;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 1024)
            throw new IllegalArgumentException(
                    name + " must be nonblank and at most 1024 characters");
        return value;
    }
}
