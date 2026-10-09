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
package io.agentscope.core.session;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Logical identity; storage namespaces remain the responsibility of the workspace backend. */
public record SessionKey(String userId, String agentId, String sessionId) {
    public SessionKey {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (agentId.isBlank() || sessionId.isBlank())
            throw new IllegalArgumentException("Empty session identity");
    }

    public String storagePath() {
        return "agents/"
                + encode(agentId)
                + "/sessions/"
                + encode(userId == null ? "" : userId)
                + "/"
                + encode(sessionId);
    }

    private static String encode(String value) {
        return "s_"
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
